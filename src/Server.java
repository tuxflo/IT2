
/* ------------------
   Server
usage: java Server [RTSP listening port]
---------------------- */


import java.io.*;
import java.lang.reflect.Array;
import java.net.*;
import java.nio.ByteBuffer;
import java.awt.*;
import java.util.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;


public class Server extends JFrame implements ActionListener {

    private final JLabel kValueLabel;
    private static JSpinner kValueSpinner;
    //RTP variables:
    //----------------
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    DatagramPacket senddp; //UDP packet containing the video frames

    InetAddress ClientIPAddr; //Client IP address
    int RTP_dest_port = 0; //destination port for RTP packets  (given by the RTSP Client)

    //GUI:
    //----------------
    JLabel label;

    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted
    VideoStream video; //VideoStream object used to access video frames
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    static int FEC_TYPE = 127; //RTP payload type for FEC error detection
    static int LOST = 42; //RTP payload type for FEC error detection
    static int FRAME_PERIOD = 20; //Frame period of the video to stream, in ms
    static int VIDEO_LENGTH = 500; //length of the video in frames

    Timer timer; //timer used to send the images at the video frame rate
    byte[] buf; //buffer used to store the images to send to the client

    //RTSP variables
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    //rtsp message types
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;
    final static int OPTIONS = 7;
    static int k = 5; //Send FEC after 3rd packet

    static int state; //RTSP Server state == INIT or READY or PLAY
    Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file requested from the client
    static int RTSP_ID = 123456; //ID of the RTSP session
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session

