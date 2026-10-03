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
     * How full the send cache may get before frames start being dropped.
     *
     * Low on purpose. The cache exists to ride out a brief stall, not to
     * store video: anything sitting in it is already late by the time it
     * would be sent, and a drone operator wants the current picture, not a
     * complete one.
     */
    private static final float CONGESTION_PCT = 20f;

    /**
     * Send-cache size, in items.
     *
     * At 30 fps this is about two seconds - enough to absorb a hiccup,
     * bounded enough that a sustained shortfall cannot eat the heap. The
     * default is far larger, which is what let the queue grow until the
     * process was killed.
     */
    private static final int CACHE_FRAMES = 60;

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
    private ICameraStreamManager.CameraFrameListener frameListener;
    private IkarosFrameEncoder encoder;
    private IkarosRtmpPublisher rtmp;

    /**
     * Adaptive bitrate, deliberately two-state.
     *
     * A link that cannot carry the stream has to give something up. Dropping
     * frames is the visible choice - and because a P-frame needs the ones
     * before it we must skip to the next keyframe, so one late frame costs a
     * run of them. Lowering the bitrate gives up detail instead and keeps the
     * motion, which is what someone watching people move around a site needs.
     *
     * Two levels, not a control loop. Gradual multiplicative stepping sounds
     * better and is where these things go wrong: it hunts, it interacts with
     * the encoder's own rate control, and when it misbehaves at 400 ft the
     * logs show a dozen intermediate rates and no clear story. Ceiling or
     * floor, with hysteresis on the way back up, is predictable and easy to
     * read off a log line.
     *
     * FLOOR is 2 Mbps and that is a hard limit, not a target: below it the
     * picture stops being good enough to inspect PPE from, which is the whole
     * job. If the operator sets the ceiling AT the floor - as 2 Mbps does -
     * there is nothing to give up and adaptation turns itself off rather than
     * pretending to work.
     */
    private static final int BITRATE_FLOOR_BPS = 2_000_000;
    private static final float PRESSURE_DOWN = 0.5f;
    private static final int CLEAR_SECONDS_BEFORE_UP = 5;

    /**
     * Tag 2 carries the CONTROLLER's clock, and that was measured, not assumed.
     *
     * The alternative was the aircraft's own presentationTimeMs off
     * addReceiveStreamListener. Running both for a minute: the two agreed to
     * within 2-9 ms on average, so the aircraft clock buys nothing - and it
     * arrives through a "latest frame info" read rather than with our frame,
     * which added a +/-20 ms frame race (one frame at 30 fps). Simpler clock,
     * no race, same answer.
     *
     * Neither is the true exposure time: both are "when the RC handled the
     * frame". That lateness is systematic, so it cancels between two aircraft
     * of the same type - but it would NOT cancel across airframes with
     * different downlink latencies, which matters if detections from several
     * drones are ever merged by timestamp.
     */
    private boolean adaptive;
    private int bitrateCeilingBps;
    private int bitrateNowBps;
    private int clearSeconds;
    private int targetBitrateBps;
    private int targetFps;
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

    /**
     * Last PTS actually sent, so the stream never steps backwards.
     *
     * getPresentationTimeMs is MOSTLY monotonic - the probe measured it as
     * such over a flight - but "mostly" is not the guarantee a muxer needs.
     * It occasionally repeats or goes back a frame or two, and MediaMTX does
     * not tolerate it: "DTS is not monotonically increasing, was 119413710,
     * now is 119408130" and it DROPS EVERY READER on the path. PARM's two
     * connections died together, reconnected, and the recorder logged drift
     * and reset - from one 62 ms step backwards.
     *
     * So the publisher's clock is derived from the aircraft's but enforced
     * here. The KLV keeps the aircraft's real capture time, which is the
     * one geolocation uses; this only governs presentation order.
     */
    private long lastPtsUs = Long.MIN_VALUE;

    // Reported once a second rather than per frame: at 30 fps a per-frame
    // line is 30 lines a second of logcat during a flight.
    private long windowStartMs;
    private int framesIn;
    private int framesOut;
    private int droppedNoPose;
    private int droppedNoConfig;
    private int backwardsPts;
    private int droppedCongested;
    private int droppedEncoderFull;
    private int droppedToRate;
    private int droppedLinkFull;

    /**
     * Once behind, drop whole GOPs rather than individual frames.
     *
     * A P-frame references the ones before it, so dropping an arbitrary
     * frame corrupts every frame after it until the next IDR - the decoder
     * shows smeared blocks rather than a clean gap. Skipping to the next
     * keyframe costs the same video but resumes cleanly.
     */
    private boolean skipUntilKeyframe;

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
        lastPtsUs = Long.MIN_VALUE;
        skipUntilKeyframe = false;
        windowStartMs = System.currentTimeMillis();

        srtClient = new SrtClient(checker);
        // No microphone is involved: the aircraft gives us video only, and
        // leaving audio enabled makes the client wait for a track that will
        // never arrive.
        srtClient.setOnlyVideo(true);
        srtClient.setVideoCodec(VideoCodec.H264);
        srtClient.resizeCache(CACHE_FRAMES);
        srtClient.connect(url);

        listener = (buffer, offset, length, streamInfo) ->
                onBuffer(buffer, offset, length, streamInfo);
        streamManager.addReceiveStreamListener(cameraIndex, listener);
        Log.i(TAG, "publishing camera " + cameraIndex + " to " + redact(url));
    }

    /**
     * Publish RTMP, encoding the frames ourselves. This is the working path.
     *
     * The alternatives were each ruled out by measurement, and the reasoning
     * lives in IkarosRtmpClient so it is next to the code that replaced them.
     * In short: RootEncoder's SRT held about 20 seconds of video on arrival,
     * its RTMP wrote one length prefix over a multi-NAL access unit, its fixed
     * release needs a Kotlin this project does not have, and ffmpeg-kit
     * collides with the ffmpeg the DJI SDK already bundles.
     */
    public void startEncodingRtmp(ComponentIndexType cameraIndex, String rtmpUrl,
                                  int bitrateBps, int fps, boolean adaptiveBitrate,
                                  IkarosRtmpPublisher.Listener l) {
        if (listener != null || frameListener != null) {
            Log.w(TAG, "already started");
            return;
        }
        camera = cameraIndex;
        sps = null;
        pps = null;
        videoInfoSent = false;
        lastPtsUs = Long.MIN_VALUE;
        skipUntilKeyframe = false;
        windowStartMs = System.currentTimeMillis();
        targetBitrateBps = bitrateBps;
        targetFps = fps;
        adaptive = adaptiveBitrate;
        bitrateCeilingBps = bitrateBps;
        bitrateNowBps = bitrateBps;
        clearSeconds = 0;

        rtmp = new IkarosRtmpPublisher(fps);
        rtmp.start(rtmpUrl, l);

        encoder = new IkarosFrameEncoder(new IkarosFrameEncoder.Sink() {
            @Override
            public void onConfig(byte[] s0, byte[] p0) {
                if (s0 == null || p0 == null || s0.length < 5 || p0.length < 5) {
                    Log.e(TAG, "ignoring malformed parameter sets");
                    return;
                }
                sps = s0;
                pps = p0;
                IkarosRtmpPublisher r = rtmp;
                if (r != null) r.setParameterSets(s0, p0);
                Log.i(TAG, "have parameter sets: sps=" + s0.length + "B pps=" + p0.length + "B");
            }

            @Override
            public void onEncoded(byte[] au, long ptsUs, boolean keyframe) {
                sendAuRtmp(au, keyframe);
            }
        });

        frameListener = (data, offset, length, width, height, format) -> {
            try {
                framesIn++;
                IkarosFrameEncoder e = encoder;
                if (e == null) return;
                e.encode(data, offset, length, width, height, format, targetBitrateBps, targetFps);
                droppedEncoderFull += e.takeDroppedNoInputBuffer();
                droppedToRate += e.takeDroppedToRate();
                IkarosRtmpPublisher r = rtmp;
                if (r != null) droppedLinkFull += r.takeDroppedQueueFull();
                report();
            } catch (Throwable t) {
                Log.e(TAG, "frame dropped on error", t);
            }
        };
        streamManager.addFrameListener(cameraIndex,
                ICameraStreamManager.FrameFormat.NV21, frameListener);
        Log.i(TAG, "publishing camera " + cameraIndex + " at " + bitrateBps + " bps, " + fps + " fps"
                + (!adaptiveBitrate
                   ? " (adaptation off by setting)"
                   : bitrateBps <= BITRATE_FLOOR_BPS
                     ? " (adaptation on, but inert: ceiling is at or below the "
                       + BITRATE_FLOOR_BPS + " bps floor)"
                     : " (adaptation on: floor " + BITRATE_FLOOR_BPS + " bps)"));
    }

    /**
     * Hold the ceiling, or fall back to the floor. Nothing in between.
     *
     * Pressure is read from the queue rather than from drops, so the rate
     * comes down BEFORE frames are lost - by the time droppedLinkFull is
     * counting, the operator has already seen the stutter.
     */
    private void govern(IkarosRtmpPublisher r) {
        if (!adaptive) return;
        if (bitrateCeilingBps <= BITRATE_FLOOR_BPS) return;   // nothing to give up

        boolean strained = droppedLinkFull > 0 || r.queuePressure() >= PRESSURE_DOWN;

        if (strained) {
            clearSeconds = 0;
            if (bitrateNowBps != BITRATE_FLOOR_BPS) {
                bitrateNowBps = BITRATE_FLOOR_BPS;
                IkarosFrameEncoder e = encoder;
                if (e != null) e.setBitrate(bitrateNowBps);
                Log.i(TAG, "link strained, bitrate to floor " + bitrateNowBps + " bps");
            }
            return;
        }

        if (bitrateNowBps >= bitrateCeilingBps) { clearSeconds = 0; return; }
        if (++clearSeconds < CLEAR_SECONDS_BEFORE_UP) return;
        clearSeconds = 0;
        bitrateNowBps = bitrateCeilingBps;
        IkarosFrameEncoder e = encoder;
        if (e != null) e.setBitrate(bitrateNowBps);
        Log.i(TAG, "link clear, bitrate back to ceiling " + bitrateNowBps + " bps");
    }

    /**
     * Weld the pose in and hand the access unit to the RTMP writer.
     *
     * Parameter sets are stripped: they travel once, in the AVC sequence
     * header, which is where AVCC expects them. The SEI goes before the first
     * slice, and IkarosRtmpClient gives every NAL its own length prefix - so
     * the pose and the picture arrive as one access unit, intact.
     */
    private void sendAuRtmp(byte[] au, boolean keyframe) {
        IkarosRtmpPublisher r = rtmp;
        if (r == null) return;

        Pose pose = poseSource.currentPose();
        if (pose == null) { droppedNoPose++; report(); return; }
        if (sps == null || pps == null) { droppedNoConfig++; report(); return; }

        byte[] klv = IkarosSei.encodeMisb0601(
                System.currentTimeMillis() * 1000L,
                pose.lat, pose.lon, pose.altM, pose.headingDeg,
                pose.gimbalYawDeg, pose.gimbalPitchDeg, pose.gimbalRollDeg);

        byte[] slices = IkarosSeiInjector.stripParameterSets(au, 0, au.length);
        byte[] withSei = IkarosSeiInjector.insertBeforeFirstSlice(
                slices, 0, slices.length, IkarosSei.buildSeiNal(klv));

        if (r.write(withSei, keyframe)) framesOut++;
        report();
    }

    /**
     * Connect and publish, encoding the frames ourselves at `bitrateBps`.
     *
     * The difference from start() is WHERE the H.264 comes from. start()
     * forwards the RC's own encode, whose bitrate the SDK will not let us
     * set on this hardware - setStreamEncoderBitrate answered the request
     * with readback -1, and the RC's encoder ran at 6.6 to 11 Mbps. Over a
     * weak uplink that does not drain: the server sat on a 12.9 MB receive
     * buffer at 5.6 SECONDS RTT with almost no packet loss, about 15s of
     * video late, which is the lag PARM and the operator saw.
     *
     * Here addFrameListener gives decoded frames and IkarosFrameEncoder
     * re-encodes them at a rate we choose. The pose injection is unchanged -
     * the SEI still goes in before the first slice of every access unit.
     */
    public void startEncoding(ComponentIndexType cameraIndex, String url, ConnectChecker checker,
                              int bitrateBps, int fps) {
        if (listener != null || frameListener != null) {
            Log.w(TAG, "already started");
            return;
        }
        camera = cameraIndex;
        sps = null;
        pps = null;
        videoInfoSent = false;
        firstPtsMs = Long.MIN_VALUE;
        lastPtsUs = Long.MIN_VALUE;
        skipUntilKeyframe = false;
        windowStartMs = System.currentTimeMillis();
        targetBitrateBps = bitrateBps;
        targetFps = fps;

        srtClient = new SrtClient(checker);
        srtClient.setOnlyVideo(true);
        srtClient.setVideoCodec(VideoCodec.H264);
        srtClient.resizeCache(CACHE_FRAMES);
        srtClient.connect(url);

        encoder = new IkarosFrameEncoder(new IkarosFrameEncoder.Sink() {
            @Override
            public void onConfig(byte[] s0, byte[] p0) {
                // Sanity-check before handing these to the packetiser. It
                // reads a start code off the front without checking the
                // length, so a short buffer is a FATAL exception on DJI's
                // dispatcher thread rather than an error we can recover from
                // - which is exactly how the first build of this path died.
                if (s0 == null || p0 == null || s0.length < 5 || p0.length < 5) {
                    Log.e(TAG, "ignoring malformed parameter sets: sps="
                            + (s0 == null ? -1 : s0.length) + "B pps="
                            + (p0 == null ? -1 : p0.length) + "B");
                    return;
                }
                sps = s0;
                pps = p0;
                SrtClient c = srtClient;
                if (c != null && !videoInfoSent) {
                    c.setVideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), null);
                    videoInfoSent = true;
                    Log.i(TAG, "sent video config: sps=" + sps.length + "B pps=" + pps.length + "B");
                }
            }

            @Override
            public void onEncoded(byte[] au, long ptsUs, boolean keyframe) {
                sendAu(au, 0, au.length, ptsUs, keyframe);
            }
        });

        // NV21 rather than YUV420_888: it is already semi-planar, so the
        // conversion to the encoder's NV12 is a chroma swap instead of an
        // interleave of two separate planes.
        // Anything thrown here lands on DJI's GLFrameDispatcher thread and
        // takes the whole app down with it, mid-flight. A failed frame is
        // worth a log line; it is not worth the aircraft losing its app.
        frameListener = (data, offset, length, width, height, format) -> {
            try {
                framesIn++;
                IkarosFrameEncoder e = encoder;
                if (e == null) return;
                e.encode(data, offset, length, width, height, format, targetBitrateBps, targetFps);
                droppedCongested += e.takeDroppedNoInputBuffer();
                report();
            } catch (Throwable t) {
                Log.e(TAG, "frame dropped on error", t);
            }
        };
        streamManager.addFrameListener(cameraIndex,
                ICameraStreamManager.FrameFormat.NV21, frameListener);
        Log.i(TAG, "publishing camera " + cameraIndex + " to " + redact(url)
                + " re-encoded at " + bitrateBps + " bps");
    }

    /** Change the publish bitrate mid-flight. Only meaningful while encoding. */
    public void setBitrate(int bps) {
        targetBitrateBps = bps;
        IkarosFrameEncoder e = encoder;
        if (e != null) e.setBitrate(bps);
    }

    public void stop() {
        if (frameListener != null) {
            streamManager.removeFrameListener(frameListener);
            frameListener = null;
        }
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        if (rtmp != null) {
            rtmp.stop();
            rtmp = null;
        }
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

        long ptsMs = (info == null) ? System.currentTimeMillis() : info.getPresentationTimeMs();
        if (firstPtsMs == Long.MIN_VALUE) firstPtsMs = ptsMs;

        // Monotonic, strictly. A repeated or reversed timestamp is nudged to
        // one microsecond past the last - which keeps presentation order
        // correct and is far below anything a decoder resolves, rather than
        // inventing a frame interval we do not know.
        long ptsUs = (ptsMs - firstPtsMs) * 1000L;
        if (lastPtsUs != Long.MIN_VALUE && ptsUs <= lastPtsUs) {
            backwardsPts++;
            ptsUs = lastPtsUs + 1;
        }
        lastPtsUs = ptsUs;

        // The KLV timestamp is the aircraft's capture time in epoch
        // microseconds - the whole point of using presentationTimeMs rather
        // than the controller's clock, which is late by the uplink latency.
        sendAu(b, offset, length, ptsUs, containsIdr(b, offset, length), ptsMs * 1000L);
    }

    /**
     * Weld the pose into one access unit and hand it to the SRT client.
     *
     * Shared by both paths: forwarding the RC's encode, and publishing our
     * own. They differ only in where the bytes and the timestamps come from -
     * the SEI, the parameter-set handling and the congestion rule are the
     * same work and belong in one place.
     */
    private void sendAu(byte[] b, int offset, int length, long ptsUs, boolean keyframe) {
        sendAu(b, offset, length, ptsUs, keyframe, System.currentTimeMillis() * 1000L);
    }

    private void sendAu(byte[] b, int offset, int length, long ptsUs, boolean keyframe,
                        long klvEpochUs) {
        SrtClient client = srtClient;
        if (client == null) return;

        Pose pose = poseSource.currentPose();
        if (pose == null) {
            // Publishing frames with no pose would produce video PARM cannot
            // geolocate, silently. Better to hold until the aircraft reports
            // one - that is seconds at startup, not a flight-long failure.
            droppedNoPose++;
            report();
            return;
        }

        byte[] klv = IkarosSei.encodeMisb0601(
                klvEpochUs,
                pose.lat, pose.lon, pose.altM, pose.headingDeg,
                pose.gimbalYawDeg, pose.gimbalPitchDeg, pose.gimbalRollDeg);
        // Parameter sets out first, THEN the pose in. Leaving SPS/PPS in the
        // access unit overflows a buffer inside the packetiser and kills the
        // sender on the first keyframe - see stripParameterSets.
        byte[] slices = IkarosSeiInjector.stripParameterSets(b, offset, length);
        byte[] au = IkarosSeiInjector.insertBeforeFirstSlice(
                slices, 0, slices.length, IkarosSei.buildSeiNal(klv));

        // CONGESTION: drop, do not queue.
        //
        // onReceiveStream hands us the camera's own ~6.4 Mbps encode, which
        // is roughly twice what LiveStreamManager was configured to push and
        // more than this uplink reliably carries. SRT answers a shortfall by
        // buffering, and the queue is bounded only by the heap - so it grew
        // until the link was 6 SECONDS behind (measured: msRTT 6019-6144 ms
        // at the server) and then until the app died:
        //
        //   OutOfMemoryError: Failed to allocate 101784 bytes with 18352
        //   free, growth limit 268435456
        //
        // The video was never going to arrive; queueing it only chose how
        // late it would be and when the process would run out of memory.
        // Dropping keeps latency bounded and the app alive, and the stream
        // degrades the way a congested video link is supposed to.
        if (skipUntilKeyframe && !keyframe) {
            droppedCongested++;
            report();
            return;
        }
        if (!keyframe && client.hasCongestion(CONGESTION_PCT)) {
            skipUntilKeyframe = true;
            droppedCongested++;
            report();
            return;
        }
        skipUntilKeyframe = false;

        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, au.length, ptsUs, keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
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
        // sent/dropped come from the CLIENT, and they are the numbers that
        // matter. "out" only counts frames handed to sendVideo, which
        // enqueues - so when the sender coroutine died on a buffer overflow,
        // this line read "30 in / 30 out, streaming=true" while the server
        // was receiving nothing whatsoever. A publish can fail in a way that
        // looks, from here, exactly like a publish that works.
        // On the RTMP path these SRT counters do not apply and read -1, which
        // is worse than useless in a log line - so say which transport is
        // actually running and report its numbers.
        IkarosRtmpPublisher r = rtmp;
        if (r != null) {
            govern(r);
            Log.i(TAG, framesIn + " in / " + framesOut + " published per s"
                    + "  droppedToRate=" + droppedToRate
                    + "  droppedEncoderFull=" + droppedEncoderFull
                    + "  droppedLinkFull=" + droppedLinkFull
                    + "  droppedNoPose=" + droppedNoPose
                    + "  droppedNoConfig=" + droppedNoConfig
                    + "  bitrate=" + (bitrateNowBps / 1000) + "k/" + (bitrateCeilingBps / 1000) + "k"
                    + "  queue=" + Math.round(r.queuePressure() * 100) + "%"
                    + "  connected=" + r.isRunning());
            windowStartMs = now;
            framesIn = 0; framesOut = 0;
            droppedToRate = 0; droppedEncoderFull = 0; droppedLinkFull = 0;
            droppedNoPose = 0; droppedNoConfig = 0; backwardsPts = 0; droppedCongested = 0;
            return;
        }

        SrtClient c = srtClient;
        long sent = c == null ? -1 : c.getSentVideoFrames();
        long dropped = c == null ? -1 : c.getDroppedVideoFrames();
        Log.i(TAG, framesIn + " in / " + framesOut + " queued per s"
                + "  sent=" + sent + " dropped=" + dropped
                + "  droppedNoPose=" + droppedNoPose
                + "  droppedNoConfig=" + droppedNoConfig
                + "  backwardsPts=" + backwardsPts
                + "  droppedCongested=" + droppedCongested
                + "  cache=" + (c == null ? -1 : c.getItemsInCache())
                + "  streaming=" + isStreaming());
        windowStartMs = now;
        framesIn = 0;
        framesOut = 0;
        droppedNoPose = 0;
        droppedNoConfig = 0;
        backwardsPts = 0;
        droppedCongested = 0;
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
