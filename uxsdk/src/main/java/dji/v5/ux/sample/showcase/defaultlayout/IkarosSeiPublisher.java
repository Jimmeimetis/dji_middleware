package dji.v5.ux.sample.showcase.defaultlayout;

import android.media.MediaCodec;
import android.util.Log;

import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.srt.srt.SrtClient;

import java.nio.ByteBuffer;

import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.manager.datacenter.camera.StreamInfo;
import dji.v5.manager.interfaces.ICameraStreamManager;

/**
 * Publishes the camera stream ourselves, with the aircraft's pose welded
 * into every frame.
 *
 * The pose is a MISB ST 0601 packet in an H.264 SEI NAL, inserted before
 * the first coded slice of each access unit. PARM reads it back off the
 * frame it arrived on, so there is no pairing step and nothing to go out
 * of sync: a detection is geolocated against the pose the camera actually
 * had when it saw it, not against whichever HTTP telemetry sample landed
 * nearest in time.
 *
 * That is the whole reason this class exists. LiveStreamManager publishes
 * RTMP internally and offers no hook to alter the bitstream, so carrying
 * anything inside the video means taking over the push. The cost is real
 * and is listed here rather than discovered later:
 *
 *   * reconnection, start/stop and bitrate become ours. The SDK's
 *     LiveStreamManager handled all three.
 *   * addReceiveStreamListener hands us the camera's own encode, measured
 *     at ~6.4 Mbps against the 3 Mbps LiveStreamManager was configured to
 *     push. We cannot re-encode without a decode/encode round trip, so
 *     the uplink carries roughly twice what it did.
 *
 * Transport is SRT, and the reason is bitstream shape rather than link
 * quality - see the dependency comment in uxsdk/build.gradle. Briefly:
 * RTMP and RTSP in this library both assume one NAL per frame, which an
 * [SEI][IDR] access unit is not. MPEG-TS carries it verbatim.
 */
public final class IkarosSeiPublisher {

    private static final String TAG = "IkarosSeiPub";

    /**
     * Where the pose comes from, sampled per frame.
     *
     * A supplier rather than a setter: the aircraft's state is already
     * being tracked for the telemetry POST, and copying it into this class
     * on a timer would add a second staleness window to a feature whose
     * entire point is removing one.
     */
    public interface PoseSource {
        /** Null when nothing usable has been reported yet. */
        Pose currentPose();
    }

    /** One sample. altM is height above the takeoff point - see IkarosSei. */
    public static final class Pose {
        public final double lat;
        public final double lon;
        public final double altM;
        public final double headingDeg;
        public final double gimbalYawDeg;
        public final double gimbalPitchDeg;
        public final double gimbalRollDeg;

        public Pose(double lat, double lon, double altM, double headingDeg,
                    double gimbalYawDeg, double gimbalPitchDeg, double gimbalRollDeg) {
            this.lat = lat;
            this.lon = lon;
            this.altM = altM;
            this.headingDeg = headingDeg;
            this.gimbalYawDeg = gimbalYawDeg;
            this.gimbalPitchDeg = gimbalPitchDeg;
            this.gimbalRollDeg = gimbalRollDeg;
        }
    }

    private final ICameraStreamManager streamManager;
    private final PoseSource poseSource;

    private SrtClient srtClient;
    private ICameraStreamManager.ReceiveStreamListener listener;
    private ComponentIndexType camera;

    private byte[] sps;
    private byte[] pps;
    private boolean videoInfoSent;

    /**
     * PTS of the first frame, so the stream starts near zero.
     *
     * StreamInfo.getPresentationTimeMs was measured on the aircraft as
     * epoch milliseconds, 2 ms behind the controller's wall clock and
     * monotonic. Sending it raw would hand the muxer a PTS of ~1.7e15 us;
     * MPEG-TS PTS is 33 bits at 90 kHz and wraps in about 26 hours, so the
     * absolute value is meaningless anyway. The epoch time that matters is
     * the one inside the KLV, where it has 64 bits to live in.
     */
    private long firstPtsMs = Long.MIN_VALUE;

