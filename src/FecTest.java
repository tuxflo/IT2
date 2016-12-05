import java.util.Arrays;

/**
 * Created by tuxflo on 24.11.16.
 */
public class FecTest {
    public static void main(String[] args) throws Exception {
        FECHelper helper = new FECHelper(3);
        VideoStream video; //VideoStream object used to access video frames
        byte[] a = new byte[6014];
        byte[] b = new byte[5940];
        byte[] c = new byte[5925];
        byte[] d = new byte[5860];
        byte[] e = new byte[5771];
        byte[] f = new byte[5689];
        int len = 0;
        video = new VideoStream("movie.mjpeg");
        len = video.getnextframe(a);
        len = video.getnextframe(b);
        len = video.getnextframe(c);
        len = video.getnextframe(d);
        len = video.getnextframe(e);
        len = video.getnextframe(f);
        //          1, 1, 0, 1, 1, 1

        byte[] fec = helper.xor(d, e);
         fec = helper.xor(fec, f);
        System.out.println("Len FEC packet 6: " + fec.length);

        helper.saveImage(1, a);
        helper.saveImage(2, b);
        helper.saveImage(3, c);
        helper.saveImage(4, d);
        helper.saveImage(6, f);
        helper.saveFec(6, fec);
        helper.saveLostPacket(5, e);

        byte[] foo = helper.xor(f, fec);
        foo = Arrays.copyOf(foo, e.length);
        System.out.println("len: " + foo.length);
        System.out.println(Arrays.equals(foo, e));

        byte[] output = helper.reconstruct(5);

        System.out.println("Payload lost packet 5 " + Arrays.toString(output));

    }
}
