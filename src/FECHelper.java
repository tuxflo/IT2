import java.util.*;

/**
 * Created by tuxflo on 24.11.16.
 */
public class FECHelper {
    private HashMap<Integer, byte[]> imageBuffer;
    private HashMap<Integer, byte[]> fecBuffer;
    private HashMap<Integer, byte[]> lostPacketBuffer;


    private int k;
    private int returncode;
    private int lastFecNumber = 0;

    public FECHelper(int k) {
        this.k = k;
        this.returncode = 0;
        imageBuffer = new HashMap<>();
        fecBuffer = new HashMap<>();
        lostPacketBuffer = new HashMap<>();
    }

    public void saveLostPacket(int seqNumber, byte[] lostPacketData) {
        System.out.println("adding lost packet: " + seqNumber);
        lostPacketBuffer.put(seqNumber, lostPacketData);
    }

    public void saveImage(int seqNumber, byte[] imageData) {
        //System.out.println("adding: " + seqNumber  +  " with data len: " + imageData.length);
        byte[] tmp = new byte[15000];
        for(int i = 0; i< imageData.length; i++)
            tmp[i] = imageData[i];
        imageBuffer.put(seqNumber, tmp);
    }
    public byte[] getImage(int seqNumber) {
        //System.out.println("getting: " + seqNumber);

        if(imageBuffer.get(seqNumber) == null)
            return reconstruct(seqNumber);

        returncode = 0;
        imageBuffer.remove(seqNumber - k);
        return imageBuffer.get(seqNumber);
    }

    public byte[] reconstruct(int seqNumber) {
        if(k == 0) {
            System.out.println("unable to reconstruct due missing fec packet");
            returncode = 2;
            return new byte[15000];
        }
        int index = (int) (seqNumber/k) * k;
        if(seqNumber%k != 0)
            index +=k;
        if(seqNumber <= k) {
            index = k;
        }
        System.out.println("call reconstruct with seqnum: " + seqNumber + " Fecnumber: " + index);
        ArrayList<byte[]> group = new ArrayList<>();

        int max_len = 0;
        byte[] fec = fecBuffer.get(index);
        if(fec == null) {
            System.out.println("unable to reconstruct due missing fec packet");
            returncode = 2;
            return new byte[15000];
        }
        group.add(fecBuffer.get(index));

        for(int i= index; i>index - k; i--) {
           byte[] element = imageBuffer.get(i);
            if(element != null) {
                group.add(element);
                System.out.println("adding to group " + i);
                if (element.length > max_len)
                    max_len = element.length;
            }
        }

        if(group.size() != k) {
            System.out.println("unable to reconstruct due missing packets in group");
            returncode = 3;
            return new byte[15000];
        }

        byte[] buffer = null;
        for (int j = 0; j < k - 1; j++) {
            if(j == 0)
                    buffer = xor(group.get(j), group.get(j + 1));
            else
                buffer = xor(buffer, group.get(j+1));
        }

//        byte[] tmp = Arrays.copyOf(buffer, lostPacketBuffer.get(seqNumber).length);
//        System.out.println("Payload reco packet " + seqNumber + " " + Arrays.toString(buffer));
//        System.out.println("Payload lost packet " + seqNumber + " " + Arrays.toString(lostPacketBuffer.get(seqNumber)));
//        System.out.println("Payload equal: " + Arrays.equals(tmp, lostPacketBuffer.get(seqNumber)) + "len buffer: " + tmp.length + " len lost packet: " + lostPacketBuffer.get(seqNumber).length);
        returncode = 1;
        return buffer;
    }

    public byte[] xor(byte[] a1, byte[] a2) {
        byte[] a = a1.clone();
        byte[] b = a2.clone();
        if(a.length > b.length) {
            byte[] tmp = b;
            b = a;
            a = tmp;
        }
        for(int i = 0; i < a.length; i++)
            b[i] ^= a[i];
        return b.clone();
    }

    public void saveFec(int fecNumber, byte[] fec) {
        //System.out.println("adding fec: " + fecNumber + " with len: " + fec.length);
        byte[] tmp = new byte[15000];
        for(int i = 0; i< fec.length; i++)
            tmp[i] = fec[i];
        fecBuffer.put(fecNumber, tmp);
        lastFecNumber = fecNumber;
    }

    public int getReturncode() {
        return returncode;
    }
    public int getSize() {
        return imageBuffer.size();
    }

    public int getLastFecNumber() {
        return lastFecNumber;
    }
    public void removeImage(int seqNumber) {
        imageBuffer.remove(seqNumber);
    }

    public void removeFec(int seqNumber) {
        fecBuffer.remove(seqNumber);
    }

    public byte[] getFec(int i) {
        //fecBuffer.remove(i - k);
        return fecBuffer.get(i);
    }

    public boolean checkImage(int sequencenum) {
        return imageBuffer.containsKey(sequencenum);
    }

    public byte[] getLostPacket(int sequencenum) {
        return lostPacketBuffer.get(sequencenum);
    }

}
