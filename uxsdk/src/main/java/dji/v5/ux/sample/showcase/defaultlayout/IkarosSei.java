package dji.v5.ux.sample.showcase.defaultlayout;

import java.io.ByteArrayOutputStream;

/**
 * The aircraft's pose, packed as MISB ST 0601 and wrapped in an H.264 SEI.
 *
 * WHY THIS EXISTS
 *
 * Telemetry reaches Ikaros over HTTP while the video goes over RTMP, and
 * PARM has to match the two by wall clock because RTMP carries no metadata
 * track. Two streams aligned by a clock they do not share drift apart:
 * replaying one mission slipped 19 seconds over nine minutes, and a frame
 * handed a pose that stale is tens of metres and a gimbal move out of step,
 * with nothing in the data to say so.
 *
 * Put the pose in the access unit and the question disappears - the frame
 * carries the pose it was taken with.
 *
 * WHY MISB AND NOT SOMETHING SIMPLER
 *
 * PARM already decodes MISB ST 0601: it is what the SRT+KLV path carries,
 * and klv_encoder.py/sei_pose.py are the reference this file was ported
 * from, byte for byte. Inventing a payload would mean a second decoder on
 * the receiving side and two formats to keep in step. The receiving half is
 * already written and tested; this only has to agree with it.
 *
 * The HTTP telemetry is NOT replaced. PARM keeps the timeline as a fallback
 * behind SEI, so an aircraft running an older build of this app keeps
 * geolocating exactly as it did.
 */
public final class IkarosSei {

    private IkarosSei() {}

    /** UAS Local Set universal label — the 16 bytes every ST 0601 packet starts with. */
    private static final byte[] UAS_LS_UL = {
        (byte) 0x06, (byte) 0x0E, (byte) 0x2B, (byte) 0x34,
        (byte) 0x02, (byte) 0x0B, (byte) 0x01, (byte) 0x01,
        (byte) 0x0E, (byte) 0x01, (byte) 0x03, (byte) 0x01,
        (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    };

    /**
     * Marks an SEI message as carrying our pose. Must match
     * sei_pose.IKAROS_POSE_UUID on the PARM side exactly, or the reader
     * skips it — which it should, because encoders write their own
     * user_data_unregistered messages (x264 puts its version string in one)
     * and feeding those to a KLV decoder would fail on every frame.
     */
    private static final byte[] POSE_UUID = {
        (byte) 0x08, (byte) 0x6f, (byte) 0x36, (byte) 0x93,
        (byte) 0xb7, (byte) 0xb3, (byte) 0x4f, (byte) 0x2c,
        (byte) 0x9c, (byte) 0x3a, (byte) 0x1b, (byte) 0x2d,
        (byte) 0x4e, (byte) 0x5f, (byte) 0x60, (byte) 0x71,
    };

    private static final int SEI_NAL_TYPE = 6;
    private static final int PAYLOAD_TYPE_USER_DATA_UNREGISTERED = 5;

    // ── scaling, exactly as ST 0601 defines and klv_encoder.py implements ──

    /** Linear map [lo, hi] onto [0, 2^bits - 1], clamped. */
    static long scaleUnsigned(double value, double lo, double hi, int bits) {
        long n = (1L << bits) - 1;
        long v = Math.round((value - lo) / (hi - lo) * n);
        return Math.max(0, Math.min(n, v));
    }

    /**
     * Linear map [-half, +half] onto +/-(2^(bits-1) - 1).
     *
     * The most-negative integer is ST 0601's "error / no data" sentinel and
     * is never produced, which is why the range is one short of the full
     * two's-complement span.
     */
    static long scaleSigned(double value, double half, int bits) {
        long halfN = (1L << (bits - 1)) - 1;
        long v = Math.round(value / half * halfN);
        return Math.max(-halfN, Math.min(halfN, v));
    }

    /**
     * ST 0601's 16-bit checksum: a running sum where even-indexed bytes go
     * in the high half and odd-indexed in the low half. The spec's
     * pseudocode is 1-based, hence the (i+1).
     */
    static int bcc16(byte[] buf, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            s = (s + (b << (8 * ((i + 1) % 2)))) & 0xFFFF;
        }
        return s;
    }

