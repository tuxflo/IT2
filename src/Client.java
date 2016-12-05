
/* ------------------
   Client
usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]
---------------------- */

import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.Timer;

public class Client{

    private final JLabel recovered;
    private final JLabel lostPacketrateLabel;
    private final JLabel dataRateLabel;
    private final JLabel kLabel;
    //GUI
    //----
    JFrame f = new JFrame("Client");
    JButton setupButton = new JButton("Setup");
    JButton playButton = new JButton("Play");
    JButton pauseButton = new JButton("Pause");
    JButton tearButton = new JButton("Teardown");
    JButton optionsButton = new JButton("Options");
    JPanel mainPanel = new JPanel();
    JPanel buttonPanel = new JPanel();
    JPanel statPanel = new JPanel();
    JLabel iconLabel = new JLabel();
    JLabel statLabel = new JLabel();
    JLabel receivedLabel = new JLabel();
    JLabel receivedFECLabel = new JLabel();
    JLabel lostLabel = new JLabel();
    ImageIcon icon;



    //RTP variables:
    //----------------
    DatagramPacket rcvdp; //UDP packet received from the server
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    static int RTP_RCV_PORT = 25000; //port where the client will receive the RTP packets

    Timer timer; //timer used to receive data from the UDP socket
    Timer statTimer; //timer to update the stat Label
    byte[] buf; //buffer used to store data received from the server
    int k = 0;

    //RTSP variables
    //----------------
    //rtsp states
    final static int BUFFERING = -1;
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;
    final static int OPTIONS = 7;
    final static int BUFFERSIZE = 50;

    static int state; //RTSP state == INIT or READY or PLAYING
    Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file to request to the server
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session
    int RTSPid = 0; //ID of the RTSP session (given by the RTSP Server)
    private int lostPacketCounter = 0;
    private int recoveredCounter = 0;
    public float lostRate = 0.0f;
    private int _timer;
    private int _currentSeqnum;
    private int _incrementalSeqnum = 0;

    final static String CRLF = "\r\n";

    //Video constants:
    //------------------
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    static int FEC_TYPE = 127; //RTP payload type for FEC error detection
    static int LOST = 42; //RTP payload type for FEC error detection
    private int packetCounter = 0;
    private int FECpacketCounter = 0;
    private int oldsequencenum = 0;
    private FECHelper helper = null;
    private long packetDataCounter = 0;
    private int seconds = 0;

