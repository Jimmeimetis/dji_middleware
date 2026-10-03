package dji.v5.ux.sample.showcase.defaultlayout;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

import dji.v5.manager.interfaces.ICameraStreamManager;

/**
 * Encodes the aircraft's decoded frames ourselves, at a bitrate we choose.
 *
 * WHY THIS EXISTS
 *
 * The publisher used to take the RC's own encode off addReceiveStreamListener
 * and forward it untouched. That stream's bitrate is not ours to set, and on
 * this hardware nothing in the SDK will talk it down:
 *
 *   setLiveVideoBitrate       - steers LiveStreamManager's internal RTMP
 *                               publish only, which we replace.
 *   setStreamEncoderBitrate   - exists on ICameraStreamManager, and the
 *                               aircraft REFUSES it. Measured on an RC Pro
 *                               Enterprise: requested 2000000, readback -1.
 *
 * Meanwhile the RC's hardware encoder runs at whatever it likes - logcat
 * showed OMX-VENC at 6.6 to 11 Mbps, 30 fps. Over a weak RC uplink that does
 * not drain, and SRT answers a shortfall by buffering: the server held a
 * 12.9 MB receive buffer with RTT at 5.6 SECONDS and only 68 lost packets in
 * 119 MB. Not a lossy link - an overrun one. Roughly 15 seconds of video sat
 * in that buffer, which is the latency PARM and the operator were seeing, and
 * periodically the connection collapsed outright.
 *
 * addFrameListener hands us DECODED frames instead, so we can encode them at
 * a rate the link can carry. DJI already decodes for display, so the added
 * cost is the encode alone, on hardware, a few milliseconds.
 *
 * BACKPRESSURE IS THE POINT
 *
 * encode() never queues. It asks for an input buffer with a zero timeout and
 * drops the frame if the encoder has none free. That is real backpressure,
 * measured at the one place that knows - the encoder itself - rather than
 * inferred from a send cache that stays empty because the SRT client hands
 * frames straight to the socket. The old congestion check read that cache and
 * so never fired: droppedCongested sat at 0 while the stream ran 15s late.
 *
 * WHAT THIS COSTS
 *
 * The frame callback carries no timestamp, unlike StreamInfo on the encoded
 * path. Presentation time therefore comes from the controller's clock here,
 * not the aircraft's capture time. That matters less than it sounds: the pose
 * welded into each frame was ALREADY sampled as "now" rather than at capture,
 * and removing ~15s of buffering moves "now" far closer to the truth than the
 * aircraft timestamp ever bought us while the stream ran that late.
 */
final class IkarosFrameEncoder {

    private static final String TAG = "IkarosFrameEnc";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    /**
     * Seconds between keyframes.
     *
     * A reader joining mid-stream shows nothing until one arrives, and the
     * congestion path skips to the next keyframe when it sheds - so a long
     * interval turns a brief shortfall into a long freeze. At two seconds a
     * single late frame could cost two seconds of video, which is most of
     * what "choppy on a poor link" actually was. One second halves that, and
     * halves the join latency for the browser, for a few percent of bitrate.
     */
    private static final int I_FRAME_INTERVAL_S = 1;

    interface Sink {
        /** Parameter sets, once, before any frame. Annex-B, start codes included. */
        void onConfig(byte[] sps, byte[] pps);

        /** One access unit, Annex-B. */
        void onEncoded(byte[] annexB, long ptsUs, boolean keyframe);
    }

    private final Sink sink;

    private MediaCodec codec;
    private int width;
    private int height;
    private int bitrateBps;
    private int fps;

    private long firstFrameNs = Long.MIN_VALUE;
    private byte[] nv12;

    private int droppedNoInputBuffer;

    /**
     * Decimation, so the publish rate is ours rather than the camera's.
     *
     * The aircraft may hand us 60 fps. Encoding all of it doubles the bitrate
     * needed for the same picture quality on a link that has already proved it
     * has none to spare, and a 60 fps inspection video buys nothing - PARM
     * samples frames and the operator is watching people walk.
     *
     * The gate is 70% of the nominal interval, not 100%: at a true 30 fps the
     * callback jitters either side of 33.3 ms, and an exact gate would reject
     * every slightly-early frame and halve the rate to 15.
     *
     * 70% rather than the 85% this started at, because 85% was still too
     * tight. Measured on an RC Pro Enterprise the camera delivers 30-31 fps
     * with enough jitter to put the occasional pair 25 ms apart - inside the
     * 28.3 ms gate - so it binned 1 to 2 good frames a second for nothing.
     * 23.3 ms leaves room for that and still rejects every second frame of a
     * 60 fps source (16.7 ms), which is the only thing this is here to do.
     */
    private long lastAcceptedNs = Long.MIN_VALUE;
    private int droppedToRate;
    private int framesSeen;
    private long rateWindowStartNs = Long.MIN_VALUE;

    IkarosFrameEncoder(Sink sink) {
        this.sink = sink;
    }