    private static void ber(ByteArrayOutputStream out, int n) {
        if (n < 128) {
            out.write(n);
            return;
        }
        int bytes = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8;
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write((n >> (8 * i)) & 0xFF);
    }

    private static void tlv(ByteArrayOutputStream out, int tag, byte[] val) {
        out.write(tag);
        out.write(val.length);
        out.write(val, 0, val.length);
    }

    private static byte[] be(long v, int bytes) {
        byte[] b = new byte[bytes];
        for (int i = bytes - 1; i >= 0; i--) { b[i] = (byte) (v & 0xFF); v >>>= 8; }
        return b;
    }

    private static double wrap360(double d) {
        double m = d % 360.0;
        return m < 0 ? m + 360.0 : m;
    }

    /**
     * One UAS LS packet: UL, BER length, items, checksum.
     *
     * altM is what tag 15 carries. This airframe reports only height above
     * the takeoff point (KeyHeightAboveSeaLevel returns nothing on it), so
     * that is what goes in, and PARM's default PARM_KLV_ALTITUDE=takeoff
     * reads it as such — deriving sea level from the terrain at the takeoff
     * point plus this height. An aircraft that can report true MSL should
     * send that instead and the deployment set PARM_KLV_ALTITUDE=msl, which
     * removes the takeoff-elevation assumption entirely.
     */
    public static byte[] encodeMisb0601(long tsMicros, double lat, double lon, double altM,
                                        double headingDeg, double gimbalYawDeg,
                                        double gimbalPitchDeg, double gimbalRollDeg) {
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        tlv(items, 2,  be(tsMicros, 8));
        tlv(items, 5,  be(scaleUnsigned(wrap360(headingDeg), 0.0, 360.0, 16), 2));
        tlv(items, 13, be(scaleSigned(lat, 90.0, 32), 4));
        tlv(items, 14, be(scaleSigned(lon, 180.0, 32), 4));
        tlv(items, 15, be(scaleUnsigned(altM, -900.0, 19000.0, 16), 2));
        tlv(items, 18, be(scaleUnsigned(wrap360(gimbalYawDeg), 0.0, 360.0, 32), 4));
        tlv(items, 19, be(scaleSigned(gimbalPitchDeg, 180.0, 32), 4));
        tlv(items, 20, be(scaleUnsigned(wrap360(gimbalRollDeg), 0.0, 360.0, 32), 4));
        byte[] body = items.toByteArray();

        // The checksum is tag 1 and MUST come last. Its value covers
        // everything from the UL up to and including its own tag and length
        // bytes, so those are laid down before it is computed.
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(UAS_LS_UL, 0, UAS_LS_UL.length);
        ber(pkt, body.length + 2 /* cs header */ + 2 /* cs value */);
        pkt.write(body, 0, body.length);
        pkt.write(1);
        pkt.write(2);
        byte[] upToCs = pkt.toByteArray();
        int cs = bcc16(upToCs, upToCs.length);
        pkt.write((cs >> 8) & 0xFF);
        pkt.write(cs & 0xFF);
        return pkt.toByteArray();
    }

    /**
     * An Annex-B SEI NAL carrying `klv`, ready to prepend to an access unit.
     *
     * Emulation prevention is applied: inside a NAL, any 00 00 followed by a
     * byte <= 3 must have a 0x03 inserted, or a decoder reads the pair as a
     * start code and the stream breaks. It only bites when the payload
     * happens to contain 00 00, which makes omitting it an intermittent,
     * data-dependent fault rather than an obvious one.
     */
    public static byte[] buildSeiNal(byte[] klv) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(PAYLOAD_TYPE_USER_DATA_UNREGISTERED);
        int n = POSE_UUID.length + klv.length;
        while (n >= 255) { body.write(0xFF); n -= 255; }
        body.write(n);
        body.write(POSE_UUID, 0, POSE_UUID.length);
        body.write(klv, 0, klv.length);
        body.write(0x80);  // rbsp_trailing_bits

        byte[] raw = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x01);
        out.write(SEI_NAL_TYPE);
        int zeros = 0;
        for (byte value : raw) {
            int b = value & 0xFF;
            if (zeros >= 2 && b <= 3) { out.write(0x03); zeros = 0; }
            out.write(b);
            zeros = (b == 0) ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }
}