    //--------------------------
    //Constructor
    //--------------------------
    public Client() {
        //build GUI
        //--------------------------

        //Frame
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });

        //Buttons
        buttonPanel.setLayout(new GridLayout(1,0));
        buttonPanel.add(setupButton);
        buttonPanel.add(playButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(tearButton);
        buttonPanel.add(optionsButton);

        setupButton.addActionListener(new setupButtonListener());
        playButton.addActionListener(new playButtonListener());
        pauseButton.addActionListener(new pauseButtonListener());
        tearButton.addActionListener(new tearButtonListener());
        optionsButton.addActionListener(new optionsButtonListener());

        //Image display label
        iconLabel.setIcon(null);
        statLabel.setIcon(new ImageIcon("./stat.png",
                "Stats:"));
        statLabel.setText("Statistics:");
        receivedLabel.setText("Received Packets: " + packetCounter);
        receivedFECLabel.setText("Recv FEC packets:" + FECpacketCounter);
        lostLabel.setText("Lost Packets: " + lostPacketCounter);

        //frame layout
        mainPanel.setLayout(null);
        mainPanel.add(iconLabel);
        mainPanel.add(buttonPanel);
        mainPanel.add(statPanel);
        iconLabel.setBounds(0,0,380,280);
        statPanel.setLayout(new GridLayout(0,2));
        statPanel.setBounds(0, 380, 280, 150);
        buttonPanel.setBounds(0,280,380,50);

        //stat Panel
        statPanel.add(statLabel);
        JLabel line = new JLabel("");
        statPanel.add(line);
        statPanel.add(receivedLabel);
        statPanel.add(receivedFECLabel);
        statPanel.add(lostLabel);
        recovered = new JLabel("Recovered packets: " + recoveredCounter);
        lostPacketrateLabel = new JLabel("Lost packet rate: ");
        dataRateLabel = new JLabel("Data rate: ");
        kLabel = new JLabel("k value: " + k);
        statPanel.add(recovered);
        statPanel.add(lostPacketrateLabel);
        statPanel.add(dataRateLabel);
        statPanel.add(kLabel);

        f.getContentPane().add(mainPanel, BorderLayout.CENTER);
        f.setSize(new Dimension(390,590));
        f.setVisible(true);

        //init timer
        //--------------------------
        timer = new Timer(10, new timerListener());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        statTimer = new Timer(1000, new statTimerListener());
        statTimer.setDelay(0);
        statTimer.setCoalesce(true);

        //allocate enough memory for the buffer used to receive data from the server
        buf = new byte[15000];
        _timer = 0;
        _currentSeqnum = 0;
    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String argv[]) throws Exception
    {
        //Create a Client object
        Client theClient = new Client();

        //get server RTSP port and IP address from the command line
        //------------------
        int RTSP_server_port = Integer.parseInt(argv[1]);
        String ServerHost = argv[0];
        InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);

        //get video filename to request:
        VideoFileName = argv[2];

        //Establish a TCP connection with the server to exchange RTSP messages
        //------------------
        theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()) );
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()) );


        //init RTSP state:
        state = INIT;
    }


    //------------------------------------
    //Handler for buttons
    //------------------------------------

    //.............
    //TO COMPLETE
    //.............

    //Handler for Setup button
    //-----------------------
    class setupButtonListener implements ActionListener{
        public void actionPerformed(ActionEvent e){

            //System.out.println("Setup Button pressed !");

            if (state == INIT)
            {
                //Init non-blocking RTPsocket that will be used to receive data
                try{
                    //construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
                    RTPsocket = new DatagramSocket(RTP_RCV_PORT);

                    RTPsocket.setSoTimeout(5);
                }
                catch (SocketException se)
                {
                    System.out.println("Socket exception: "+se);
                    System.exit(0);
                }

                //init RTSP sequence number
                RTSPSeqNb = 1;

                //Send SETUP message to the server
                send_RTSP_request("SETUP");

                //Wait for the response
                if (parse_server_response(SETUP) != 200)
                    System.out.println("Invalid Server Response");
                else
                {
                    //change RTSP state and print new state
                    state = READY;
                    System.out.println("New RTSP state: READY" + state);
                }
            }//else if state != INIT then do nothing
        }
    }

    //Handler for Play button
    //-----------------------
    class playButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){

            //System.out.println("Play Button pressed !");

            if (state == READY)
            {
                //increase RTSP sequence number
                RTSPSeqNb++;

                //Send PLAY message to the server
                send_RTSP_request("PLAY");

                //Wait for the response
                if (parse_server_response(PLAY) != 200)
                    System.out.println("Invalid Server Response");
                else
                {
                    //change RTSP state and print out new state
                    System.out.println("New RTSP state: BUFFERING (" + state + ") ");
                    state = BUFFERING;
                    //start the timer
                    timer.start();
                    statTimer.start();
                }
            }//else if state != READY then do nothing
        }
    }


    //Handler for Pause button
    //-----------------------
    class pauseButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){

            //System.out.println("Pause Button pressed !");

            if (state == PLAYING)
            {
                //increase RTSP sequence number
                RTSPSeqNb += 1;
                //Send PAUSE message to the server
                send_RTSP_request("PAUSE");

                //Wait for the response
                if (parse_server_response(PAUSE) != 200)
                    System.out.println("Invalid Server Response");
                else
                {
                    //change RTSP state and print out new state
                    state = READY;
                    //System.out.println("New RTSP state: READY");

                    //stop the timer
                    timer.stop();
                }
            }
            //else if state != PLAYING then do nothing
        }
    }

    class optionsButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){

            System.out.println("Options Button pressed !");

            //increase RTSP sequence number
            RTSPSeqNb++;

            send_RTSP_request("OPTIONS");
            if(parse_server_response(OPTIONS) != 200)
                System.out.println("Error getting OPTIONS response!");
            else{
                timer.stop();
            }

        }
    }
    //Handler for Teardown button
    //-----------------------
    class tearButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){

            //System.out.println("Teardown Button pressed !");

            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send TEARDOWN message to the server
            send_RTSP_request("TEARDOWN");

            //Wait for the response
            if (parse_server_response(TEARDOWN) != 200)
                System.out.println("Invalid Server Response");
            else
            {
                //change RTSP state and print out new state
                state = INIT;
                System.out.println("New RTSP state: INIT");

                //stop the timer
                timer.stop();

                //exit
                System.exit(0);
            }
        }
    }


    //------------------------------------
    //Handler for timer
    //------------------------------------
    class statTimerListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            receivedLabel.setText("Received packets: " + packetCounter);
            receivedFECLabel.setText("Recv FEC packets: " + FECpacketCounter);
            recovered.setText("Recovered packets: " + recoveredCounter);
            lostLabel.setText("Lost Packets: " + lostPacketCounter);
            kLabel.setText("k value: " + k);
            float lostRate = 0;
            if(packetCounter + FECpacketCounter > 0)
                 lostRate =  lostPacketCounter / (float)(packetCounter + FECpacketCounter);

            lostPacketrateLabel.setText("Lost packet rate: "  + String.format("%.3f", lostRate));
            //if(state == PLAYING) {
            //    seconds++;
            //    dataRateLabel.setText("Data rate: " + String.format("%.3f", (float) (packetDataCounter/1000)));
            //}

        }
    }
    class timerListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(buf, buf.length);
            if(_currentSeqnum == 500) {
                System.out.println("all done, stopping now");
                timer.stop();
            }

            _timer += 10;

            try{
                //receive the DP from the socket:
                RTPsocket.receive(rcvdp);
            }
            catch (InterruptedIOException iioe){
                //System.out.println("Nothing to read");
            }
            catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
            //create an RTPpacket object from the DP
            RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
            packetDataCounter += rcvdp.getLength();

            int sequencenum = rtp_packet.getsequencenumber();
            if(_timer % 100 == 0 && sequencenum < 500) { //new second
                seconds++;
                dataRateLabel.setText("Data rate: " + String.format("%.1f", (float) (packetDataCounter / 1024) / seconds) + " kBit/s");
            }
            k = rtp_packet.getk();

            if(helper == null)
                helper = new FECHelper(k);

