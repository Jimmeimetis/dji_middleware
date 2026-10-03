package dji.v5.ux.sample.showcase.defaultlayout;

import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Drives IkarosRtmpClient off the camera thread.
 *
 * RTMP writes to a socket, and a socket write blocks when the link is slow.
 * That must never happen on DJI's frame callback: it is the thread delivering
 * video, and blocking it stalls the decoder and eventually the app. So frames
 * cross to a writer thread through a bounded queue, and when the queue is full
 * they are dropped rather than waited on.
 *
 * Dropping is by GOP. A P-frame references the ones before it, so discarding
 * an arbitrary frame smears everything after it until the next IDR; skipping
 * to the keyframe costs the same video and resumes cleanly. This is also the
 * backpressure the old SRT path never had - it read a send cache that stayed
 * empty while the stream ran 20 seconds late, so it never shed anything.
 */
final class IkarosRtmpPublisher {

    private static final String TAG = "IkarosRtmpPub";

    /**
     * How late a frame may be before we stop waiting for the link, in ms.
     *
     * This is the latency budget, and it is the knob to turn when the link is
     * poor: raise it and a stall is absorbed as delay instead of as a dropped
     * GOP; lower it and the picture stays current at the cost of smoothness.
     *
     * 400 ms is deliberately modest. The queue used to be a flat 60 frames -
     * two whole seconds - which sounds generous but is the wrong shape: on a
     * link that cannot keep up it does not prevent drops, it just makes the
     * video two seconds old BEFORE dropping. A short budget plus the adaptive
     * bitrate in IkarosSeiPublisher is the better trade, because lowering the
     * rate keeps every frame rather than discarding a run of them.
     */
    static final int LATENCY_BUDGET_MS = 400;

    private final int queueFrames;

    interface Listener {
        void onStarted();
        void onFailed(String reason);
    }

    private static final class Frame {
        final byte[] data; final boolean key; final int tsMs;
        Frame(byte[] d, boolean k, int t) { data = d; key = k; tsMs = t; }
    }

    private final ArrayBlockingQueue<Frame> queue;
    private final IkarosRtmpClient client = new IkarosRtmpClient();

    IkarosRtmpPublisher(int fps) {
        // At least a few frames: a budget so small it cannot hold one GOP's
        // worth of jitter would shed constantly on a healthy link.
        queueFrames = Math.max(4, LATENCY_BUDGET_MS * Math.max(1, fps) / 1000);
        queue = new ArrayBlockingQueue<>(queueFrames);
        Log.i(TAG, "latency budget " + LATENCY_BUDGET_MS + " ms = " + queueFrames + " frames at " + fps + " fps");
    }

    private volatile boolean running;
    private volatile boolean configSent;
    private volatile boolean skipUntilKeyframe;
    private Thread writer;
    private Listener listener;
    private byte[] sps, pps;
    private long firstFrameMs = Long.MIN_VALUE;
    private int droppedQueueFull;

    boolean isRunning() { return running; }

    /** How full the link queue is, 0..1. The governor's pressure signal. */
    float queuePressure() {
        return queueFrames == 0 ? 0f : (float) queue.size() / (float) queueFrames;
    }

    int takeDroppedQueueFull() {
        int n = droppedQueueFull;
        droppedQueueFull = 0;
        return n;
    }

    void setParameterSets(byte[] s, byte[] p) {
        sps = s;
        pps = p;
    }

    void start(String rtmpUrl, Listener l) {
        if (running) return;
        listener = l;
        queue.clear();
        configSent = false;
        skipUntilKeyframe = false;
        firstFrameMs = Long.MIN_VALUE;
        running = true;

        writer = new Thread(() -> pump(rtmpUrl), "ikaros-rtmp-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void pump(String rtmpUrl) {
        try {
            client.connect(rtmpUrl, 8000);
            Listener l = listener;
            if (l != null) l.onStarted();

            while (running) {
                Frame f = queue.poll(500, TimeUnit.MILLISECONDS);
                if (f == null) continue;

                if (!configSent) {
                    byte[] s = sps, p = pps;
                    if (s == null || p == null) continue;   // nothing decodable yet
                    client.sendVideoConfig(s, p, 0);
                    configSent = true;
                }
                client.sendVideo(f.data, 0, f.data.length, f.key, f.tsMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "publish failed", e);
                running = false;
                Listener l = listener;
                if (l != null) l.onFailed(String.valueOf(e.getMessage()));
            }
        } finally {
            running = false;
            client.close();
            Log.i(TAG, "writer stopped");
        }
    }

    /** Queue one Annex-B access unit. Returns false if it was shed. */
    boolean write(byte[] au, boolean keyframe) {
        if (!running) return false;

        if (firstFrameMs == Long.MIN_VALUE) firstFrameMs = System.currentTimeMillis();
        int ts = (int) (System.currentTimeMillis() - firstFrameMs);

        if (skipUntilKeyframe) {
            if (!keyframe) { droppedQueueFull++; return false; }
            skipUntilKeyframe = false;
        }
        if (queue.offer(new Frame(au, keyframe, ts))) return true;

        skipUntilKeyframe = true;
        droppedQueueFull++;
        return false;
    }

    void stop() {
        running = false;
        if (writer != null) { writer.interrupt(); writer = null; }
        client.close();
        queue.clear();
        Log.i(TAG, "stopped");
    }
}
