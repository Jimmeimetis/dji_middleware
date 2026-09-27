package dji.v5.ux.sample.showcase.defaultlayout;

/**
 * Where the camera was pointing, and through what lens, for one telemetry
 * frame.
 *
 * Ikaros geolocates a detection by casting a ray from the aircraft through the
 * pixel the box sits on, so it needs the gimbal's attitude and the lens's
 * field of view for THAT frame. Without them the server assumes the camera
 * looks straight down and every position is wrong in a way nothing flags.
 *
 * Both blocks are optional on the wire (Gson omits null fields, and the
 * server's schema accepts their absence), so a build that cannot read one of
 * them still posts position and battery rather than losing the whole frame.
 */
public class CameraPose {

    /**
     * Gimbal attitude in degrees, as Ikaros defines it:
     *   yaw   — absolute azimuth, true north 0, clockwise. NOT the raw value
     *           from KeyGimbalAttitude: that yaw is in the gimbal's own frame
     *           and has to be corrected by KeyImuCoordinateTran before it
     *           means anything absolute. See DefaultLayoutActivity.
     *   pitch — 0 is the horizon, -90 straight down.
     *   roll  — about the optical axis.
     */
    public static class Gimbal {
        private final double yaw;
        private final double pitch;
        private final double roll;

        public Gimbal(double yaw, double pitch, double roll) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
        }

        public double getYaw() { return yaw; }
        public double getPitch() { return pitch; }
        public double getRoll() { return roll; }
    }

    /**
     * The lens, as a 35 mm-equivalent focal length.
     *
     * Sent as focal_mm rather than as a zoom ratio because that is what the
     * server's calibration is expressed against: the camera profile records
     * ref_focal_mm (24 mm for the Mavic wide camera), and PARM recovers the
     * zoom as focal_mm / ref_focal_mm to scale the calibrated intrinsics. So
     * focal_mm = wide focal x the current zoom ratio, and a profile change on
     * the server needs no change here.
     *
     * fov_h_deg / fov_v_deg are left null: the server prefers the calibrated
     * matrix scaled by focal length, and a field of view guessed on this side
     * would override it with something worse. The schema rejects 0 and >= 180,
     * so they must be absent rather than zero.
     */
    public static class Lens {
        private final double focal_mm;

        public Lens(double focalMm) {
            this.focal_mm = focalMm;
        }

        public double getFocalMm() { return focal_mm; }
    }

    private CameraPose() { }
}