//            if(rtp_packet.getpayloadtype() == LOST) {
//                System.out.println("Lost packet image len: " + rtp_packet.getpayload_length());
//                byte[] tmp = Arrays.copyOf(rtp_packet.payload, rtp_packet.getpayload_length());
//                if(helper.getLostPacket(sequencenum) == null)
//                    helper.saveLostPacket(sequencenum, tmp);
//
//            }
            if(rtp_packet.getpayloadtype() == MJPEG_TYPE) {
                byte[] tmp = Arrays.copyOf(rtp_packet.payload, rtp_packet.getpayload_length());
                if(!helper.checkImage(sequencenum)) {
                    int expectedSeqNum = oldsequencenum + 1;
                    lostPacketCounter += sequencenum - expectedSeqNum;
                    helper.saveImage(sequencenum, tmp);
                    packetCounter++;
                    oldsequencenum = sequencenum;
                }
            }


            //if we got here, packet seems to be an FEC packet
            if(rtp_packet.getpayloadtype() == FEC_TYPE ) {
                byte[] tmp = Arrays.copyOf(rtp_packet.payload, rtp_packet.getpayload_length());
                if(helper.getFec(sequencenum) == null) {
                    if(sequencenum != helper.getLastFecNumber() + k) {
                        System.out.printf("FEC packet lost" + sequencenum);
                        lostPacketCounter++;
                    }
                    else
                        FECpacketCounter++;
                    helper.saveFec(sequencenum, tmp);
                }
            }

            if(state == BUFFERING)
            {
                if(helper.getSize() > BUFFERSIZE) {
                    System.out.println("now in state Playing");
                    state = PLAYING;
                }
                return;
            }

            if(state == PLAYING && _timer % 40 == 0)
            {
                _currentSeqnum++;
                int result = -1;
                byte[] display = helper.getImage(_currentSeqnum);
                result = helper.getReturncode();
                if(result == 1)
                    recoveredCounter++;

                //get an Image object from the payload bitstream
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                Image image = toolkit.createImage(display, 0, display.length);

                //display the image as an ImageIcon object
                icon = new ImageIcon(image);
                iconLabel.setIcon(icon);
                //helper.removeImage(_currentSeqnum - k);
                //clean up
                //helper.removeImage(_currentSeqnum);
            }

        }

        private void calculateStatistics(int sequencenum) {
            //calculate statistics

            //_lostPacketCounter += (sequencenum  - _oldSeqNum) - 1;
            //_oldSeqNum = sequencenum ;
            //_lostRate = (float) _lostPacketCounter/_oldSeqNum;

        }
    }

    //------------------------------------
    //Parse Server Response
    //------------------------------------
    private int parse_server_response(int request)
    {
        int reply_code = 0;

        try{
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            //System.out.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);

            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP version
            reply_code = Integer.parseInt(tokens.nextToken());

            //if reply code is OK get and print the 2 other lines
            if (reply_code == 200)
            {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);

                if (request == OPTIONS)
                {
                    String SessionLine = RTSPBufferedReader.readLine();
                    System.out.println(SessionLine);
                    return(reply_code);
                }
                String SessionLine = RTSPBufferedReader.readLine();

                //if state == INIT gets the Session Id from the SessionLine
                tokens = new StringTokenizer(SessionLine);
                tokens.nextToken(); //skip over the Session:
                RTSPid = Integer.parseInt(tokens.nextToken());
            }
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }

        return(reply_code);
    }

    //------------------------------------
    //Send RTSP Request
    //------------------------------------


    private void send_RTSP_request(String request_type)
    {
        try{
            //Use the RTSPBufferedWriter to write to the RTSP socket
            //SETUP movie.mjpeg RTSP/1.0
            //Transport: rtp/udp; compression; port=RTP_RCV_PORT; mode=PLAY

            //write the request line:
            System.out.println(request_type + " " + VideoFileName + " RTSP/1.0");

            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);

            //write the CSeq line:
            System.out.println("CSeq: " + RTSPSeqNb);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb +  CRLF);

            //check if request_type is equal to "SETUP" and in this case write the Transport: line advertising to the server the port used to receive the RTP packets RTP_RCV_PORT
            if(request_type.equals("SETUP"))
            {
                //System.out.println("Transport: rtp/udp; compression; port=" + RTP_RCV_PORT + "; mode=PLAY");
                RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
            }
            //otherwise, write the Session line from the RTSPid field
            else
            {
                RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            }

            RTSPBufferedWriter.flush();
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }

