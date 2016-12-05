public class FECpacket2 {
    //size of the RTP header:
    public int SnBase;

    //size of the RTP payload
    public int payload_size;
    public int PayloadType;
    //Bitstream of the RTP payload
    public byte[] payload;
    private RTPpacket _data;

    public FECpacket2(RTPpacket p1, RTPpacket p2, int imageb, int period) {
        PayloadType = 127;
        int len1 = p1.getpayload_length();
		int len2 = p2.getpayload_length();

        //Calculate packet lengths
		int len_short, len_long;

		if (len1 < len2)
			len_short = len1;
		else
			len_short = len2;

		len_long = ((len1 > len2) ? len1 : len2);

		byte[] payload1 = new byte[len1];
		byte[] payload2 = new byte[len2];
		byte[] buf = new byte[len_long + 2]; // +2 byte um sich die laenge des
		// kuerzeren payloads zu merken

		p1.getpayload(payload1);
		p2.getpayload(payload2);

		// XOR solange bis das kuerzeste array am ende ist
		for (int i = 0; i < len_short; i++)
			buf[i] = (byte) (payload1[i] ^ payload2[i]);

		// den rest des laengeren arrays noch anhaengen
		if (len1 < len2) {
			// int len = len1;
			for (int i = len1; i < len2; i++)
				buf[i] = (byte) (payload2[i]);
		} else if (len1 > len2) {
			// int len = len1 - len2;
			for (int i = len2; i < len1; i++)
				buf[i] = (byte) (payload1[i]);
		}

		buf[len_long] = (byte) (len_short >> 8); // high byte payload laenge des kuerzeren payloads
		buf[len_long + 1] = (byte) ((int) (len_short & 0xFF)); // low byte payload laenge des kuerzeren payloads
		_data = new RTPpacket(127, imageb, period, buf, len_long + 2 , 3);
    }

    public RTPpacket getFECPacket() {
		return _data;
	}
}