    /** Frames the encoder had no room for. Real pressure. */
    int takeDroppedNoInputBuffer() {
        int n = droppedNoInputBuffer;
        droppedNoInputBuffer = 0;
        return n;
    }

    /** Frames discarded to hold the publish rate. Expected, not pressure. */
    int takeDroppedToRate() {
        int n = droppedToRate;
        droppedToRate = 0;
        return n;
    }

    /**
     * Change the target bitrate on a running encoder.
     *
     * Supported from Android 19 via PARAMETER_KEY_VIDEO_BITRATE. Leaves the
     * stream running - no keyframe, no reconfigure.
     */
    void setBitrate(int bps) {
        bitrateBps = bps;
        MediaCodec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            c.setParameters(b);
            Log.i(TAG, "bitrate set to " + bps + " bps");
        } catch (Exception e) {
            Log.e(TAG, "could not change bitrate", e);
        }
    }

    /**
     * Feed one decoded frame. Returns false if it was dropped.
     *
     * The encoder is configured on the first frame rather than up front,
     * because the frame callback is what tells us the real dimensions.
     */
    boolean encode(byte[] data, int offset, int length, int w, int h,
                   ICameraStreamManager.FrameFormat format, int targetBitrateBps, int targetFps) {
        // What the camera is ACTUALLY giving us, once a second. Worth knowing:
        // the difference between a 30 and a 60 fps source changes what the
        // link is being asked to carry, and it is not documented anywhere.
        long now = System.nanoTime();
        framesSeen++;
        if (rateWindowStartNs == Long.MIN_VALUE) rateWindowStartNs = now;
        if (now - rateWindowStartNs >= 1_000_000_000L) {
            Log.i(TAG, "camera delivering " + framesSeen + " fps, encoding at " + targetFps);
            framesSeen = 0;
            rateWindowStartNs = now;
        }

        if (targetFps > 0) {
            long minIntervalNs = (long) (1_000_000_000L / targetFps * 0.70);
            if (lastAcceptedNs != Long.MIN_VALUE && (now - lastAcceptedNs) < minIntervalNs) {
                droppedToRate++;
                return false;
            }
            lastAcceptedNs = now;
        }

        if (codec == null) {
            if (!configure(w, h, targetBitrateBps, targetFps)) return false;
        }
        if (w != width || h != height) {
            // The aircraft changed resolution mid-flight (camera mode switch).
            // Rebuild rather than feed the encoder buffers it cannot read.
            Log.i(TAG, "resolution changed " + width + "x" + height + " -> " + w + "x" + h);
            release();
            if (!configure(w, h, targetBitrateBps, targetFps)) return false;
        }

        byte[] input = toNv12(data, offset, length, w, h, format);
        if (input == null) return false;

        MediaCodec c = codec;
        try {
            // A few milliseconds, not zero. At 30 fps there are 33 ms per
            // frame, so waiting 5 ms for a buffer the encoder is about to
            // free costs nothing measurable and avoids discarding a frame
            // over a momentary hiccup - which is what a zero timeout did,
            // shedding 1 to 4 frames a second on an otherwise healthy link.
            // Still bounded: if the encoder is genuinely behind, the frame
            // goes in the bin rather than into a queue that becomes latency.
            int in = c.dequeueInputBuffer(5000);
            if (in < 0) {
                droppedNoInputBuffer++;
                drain();
                return false;
            }
            ByteBuffer buf = c.getInputBuffer(in);
            if (buf == null) {
                droppedNoInputBuffer++;
                return false;
            }
            buf.clear();
            int n = Math.min(input.length, buf.capacity());
            buf.put(input, 0, n);

            if (firstFrameNs == Long.MIN_VALUE) firstFrameNs = System.nanoTime();
            long ptsUs = (System.nanoTime() - firstFrameNs) / 1000L;

            c.queueInputBuffer(in, 0, n, ptsUs, 0);
            drain();
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "encoder in bad state", e);
            return false;
        }
    }

    private boolean configure(int w, int h, int bps, int targetFps) {
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bps);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S);
            // CBR: the whole reason this class exists is a predictable rate on
            // a link that cannot absorb a VBR spike.
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            width = w;
            height = h;
            bitrateBps = bps;
            fps = targetFps;
            nv12 = new byte[w * h * 3 / 2];
            firstFrameNs = Long.MIN_VALUE;
            lastAcceptedNs = Long.MIN_VALUE;
            Log.i(TAG, "encoder started " + w + "x" + h + " @" + targetFps + "fps " + bps + " bps CBR");
            return true;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "could not start encoder", e);
            release();
            return false;
        }
    }

    /**
     * Hand finished access units to the sink.
     *
     * Non-blocking, and called after every queued frame rather than on its own
     * thread: the encoder's output is what we are here for, and leaving it
     * undrained stalls the input side a frame or two later.
     */
    private void drain() {
        MediaCodec c = codec;
        if (c == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out;
            try {
                out = c.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                Log.e(TAG, "drain failed", e);
                return;
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The reliable source of the parameter sets. MediaCodec hands
                // csd-0 (SPS) and csd-1 (PPS) over ALREADY SEPARATED, so this
                // avoids splitting a concatenated buffer by hand - which is
                // what crashed the first build: a 4-byte start code contains a
                // 3-byte one at offset 1, the scan matched inside the leading
                // start code, and setVideoInfo got a 1-byte SPS
                // ("IndexOutOfBoundsException: index=1 out of bounds (limit=1)"
                // from H26XPacket.getStartCodeSize).
                try {
                    MediaFormat f = c.getOutputFormat();
                    ByteBuffer csd0 = f.getByteBuffer("csd-0");
                    ByteBuffer csd1 = f.getByteBuffer("csd-1");
                    if (csd0 != null && csd1 != null) {
                        sink.onConfig(bytesOf(csd0), bytesOf(csd1));
                    } else {
                        Log.e(TAG, "output format carried no csd-0/csd-1");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "could not read csd from output format", e);
                }
                continue;
            }
            if (out < 0) return;

            ByteBuffer buf = c.getOutputBuffer(out);
            if (buf == null) {
                c.releaseOutputBuffer(out, false);
                continue;
            }
            byte[] au = new byte[info.size];
            buf.position(info.offset);
            buf.get(au, 0, info.size);
            c.releaseOutputBuffer(out, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // csd for AVC is SPS then PPS, both with start codes, in one
                // buffer. Split on the second start code.
                byte[][] sp = splitParameterSets(au);
                if (sp != null) sink.onConfig(sp[0], sp[1]);
                else Log.e(TAG, "could not split csd, " + au.length + "B");
                continue;
            }
            if (info.size == 0) continue;

            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            sink.onEncoded(au, info.presentationTimeUs, key);
        }
    }

    private static byte[] bytesOf(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    /** Index of the next Annex-B start code at or after `from`, else -1. */
    private static int indexOfStartCode(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 2 < b.length; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            if (b[i + 2] == 1) return i;
            if (i + 3 < b.length && b[i + 2] == 0 && b[i + 3] == 1) return i;
        }
        return -1;
    }

    /**
     * SPS and PPS out of one codec-config buffer, start codes kept.
     *
     * Only used if the output format did not carry csd-0/csd-1. The search
     * for the SECOND start code begins past the first NAL's header byte: a
     * 4-byte start code (00 00 00 01) contains a 3-byte one (00 00 01) at
     * offset 1, so scanning from 1 finds the start code it is standing in and
     * splits off a 1-byte SPS.
     */
    private static byte[][] splitParameterSets(byte[] csd) {
        int first = indexOfStartCode(csd, 0);
        if (first < 0) return null;
        int firstSize = (csd[first + 2] == 1) ? 3 : 4;
        int second = indexOfStartCode(csd, first + firstSize + 1);
        if (second <= 0) return null;
        byte[] sps = new byte[second];
        byte[] pps = new byte[csd.length - second];
        System.arraycopy(csd, 0, sps, 0, sps.length);
        System.arraycopy(csd, second, pps, 0, pps.length);
        return new byte[][]{sps, pps};
    }

    /**
     * Into the one layout the encoder was configured for.
     *
     * COLOR_FormatYUV420SemiPlanar is NV12 - Y plane, then U and V
     * INTERLEAVED. DJI's NV21 is the same shape with the chroma pair the
     * other way round, so the conversion is a swap, not a resample. Getting
     * it backwards does not fail loudly: it encodes fine and the picture
     * comes out with the colours inverted.
     */
    private byte[] toNv12(byte[] src, int offset, int length, int w, int h,
                          ICameraStreamManager.FrameFormat format) {
        int ySize = w * h;
        int need = ySize * 3 / 2;
        if (length < need) {
            Log.e(TAG, "frame too small: " + length + "B, need " + need + "B for " + w + "x" + h);
            return null;
        }
        if (nv12 == null || nv12.length != need) nv12 = new byte[need];

        System.arraycopy(src, offset, nv12, 0, ySize);

        if (format == ICameraStreamManager.FrameFormat.NV21) {
            for (int i = ySize; i + 1 < need; i += 2) {
                nv12[i] = src[offset + i + 1];   // U
                nv12[i + 1] = src[offset + i];   // V
            }
        } else {
            // YUV420_888 arrives here planar: Y, then a full U plane, then V.
            int cSize = ySize / 4;
            int u = offset + ySize;
            int v = u + cSize;
            for (int i = 0; i < cSize; i++) {
                nv12[ySize + i * 2] = src[u + i];
                nv12[ySize + i * 2 + 1] = src[v + i];
            }
        }
        return nv12;
    }

    void release() {
        MediaCodec c = codec;
        codec = null;
        if (c == null) return;
        try { c.stop(); } catch (Exception ignored) { }
        try { c.release(); } catch (Exception ignored) { }
        Log.i(TAG, "encoder released");
    }
}