//  public void recover(int lostPacketNum)
//  {
//    if(fecindex < 1 || rtpindex < 1)
//    {
//      System.out.println("Not enough packets buffered, unable to restore");
//      return;
//    }
//	  int length = Math.min(_fecbuffer[fecindex - 1].getlength(), _rtpbuffer[rtpindex - 1].getlength());
//	  byte[] payloadRecovery = new byte[length];
//
//
//	  for (int no = 0; no < Math.min(payloadRecovery.length, _rtpbuffer[rtpindex -1].payload.length); no++)
//	  {
//		  payloadRecovery[no] ^= _rtpbuffer[rtpindex -1].payload[no];
//	  }
//	  System.out.println("Recovered payload: " + Arrays.toString(payloadRecovery) );
//    RTPpacket tmp = new RTPpacket(0, lostPacketNum, 0,payloadRecovery, length);
//    for(int i=rtpindex -1; i<BUFFERSIZE; i++)
//    {
//      if(i == BUFFERSIZE - 1)
//        _rtpbuffer[0] = _rtpbuffer[i];
//      else
//        _rtpbuffer[i+1] = _rtpbuffer[i];
//    }
//    //System.out.println("Inserting recovered package " + lostPacketNum + " at " + rtpindex);
//    _rtpbuffer[rtpindex] = tmp;
//    rtpindex += 1;
//
//
//  }

}//end of Class Client

