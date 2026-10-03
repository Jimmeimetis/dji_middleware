package dji.v5.ux.sample.showcase.defaultlayout;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RTMP publisher: enough to push one H.264 track and no more.
 *
 * WHY THIS EXISTS, rather than a library
 *
 * Every option was tried and measured first:
 *
 *   RootEncoder SRT   - holds video. Against the SAME MediaMTX at the same
 *                       moment and the same ~2 Mbps, ffmpeg sat on a 0.04 MB
 *                       receive buffer at 0 ms RTT while this sat on 22.76 MB
 *                       at 5545 ms: about 20 seconds of video held on arrival,
 *                       with the server receiving every byte and ICMP to the
 *                       controller at 54 ms, 0% loss. The library implements
 *                       SRT by hand with no TSBPD/latency handling at all.
 *
 *   RootEncoder RTMP  - corrupts the frame. H264Packet writes ONE 4-byte
 *                       length over the whole buffer, so an [SEI][IDR] access
 *                       unit becomes a single type-6 NAL whose declared length
 *                       swallows the keyframe.
 *
 *   RootEncoder 2.8.1 - fixes that (NalReader.extractNals, a length per NAL)
 *                       but carries Kotlin 2.2 metadata; this project is on
 *                       1.8.10 and pedroSG94's 1.8 line ends at 2.5.4-1.8.22.
 *
 *   ffmpeg-kit        - collides with the DJI SDK, which bundles its OWN
 *                       ffmpeg: both ship lib/arm64-v8a/libavcodec.so, DJI at
 *                       Lavc58 (4.x) and the kit at Lavc62 (8.1.2). Whichever
 *                       is packaged is used by both, and breaking DJI's
 *                       decoder would take addFrameListener with it - the
 *                       source of our frames.
 *
 * So the last mile is ours. It is a small protocol for this one job: connect,
 * publish, and write video messages. No audio, no reading back media, no
 * seeking. The part the libraries got wrong - a length prefix PER NAL, so the
 * pose SEI and the picture travel in one access unit - is the part this gets
 * right by construction.
 */
final class IkarosRtmpClient {

    private static final String TAG = "IkarosRtmp";

    /** RTMP's own default is 128 bytes, which at 2 Mbps is a lot of headers. */
    private static final int CHUNK_SIZE = 4096;

    private static final int CSID_CONTROL = 2;
    private static final int CSID_COMMAND = 3;
    private static final int CSID_VIDEO = 6;

    private static final int MSG_SET_CHUNK_SIZE = 1;
    private static final int MSG_AMF0_COMMAND = 20;
    private static final int MSG_VIDEO = 9;

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;
    private int streamId = 1;
    private volatile boolean connected;

    boolean isConnected() { return connected; }

    /**
     * Connect and begin publishing.
     *
     * `url` is the full publish URL, credentials in the query string:
     *   rtmp://host:1935/live/3?user=...&pass=...
     * mediamtx ignores the rtmp://user:pass@host form and answers
     * "authentication failed" with no further detail.
     */
    void connect(String url, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 1935;

        // rtmp://host/live/3 -> app "live", stream "3". The query rides on the
        // stream name AND the tcUrl: mediamtx reads it from the tcUrl, and
        // other servers look at the publish argument, so send it in both.
        String path = u.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        int slash = path.indexOf('/');
        String app = slash < 0 ? path : path.substring(0, slash);
        String stream = slash < 0 ? "" : path.substring(slash + 1);
        String query = u.getRawQuery();
        String streamArg = (query == null || query.isEmpty()) ? stream : stream + "?" + query;
        String tcUrl = "rtmp://" + host + ":" + port + "/" + app
                + ((query == null || query.isEmpty()) ? "" : "?" + query);

        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        in = new DataInputStream(socket.getInputStream());

        handshake();
        setChunkSize(CHUNK_SIZE);
        sendConnect(app, tcUrl);
        readUntilResult("connect");
        sendCreateStream();
        streamId = readCreateStreamResult();
        sendPublish(streamArg);

        connected = true;
        Log.i(TAG, "publishing app=" + app + " stream=" + stream + " streamId=" + streamId);
    }

