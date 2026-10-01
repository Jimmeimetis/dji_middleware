package dji.v5.ux.sample.showcase.defaultlayout;

import android.util.Log;


import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.manager.datacenter.camera.StreamInfo;
import dji.v5.manager.interfaces.ICameraStreamManager;

/**
 * Answers, on real hardware, whether we can publish the video ourselves.
 *
 * Carrying the aircraft's pose inside the video means taking over
 * publishing: LiveStreamManager pushes RTMP internally with no hook to
 * alter the bitstream, so the pose NAL has to be inserted into frames we
 * get from addReceiveStreamListener and sent by our own client.
 *
 * Three things have to be true for that, and none can be checked off the
 * aircraft:
 *
 *   1. the listener fires at all WHILE the live view is on screen. The
 *      same decoder drives the preview, and a previous attempt at this
 *      (addReceiveStreamListener -> UdpSender, still commented out above)
 *      was abandoned for reasons nobody recorded.
 *   2. the buffers are Annex-B H.264, which is what the injector and an
 *      RTMP client both expect.
 *   3. DJI's own per-frame SEI is in THESE buffers. It is in the published
 *      RTMP - 1,672 packets in 60s of a recorded flight - but that is the
 *      SDK's output, not necessarily its input.
 *
 * Logs once a second, so it can be left on during a normal flight without
 * drowning logcat. Reads only; it never touches the stream.
 */
public final class IkarosStreamProbe {

    private static final String TAG = "IkarosProbe";

    private final ICameraStreamManager streamManager;
    private ICameraStreamManager.ReceiveStreamListener listener;
    private ComponentIndexType camera;

    private long windowStartMs;
    private int buffers;
    private long bytes;
    private int annexB;
    private int withDjiSei;
    private int nalTypesSeen;   // bitmask of NAL types 0..31
    private boolean loggedFirst;

    public IkarosStreamProbe(ICameraStreamManager streamManager) {
        this.streamManager = streamManager;
    }

    public void start(ComponentIndexType cameraIndex) {
        if (listener != null) return;
        camera = cameraIndex;
        windowStartMs = System.currentTimeMillis();
        listener = (buffer, offset, length, streamInfo) ->
                onBuffer(buffer, offset, length, streamInfo);
        streamManager.addReceiveStreamListener(cameraIndex, listener);
        Log.i(TAG, "probe attached to camera " + cameraIndex
                + " — if nothing follows within a second or two, the listener "
                + "does not fire while the live view holds the decoder");
    }

    public void stop() {
        if (listener == null) return;
        streamManager.removeReceiveStreamListener(listener);
        listener = null;
        Log.i(TAG, "probe detached from camera " + camera);
    }

    private void onBuffer(byte[] b, int offset, int length, StreamInfo info) {
        buffers++;
        bytes += length;

        boolean isAnnexB = length > 4 && b[offset] == 0 && b[offset + 1] == 0
                && (b[offset + 2] == 1 || (b[offset + 2] == 0 && b[offset + 3] == 1));
        if (isAnnexB) annexB++;

        boolean sawDjiSei = false;
        int end = offset + length;
        for (int i = offset; i + 4 < end; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            int hdr = (b[i + 2] == 1) ? i + 3
                    : ((b[i + 2] == 0 && b[i + 3] == 1) ? i + 4 : -1);
            if (hdr < 0 || hdr >= end) continue;
            int t = b[hdr] & 0x1F;
            if (t < 32) nalTypesSeen |= (1 << t);
            // SEI payload type is the first byte of the message; DJI uses 101.
            if (t == 6 && hdr + 1 < end && (b[hdr + 1] & 0xFF) == 101) sawDjiSei = true;
        }
        if (sawDjiSei) withDjiSei++;

        if (!loggedFirst) {
            loggedFirst = true;
            Log.i(TAG, "FIRST BUFFER — the listener DOES fire with the live view up."
                    + " len=" + length + " annexB=" + isAnnexB
                    + " mime=" + (info == null ? "?" : String.valueOf(info.getMimeType()))
                    + " " + (info == null ? "?" : info.getWidth() + "x" + info.getHeight()
                        + "@" + info.getFrameRate()));
        }

        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 1000) {
            StringBuilder types = new StringBuilder();
            for (int t = 0; t < 32; t++) {
                if ((nalTypesSeen & (1 << t)) != 0) types.append(t).append(' ');
            }
            Log.i(TAG, String.format(
                    "%d buf/s  %.0f kB/s  annexB=%d/%d  withDjiSei=%d/%d  nalTypes=[%s]",
                    buffers, bytes / 1024.0, annexB, buffers, withDjiSei, buffers,
                    types.toString().trim()));
            windowStartMs = now;
            buffers = 0;
            bytes = 0;
            annexB = 0;
            withDjiSei = 0;
            nalTypesSeen = 0;
        }
    }
}