    final static String CRLF = "\r\n";
    private ChangeListener listener;
    private ChangeListener kValueListener;
    private double _error_rate = 0.0;
    private byte[] payloadRecovery;
    private byte[] oldpacket;
    private int _fecNumber;
    private FECHelper helper;
    private ArrayList<byte[]> group;
    //--------------------------------
    //Constructor
    //--------------------------------
    public Server(){

        //init Frame
        super("Server");
        group = new ArrayList<>();
        _fecNumber = 0;
        oldpacket = null;
        helper = new FECHelper(k);

        payloadRecovery = new byte[0];
        listener = new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                JSpinner s = (JSpinner) e.getSource();
                System.out.println("State changed" + s.getValue());

                _error_rate = (double)s.getValue();
            }
        };

        kValueListener = new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                JSpinner s = (JSpinner) e.getSource();
                System.out.println("new k value: " + s.getValue());
                double value = (double) s.getValue();
                k = (int) value;
            }
        };
        //init Timer
        timer = new Timer(FRAME_PERIOD, this);
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //allocate memory for the sending buffer
        buf = new byte[15000];

        //Handler to close the main window
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                //stop the timer and exit
                timer.stop();
                System.exit(0);
            }});

        //GUI:
        label = new JLabel("Send frame #        ", JLabel.CENTER);
        kValueLabel = new JLabel("k Value", JLabel.CENTER);
        // from 0 to 100, in 1.0 steps start value 5
        SpinnerNumberModel model1 = new SpinnerNumberModel(0.0, 0.0, 100.0, 1.0);
        JSpinner spinner = new JSpinner(model1);
        SpinnerNumberModel kValueModell = new SpinnerNumberModel(0.0, 0.0, 20.0, 1.0);
        kValueSpinner = new JSpinner(kValueModell);
        kValueSpinner.addChangeListener(kValueListener);
        spinner.addChangeListener(listener);
        getContentPane().setLayout(new GridLayout(0, 2));
        getContentPane().add(label);
        getContentPane().add(spinner);
        getContentPane().add(kValueLabel);
        getContentPane().add(kValueSpinner);
        spinner.setValue((double)10.0);
        kValueSpinner.setValue(2.0);
    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String argv[]) throws Exception
    {
        //create a Server object
        Server theServer = new Server();

        //show GUI:
        theServer.pack();
        theServer.setVisible(true);

        //get RTSP socket port from the command line
        int RTSPport = Integer.parseInt(argv[0]);

        //Initiate TCP connection with the client for the RTSP session
        ServerSocket listenSocket = new ServerSocket(RTSPport);
        theServer.RTSPsocket = listenSocket.accept();
        listenSocket.close();

        //Get Client IP address
        theServer.ClientIPAddr = theServer.RTSPsocket.getInetAddress();

        //Initiate RTSPstate
        state = INIT;

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(theServer.RTSPsocket.getInputStream()) );
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theServer.RTSPsocket.getOutputStream()) );

        //Wait for the SETUP message from the client
        int request_type;
        boolean done = false;
        while(!done)
        {
            request_type = theServer.parse_RTSP_request(); //blocking

            if (request_type == SETUP)
            {
                done = true;

                //update RTSP state
                state = READY;
                System.out.println("New RTSP state: READY");

                //Send response
                theServer.send_RTSP_response(SETUP);

                //init the VideoStream object:
                theServer.video = new VideoStream(VideoFileName);

                //init RTP socket
                theServer.RTPsocket = new DatagramSocket();
            }
            else if(request_type == OPTIONS)
            {
                theServer.send_RTSP_response(OPTIONS);
            }
        }

        //loop to handle RTSP requests
        while(true)
        {
            //parse the request
            request_type = theServer.parse_RTSP_request(); //blocking

            if ((request_type == PLAY) && (state == READY))
            {
                //send back response
                theServer.send_RTSP_response(PLAY);
                //start timer
                theServer.timer.start();
                //update state
                state = PLAYING;
                System.out.println("New RTSP state: PLAYING");
                JFormattedTextField tf = ((JSpinner.DefaultEditor) kValueSpinner.getEditor()).getTextField();
                tf.setEditable(false);
                tf.setBackground(Color.GRAY);
                kValueSpinner.setEnabled(false);
            }
            else if ((request_type == PAUSE) && (state == PLAYING))
            {
                //send back response
                theServer.send_RTSP_response(PAUSE);
                //stop timer
                theServer.timer.stop();
                //update state
                state = READY;
                System.out.println("New RTSP state: READY");
            }
            else if (request_type == TEARDOWN)
            {
                //send back response
                theServer.send_RTSP_response(TEARDOWN);
                //stop timer
                theServer.timer.stop();
                //close sockets
                theServer.RTSPsocket.close();
                theServer.RTPsocket.close();

                System.exit(0);
            }
            else if(request_type == OPTIONS)
            {
                theServer.send_RTSP_response(OPTIONS);
            }
        }
    }

    //------------------------
    //Handler for timer
    //------------------------
    public void actionPerformed(ActionEvent e) {

        //if the current image nb is less than the length of the video
        if (imagenb < VIDEO_LENGTH)
        {
            //update current imagenb
            imagenb++;

            try {
                //get next frame to send from the video, as well as its size
                int image_length = video.getnextframe(buf);

                group.add(Arrays.copyOf(buf, image_length));

                //System.out.println("seqnum: " + imagenb + " image len: " + image_length);

                //Builds an RTPpacket object containing the frame
                RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, buf, image_length, k);

                //get to total length of the full rtp packet to send
                int packet_length = rtp_packet.getlength();

                //retrieve the packet bitstream and store it in an array of bytes
                byte[] packet_bits = new byte[packet_length];
                rtp_packet.getpacket(packet_bits);

                //send the packet as a DatagramPacket over the UDP socket
                senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
                if(!checkError()) {
                    RTPsocket.send(senddp);
                    //rtp_packet.setPayloadType(LOST);
                }

                if(imagenb % k == 0 )
                {
                    System.out.println("group size: " + group.size());
                    byte[] fec = null;
                    for (int j = 0; j < k - 1; j++) {
                        if(j == 0)
                            fec = helper.xor(group.get(j), group.get(j + 1));
                        else
                            fec = helper.xor(fec, group.get(j+1));
                    }

                    System.out.println("payload fec len: " + fec.length);
                    RTPpacket fec_packet = new RTPpacket(FEC_TYPE, imagenb, imagenb*FRAME_PERIOD, fec, fec.length, k);
                    int fec_length = fec_packet.getlength();

                    //retrieve the packet bitstream and store it in an array of bytes
                    byte[] fec_bits = new byte[fec_length];
                    fec_packet.getpacket(fec_bits);
                    DatagramPacket sendfec = new DatagramPacket(fec_bits, fec_length, ClientIPAddr, RTP_dest_port);
                    //System.out.println("sending fec: " + imagenb + " with len: " + fec.length);;
                    if(!checkError())
                        RTPsocket.send(sendfec);
                    //important: reset after sending FEC
                    group.clear();

                }

                //print the header bitstream
                //rtp_packet.printheader();

                //update GUI
                label.setText("Send frame #" + imagenb);
                Thread.sleep(5);
            }
            catch(Exception ex)
            {
                System.out.println("Exception caught: "+ex);
                System.exit(0);
            }
        }
        else
        {
            //if we have reached the end of the video file, stop the timer
            timer.stop();
        }
    }

    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    private int parse_RTSP_request()
    {
        int request_type = -1;
        try{
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            //System.out.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ((new String(request_type_string)).compareTo("SETUP") == 0)
                request_type = SETUP;
            else if ((new String(request_type_string)).compareTo("PLAY") == 0)
                request_type = PLAY;
            else if ((new String(request_type_string)).compareTo("PAUSE") == 0)
                request_type = PAUSE;
            else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)
                request_type = TEARDOWN;
            else if ((new String(request_type_string)).compareTo("OPTIONS") == 0)
                request_type = OPTIONS;

            if (request_type == SETUP)
            {
                //extract VideoFileName from RequestLine
                VideoFileName = tokens.nextToken();
            }

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());

            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            if (request_type == SETUP)
            {
                //extract RTP_dest_port from LastLine
                tokens = new StringTokenizer(LastLine);
                for (int i=0; i<3; i++)
                    tokens.nextToken(); //skip unused stuff
                RTP_dest_port = Integer.parseInt(tokens.nextToken());
            }
            else if(request_type == OPTIONS)
            {
                System.out.println("parsed Options request...");
            }
            //else LastLine will be the SessionId line ... do not check for now.
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
        return(request_type);
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    private void send_RTSP_response(int type)
    {
        try{
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            if(type == OPTIONS)
            {
                //OPTIONS request
                RTSPBufferedWriter.write("Public: SETUP, TEARDOWN, PLAY, PAUSE"+CRLF);
                System.out.println("OPTIONS request");
            }
            else
            {
                RTSPBufferedWriter.write("Session: "+RTSP_ID+CRLF);
            }
            RTSPBufferedWriter.flush();
            //System.out.println("RTSP Server - Sent response to Client.");
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }

    private boolean checkError()
    {
        //simulate packet loss
        int rand = new java.util.Random().nextInt(100) + 1;
        if(rand < _error_rate)
        {
            System.out.println("Packet loss...");
            return true;
        }
        return false;

    }
}
