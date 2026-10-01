package dji.v5.ux.sample.showcase.defaultlayout;

import java.io.ByteArrayOutputStream;

/**
 * Puts an SEI NAL into an access unit, in the one place it is allowed.
 *
 * H.264 orders an access unit: delimiter, parameter sets, SEI, then the
 * coded slices. SEI must precede the primary coded picture - put it after
 * the slices and a conforming decoder is entitled to ignore it or fail,
 * and intermediaries that re-packetise (MediaMTX does) may drop it.
 *
 * So the insertion point is immediately before the first VCL NAL (type 1
 * or 5), which keeps any SPS/PPS/AUD ahead of it where they belong, and
 * keeps whatever SEI the aircraft already sent - a DJI emits its own
 * per-frame timestamp as type 101, and ours sits alongside rather than
 * replacing it.
 *
 * This is deliberately separate from the publishing: it is pure byte
 * manipulation over a buffer, so it can be tested against a real DJI
 * bitstream without an aircraft, which is where the risk actually is.
 */
public final class IkarosSeiInjector {

    private IkarosSeiInjector() {}

    /** NAL types that carry coded picture data; SEI must come before these. */
    private static boolean isVcl(int nalType) {
        return nalType >= 1 && nalType <= 5;
    }

    /**
     * `accessUnit` with `seiNal` inserted before its first coded slice.
     *
     * Returns the input unchanged if it holds no slice - a parameter-set-only
     * buffer is not a picture, and prepending there would place the SEI
     * against the wrong frame.
     *
     * Both start-code lengths are handled: the aircraft's stream mixes
     * 3- and 4-byte codes, and assuming one of them silently misplaces the
     * insertion point.
     */
    public static byte[] insertBeforeFirstSlice(byte[] accessUnit, int offset, int length,
                                                byte[] seiNal) {
        int at = firstVclStart(accessUnit, offset, length);
        if (at < 0) {
            byte[] copy = new byte[length];
            System.arraycopy(accessUnit, offset, copy, 0, length);
            return copy;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + seiNal.length);
        out.write(accessUnit, offset, at - offset);
        out.write(seiNal, 0, seiNal.length);
        out.write(accessUnit, at, length - (at - offset));
        return out.toByteArray();
    }

    /**
     * `accessUnit` with its parameter sets and delimiter removed.
     *
     * The publisher hands SPS and PPS to the SRT client once, through
     * setVideoInfo, and the MPEG-TS packetiser re-emits them ahead of every
     * keyframe itself. Leaving them in the access unit as well is not merely
     * redundant - it crashes the sender.
     *
     * RootEncoder strips a leading SPS/PPS it recognises with position() +
     * slice(), then reads the result back with a helper that is
     *
     *     if (hasArray() && !isDirect) array() else copy of remaining()
     *
     * and array() on a slice returns the WHOLE backing array, arrayOffset
     * and the slice's smaller capacity both ignored. So it sizes the output
     * buffer for the trimmed access unit and then writes the untrimmed one
     * into it: BufferOverflowException, inside the sender's coroutine, on
     * the first keyframe. The publish dies silently - the client still
     * reports isStreaming() and frames still enqueue, while the server
     * receives nothing at all.
     *
     * Feeding only slices is also simply how the library expects to be fed:
     * MediaCodec delivers its parameter sets in a separate codec-config
     * buffer, so a frame buffer it packetises never contains them.
     *
     * The access-unit delimiter goes too - the packetiser writes its own,
     * and two in one access unit is not conformant.
     */
    public static byte[] stripParameterSets(byte[] accessUnit, int offset, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int end = offset + length;
        int i = nextStartCode(accessUnit, offset, end);
        while (i >= 0) {
            int hdr = headerAt(accessUnit, i, end);
            if (hdr < 0) break;
            int next = nextStartCode(accessUnit, hdr, end);
            int stop = next < 0 ? end : next;
            int type = accessUnit[hdr] & 0x1F;
            // 7 SPS, 8 PPS, 9 access unit delimiter.
            if (type != 7 && type != 8 && type != 9) {
                out.write(accessUnit, i, stop - i);
            }
            i = next;
        }
        return out.toByteArray();
    }

    /** Offset of the next start code at or after `from`, or -1. */
    private static int nextStartCode(byte[] b, int from, int end) {
        for (int i = from; i + 3 < end; i++) {
            if (b[i] != 0 || b[i + 1] != 0) continue;
            if (b[i + 2] == 1 || (b[i + 2] == 0 && b[i + 3] == 1)) return i;
        }
        return -1;
    }

    /** Offset of the NAL header following the start code at `i`, or -1. */
    private static int headerAt(byte[] b, int i, int end) {
        int hdr = (b[i + 2] == 1) ? i + 3 : i + 4;
        return hdr < end ? hdr : -1;
    }

    /** Offset of the start code introducing the first VCL NAL, or -1. */
    static int firstVclStart(byte[] b, int offset, int length) {
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
            if (hdr >= end) return -1;
            if (isVcl(b[hdr] & 0x1F)) return i;
        }
        return -1;
    }
}