    /**
     * The simple handshake: C0/C1, then echo S1 back as C2.
     *
     * Not the digest-signed variant. That exists for Flash-era servers that
     * verify it; mediamtx, nginx-rtmp and ffmpeg all accept the plain form.
     */
    private void handshake() throws IOException {
        byte[] c1 = new byte[1536];
        new SecureRandom().nextBytes(c1);
        // time = 0, zero = 0; the rest stays random.
        for (int i = 0; i < 8; i++) c1[i] = 0;

        out.write(3);
        out.write(c1);
        out.flush();

        int s0 = in.read();
        if (s0 != 3) throw new IOException("bad RTMP version from server: " + s0);
        byte[] s1 = new byte[1536];
        in.readFully(s1);
        byte[] s2 = new byte[1536];
        in.readFully(s2);

        out.write(s1);   // C2 echoes S1
        out.flush();
    }

    private void setChunkSize(int size) throws IOException {
        byte[] p = new byte[]{
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size
        };
        writeMessage(CSID_CONTROL, 0, MSG_SET_CHUNK_SIZE, 0, p);
        out.flush();
    }

    private void sendConnect(String app, String tcUrl) throws IOException {
        Amf a = new Amf();
        a.string("connect");
        a.number(1);
        a.objectStart();
        a.prop("app", app);
        a.prop("type", "nonprivate");
        a.prop("flashVer", "FMLE/3.0 (compatible; Ikaros)");
        a.prop("tcUrl", tcUrl);
        a.objectEnd();
        writeMessage(CSID_COMMAND, 0, MSG_AMF0_COMMAND, 0, a.toByteArray());
        out.flush();
    }

    private void sendCreateStream() throws IOException {
        Amf a = new Amf();
        a.string("createStream");
        a.number(2);
        a.nul();
        writeMessage(CSID_COMMAND, 0, MSG_AMF0_COMMAND, 0, a.toByteArray());
        out.flush();
    }

    private void sendPublish(String streamName) throws IOException {
        Amf a = new Amf();
        a.string("publish");
        a.number(3);
        a.nul();
        a.string(streamName);
        a.string("live");
        writeMessage(CSID_COMMAND, 0, MSG_AMF0_COMMAND, streamId, a.toByteArray());
        out.flush();
    }

    /**
     * The AVC sequence header: what tells a decoder how to read the rest.
     *
     * AVCDecoderConfigurationRecord, with lengthSizeMinusOne = 3, which is the
     * promise that every NAL in every later frame is prefixed by a 4-byte
     * length. Honouring that promise for EVERY NAL - not just the first - is
     * the thing RootEncoder's packetiser got wrong.
     */
    void sendVideoConfig(byte[] spsAnnexB, byte[] ppsAnnexB, int timestampMs) throws IOException {
        byte[] sps = stripStartCode(spsAnnexB);
        byte[] pps = stripStartCode(ppsAnnexB);
        if (sps.length < 4) throw new IOException("SPS too short: " + sps.length);

        byte[] p = new byte[16 + sps.length + pps.length];
        int i = 0;
        p[i++] = 0x17;                 // keyframe | AVC
        p[i++] = 0x00;                 // AVC sequence header
        p[i++] = 0; p[i++] = 0; p[i++] = 0;   // composition time
        p[i++] = 0x01;                 // configurationVersion
        p[i++] = sps[1];               // AVCProfileIndication
        p[i++] = sps[2];               // profile_compatibility
        p[i++] = sps[3];               // AVCLevelIndication
        p[i++] = (byte) 0xFF;          // 111111 + lengthSizeMinusOne = 3
        p[i++] = (byte) 0xE1;          // 111 + numOfSPS = 1
        p[i++] = (byte) (sps.length >>> 8);
        p[i++] = (byte) sps.length;
        System.arraycopy(sps, 0, p, i, sps.length); i += sps.length;
        p[i++] = 0x01;                 // numOfPPS
        p[i++] = (byte) (pps.length >>> 8);
        p[i++] = (byte) pps.length;
        System.arraycopy(pps, 0, p, i, pps.length); i += pps.length;

        writeMessage(CSID_VIDEO, timestampMs, MSG_VIDEO, streamId, trim(p, i));
        out.flush();
        Log.i(TAG, "sent AVC sequence header: sps=" + sps.length + "B pps=" + pps.length + "B");
    }

