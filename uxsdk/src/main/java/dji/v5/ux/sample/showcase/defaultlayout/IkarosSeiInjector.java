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