    // Reported once a second rather than per frame: at 30 fps a per-frame
    // line is 30 lines a second of logcat during a flight.
    private long windowStartMs;
    private int framesIn;
    private int framesOut;
    private int droppedNoPose;
    private int droppedNoConfig;

    public IkarosSeiPublisher(ICameraStreamManager streamManager, PoseSource poseSource) {
        this.streamManager = streamManager;
        this.poseSource = poseSource;
    }

    /** True once the SRT client is connected and sending. */
    public boolean isStreaming() {
        return srtClient != null && srtClient.isStreaming();
    }

    /**
     * Connect and start injecting.
     *
     * `url` is MediaMTX's publish form, with the credentials in the
     * streamid rather than as URL userinfo:
     *
     *   srt://host:8890?streamid=publish:live/&lt;vehicleId&gt;:&lt;user&gt;:&lt;pass&gt;
     *
     * RootEncoder reads the streamid query parameter when there is one and
     * falls back to the URL path otherwise, so this passes through to the
     * handshake untouched. The userinfo form is deliberately not used: it
     * was intermittently refused on the RTMP side and the query form was
     * not.
     */
    public void start(ComponentIndexType cameraIndex, String url, ConnectChecker checker) {
        if (listener != null) {
            Log.w(TAG, "already started");
            return;
        }
        camera = cameraIndex;
        sps = null;
        pps = null;
        videoInfoSent = false;
        firstPtsMs = Long.MIN_VALUE;
        windowStartMs = System.currentTimeMillis();

        srtClient = new SrtClient(checker);
        // No microphone is involved: the aircraft gives us video only, and
        // leaving audio enabled makes the client wait for a track that will
        // never arrive.
        srtClient.setOnlyVideo(true);
        srtClient.setVideoCodec(VideoCodec.H264);
        srtClient.connect(url);

        listener = (buffer, offset, length, streamInfo) ->
                onBuffer(buffer, offset, length, streamInfo);
        streamManager.addReceiveStreamListener(cameraIndex, listener);
        Log.i(TAG, "publishing camera " + cameraIndex + " to " + redact(url));
    }

    public void stop() {
        if (listener != null) {
            streamManager.removeReceiveStreamListener(listener);
            listener = null;
        }
        if (srtClient != null) {
            srtClient.disconnect();
            srtClient = null;
        }
        Log.i(TAG, "stopped publishing camera " + camera);
    }