    /**
     * One access unit, Annex-B in, AVCC out.
     *
     * EVERY NAL gets its own 4-byte length, which is what lets an [SEI][IDR]
     * unit survive: the decoder reads the SEI, then reads the picture, instead
     * of reading one NAL whose length covers both.
     *
     * Parameter sets are dropped here. They travel in the sequence header, and
     * a decoder that sees them again inline is at best doing nothing useful.
     */
    void sendVideo(byte[] annexB, int offset, int length, boolean keyframe, int timestampMs)
            throws IOException {
        List<int[]> nals = splitNals(annexB, offset, offset + length);
        if (nals.isEmpty()) return;

        int payload = 5;
        for (int[] n : nals) {
            int type = annexB[n[0]] & 0x1F;
            if (type == 7 || type == 8 || type == 9) continue;
            payload += 4 + (n[1] - n[0]);
        }
        if (payload == 5) return;

        byte[] p = new byte[payload];
        int i = 0;
        p[i++] = (byte) (keyframe ? 0x17 : 0x27);
        p[i++] = 0x01;                 // NALU
        p[i++] = 0; p[i++] = 0; p[i++] = 0;   // composition time
        for (int[] n : nals) {
            int type = annexB[n[0]] & 0x1F;
            if (type == 7 || type == 8 || type == 9) continue;
            int len = n[1] - n[0];
            p[i++] = (byte) (len >>> 24);
            p[i++] = (byte) (len >>> 16);
            p[i++] = (byte) (len >>> 8);
            p[i++] = (byte) len;
            System.arraycopy(annexB, n[0], p, i, len);
            i += len;
        }
        writeMessage(CSID_VIDEO, timestampMs, MSG_VIDEO, streamId, p);
        out.flush();
    }

    /** [start,end) of each NAL's payload, start codes excluded. */
    private static List<int[]> splitNals(byte[] b, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = from;
        int cur = -1;
        while (i + 2 < to) {
            boolean sc3 = b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1;
            boolean sc4 = i + 3 < to && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1;
            if (sc3 || sc4) {
                if (cur >= 0) out.add(new int[]{cur, i});
                i += sc4 ? 4 : 3;
                cur = i;
            } else {
                i++;
            }
        }
        if (cur >= 0 && cur < to) out.add(new int[]{cur, to});
        return out;
    }

    private static byte[] stripStartCode(byte[] nal) {
        int i = 0;
        if (nal.length > 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) i = 4;
        else if (nal.length > 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) i = 3;
        byte[] o = new byte[nal.length - i];
        System.arraycopy(nal, i, o, 0, o.length);
        return o;
    }

    private static byte[] trim(byte[] b, int len) {
        if (len == b.length) return b;
        byte[] o = new byte[len];
        System.arraycopy(b, 0, o, 0, len);
        return o;
    }

    /**
     * One RTMP message, split across chunks.
     *
     * The first chunk carries a type-0 header (full: timestamp, length, type,
     * stream id); every continuation is type-3, which is header-only-by-
     * reference and costs one byte. A 2 Mbps stream at 4 KB chunks is a few
     * hundred of these a second, so the saving is not academic.
     */
    private void writeMessage(int csid, int timestamp, int type, int msgStreamId, byte[] payload)
            throws IOException {
        boolean extended = timestamp >= 0xFFFFFF;
        int ts = extended ? 0xFFFFFF : timestamp;

        out.write((0 << 6) | csid);                 // fmt 0
        out.write(ts >>> 16); out.write(ts >>> 8); out.write(ts);
        out.write(payload.length >>> 16);
        out.write(payload.length >>> 8);
        out.write(payload.length);
        out.write(type);
        // message stream id is LITTLE endian here, alone in the protocol.
        out.write(msgStreamId);
        out.write(msgStreamId >>> 8);
        out.write(msgStreamId >>> 16);
        out.write(msgStreamId >>> 24);
        if (extended) {
            out.write(timestamp >>> 24); out.write(timestamp >>> 16);
            out.write(timestamp >>> 8); out.write(timestamp);
        }

        int off = 0;
        int remaining = payload.length;
        int n = Math.min(remaining, CHUNK_SIZE);
        out.write(payload, off, n);
        off += n; remaining -= n;

        while (remaining > 0) {
            out.write((3 << 6) | csid);             // fmt 3 continuation
            if (extended) {
                out.write(timestamp >>> 24); out.write(timestamp >>> 16);
                out.write(timestamp >>> 8); out.write(timestamp);
            }
            n = Math.min(remaining, CHUNK_SIZE);
            out.write(payload, off, n);
            off += n; remaining -= n;
        }
    }