    private void onBuffer(byte[] b, int offset, int length, StreamInfo info) {
        framesIn++;

        // Parameter sets arrive in the stream (measured: nalTypes=[1 5 7 8]),
        // not through a separate codec-config callback, so they are picked
        // out here. They are also left in the buffer: the MPEG-TS packetiser
        // strips the ones it knows from keyframes and re-emits them after the
        // access unit delimiter, which is where they belong.
        scanConfig(b, offset, length);

        SrtClient client = srtClient;
        if (client == null) return;

        if (!videoInfoSent) {
            if (sps == null || pps == null) {
                droppedNoConfig++;
                report();
                return;
            }
            // With their start codes: that is what MediaCodec's csd-0/csd-1
            // carry, which is what RootEncoder is written against, and what
            // its keyframe-stripping compares byte for byte against the
            // access unit.
            client.setVideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), null);
            videoInfoSent = true;
            Log.i(TAG, "sent video config: sps=" + sps.length + "B pps=" + pps.length + "B");
        }

        Pose pose = poseSource.currentPose();
        if (pose == null) {
            // Publishing frames with no pose would produce video PARM cannot
            // geolocate, silently. Better to hold until the aircraft reports
            // one - that is seconds at startup, not a flight-long failure.
            droppedNoPose++;
            report();
            return;
        }

        long ptsMs = (info == null) ? System.currentTimeMillis() : info.getPresentationTimeMs();
        if (firstPtsMs == Long.MIN_VALUE) firstPtsMs = ptsMs;

        // The KLV timestamp is the aircraft's capture time in epoch
        // microseconds - the whole point of using presentationTimeMs rather
        // than the controller's clock, which is late by the uplink latency.
        byte[] klv = IkarosSei.encodeMisb0601(
                ptsMs * 1000L,
                pose.lat, pose.lon, pose.altM, pose.headingDeg,
                pose.gimbalYawDeg, pose.gimbalPitchDeg, pose.gimbalRollDeg);
        byte[] au = IkarosSeiInjector.insertBeforeFirstSlice(
                b, offset, length, IkarosSei.buildSeiNal(klv));

        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, au.length, (ptsMs - firstPtsMs) * 1000L,
                containsIdr(b, offset, length) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        client.sendVideo(ByteBuffer.wrap(au), bi);
        framesOut++;
        report();
    }

    /** Remember the parameter sets, which the SRT client needs up front. */
    private void scanConfig(byte[] b, int offset, int length) {
        if (sps != null && pps != null) return;
        int end = offset + length;
        for (int i = offset; i + 3 < end; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            int hdr;
            if (b[i + 2] == 1) {
                hdr = i + 3;
            } else if (b[i + 2] == 0 && b[i + 3] == 1) {
                hdr = i + 4;
            } else {
                continue;
            }
            if (hdr >= end) return;
            int type = b[hdr] & 0x1F;
            if (type != 7 && type != 8) continue;
            int next = nextStart(b, hdr, end);
            byte[] nal = new byte[next - i];
            System.arraycopy(b, i, nal, 0, nal.length);
            if (type == 7 && sps == null) sps = nal;
            if (type == 8 && pps == null) pps = nal;
            if (sps != null && pps != null) return;
        }
    }

    /** Offset of the next start code at or after `from`, else `end`. */
    private static int nextStart(byte[] b, int from, int end) {
        for (int i = from; i + 3 < end; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            if (b[i + 2] == 1 || (b[i + 2] == 0 && b[i + 3] == 1)) return i;
        }
        return end;
    }

    /**
     * Whether this access unit carries an IDR.
     *
     * Read off the NAL types rather than taken from the SDK, which offers
     * no keyframe flag here. Getting it wrong costs more than it looks:
     * the packetiser only emits the parameter sets on frames marked as
     * keyframes, so a missed IDR means a receiver that joins mid-stream
     * never gets an SPS and shows nothing.
     */
    private static boolean containsIdr(byte[] b, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i + 3 < end; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            int hdr;
            if (b[i + 2] == 1) {
                hdr = i + 3;
            } else if (b[i + 2] == 0 && b[i + 3] == 1) {
                hdr = i + 4;
            } else {
                continue;
            }
            if (hdr >= end) return false;
            if ((b[hdr] & 0x1F) == 5) return true;
        }
        return false;
    }

    private void report() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs < 1000) return;
        Log.i(TAG, framesIn + " in / " + framesOut + " out per s"
                + "  droppedNoPose=" + droppedNoPose
                + "  droppedNoConfig=" + droppedNoConfig
                + "  streaming=" + isStreaming());
        windowStartMs = now;
        framesIn = 0;
        framesOut = 0;
        droppedNoPose = 0;
        droppedNoConfig = 0;
    }

    /** The streamid carries the publish password; keep it out of logcat. */
    static String redact(String url) {
        int at = url.indexOf("streamid=");
        if (at < 0) return url;
        int colon = url.indexOf(':', at);
        if (colon < 0) return url.substring(0, at) + "streamid=<redacted>";
        // Keep "publish:live/<id>", drop the user and password after it.
        int second = url.indexOf(':', colon + 1);
        if (second < 0) return url;
        return url.substring(0, second) + ":<redacted>";
    }
}