    /**
     * Read chunks until the server answers the named command.
     *
     * Deliberately shallow: we parse enough to find _result or _error and to
     * step over everything else. A publisher does not need to understand the
     * server's window-ack or bandwidth messages, only to not choke on them.
     */
    private void readUntilResult(String what) throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] msg = readMessage();
            if (msg == null) continue;
            String s = new String(msg, StandardCharsets.ISO_8859_1);
            if (s.contains("_error")) throw new IOException(what + " rejected: " + printable(s));
            if (s.contains("_result") || s.contains("onStatus")) return;
        }
        throw new IOException("timed out waiting for " + what);
    }

    private int readCreateStreamResult() throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] msg = readMessage();
            if (msg == null) continue;
            String s = new String(msg, StandardCharsets.ISO_8859_1);
            if (s.contains("_error")) throw new IOException("createStream rejected: " + printable(s));
            if (s.contains("_result")) {
                // ... "_result", transaction, null, streamId(double)
                int i = s.indexOf("_result");
                if (i >= 0) {
                    int p = i + "_result".length();
                    // skip: number marker + 8, then null marker
                    for (int k = p; k + 8 < msg.length; k++) {
                        if ((msg[k] & 0xFF) == 0x00 && k + 8 < msg.length) {
                            // the LAST number in the message is the stream id
                        }
                    }
                }
                int id = lastAmfNumber(msg);
                return id > 0 ? id : 1;
            }
        }
        return 1;
    }

    /** The last AMF0 number in a message, which for _result is the stream id. */
    private static int lastAmfNumber(byte[] b) {
        int found = -1;
        for (int i = 0; i + 8 < b.length; i++) {
            if ((b[i] & 0xFF) != 0x00) continue;
            long bits = 0;
            for (int k = 1; k <= 8; k++) bits = (bits << 8) | (b[i + k] & 0xFFL);
            double d = Double.longBitsToDouble(bits);
            if (d >= 0 && d < 1e6 && d == Math.floor(d)) found = (int) d;
        }
        return found;
    }

    /** One message, reassembled across chunks. Returns null for a skipped one. */
    private byte[] readMessage() throws IOException {
        int b0 = in.read();
        if (b0 < 0) throw new EOFException("server closed");
        int fmt = (b0 >>> 6) & 3;
        int csid = b0 & 0x3F;
        if (csid == 0) csid = 64 + in.read();
        else if (csid == 1) { int a = in.read(); int c = in.read(); csid = 64 + a + (c << 8); }

        int len = 0, type = 0;
        if (fmt <= 1) {
            in.skipBytes(3);                     // timestamp / delta
            len = (in.read() << 16) | (in.read() << 8) | in.read();
            type = in.read();
            if (fmt == 0) in.skipBytes(4);       // message stream id
        } else if (fmt == 2) {
            in.skipBytes(3);
            return null;
        } else {
            return null;
        }
        if (len < 0 || len > 1 << 20) throw new IOException("absurd message length " + len);

        byte[] payload = new byte[len];
        int off = 0;
        while (off < len) {
            int n = Math.min(len - off, CHUNK_SIZE);
            in.readFully(payload, off, n);
            off += n;
            if (off < len) {
                int hdr = in.read();              // the fmt-3 continuation byte
                if (hdr < 0) throw new EOFException("server closed mid-message");
            }
        }
        if (type == MSG_SET_CHUNK_SIZE) return null;
        return payload;
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 32 && c < 127) sb.append(c);
        return sb.toString();
    }

    void close() {
        connected = false;
        try { if (out != null) out.flush(); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null; out = null; in = null;
    }

    /** Just the AMF0 this publisher emits: string, number, null, one object. */
    private static final class Amf {
        private final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();

        void string(String s) {
            byte[] v = s.getBytes(StandardCharsets.UTF_8);
            b.write(0x02);
            b.write(v.length >>> 8); b.write(v.length);
            b.write(v, 0, v.length);
        }
        void number(double d) {
            long bits = Double.doubleToLongBits(d);
            b.write(0x00);
            for (int i = 7; i >= 0; i--) b.write((int) (bits >>> (i * 8)) & 0xFF);
        }
        void nul() { b.write(0x05); }
        void objectStart() { b.write(0x03); }
        void objectEnd() { b.write(0x00); b.write(0x00); b.write(0x09); }
        void prop(String k, String v) {
            byte[] kb = k.getBytes(StandardCharsets.UTF_8);
            b.write(kb.length >>> 8); b.write(kb.length);
            b.write(kb, 0, kb.length);
            string(v);
        }
        byte[] toByteArray() { return b.toByteArray(); }
    }
}
