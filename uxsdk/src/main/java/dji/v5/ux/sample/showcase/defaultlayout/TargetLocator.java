package dji.v5.ux.sample.showcase.defaultlayout;

public class TargetLocator {

    /**
     * Computes target location in geodetic coordinates assuming a flat plane.
     *
     * @param droneLat       Drone latitude (deg)
     * @param droneLon       Drone longitude (deg)
     * @param droneAlt       Drone altitude MSL (meters)
     * @param cameraRollDeg  Camera roll (deg)
     * @param cameraPitchDeg Camera pitch (deg)
     * @param cameraYawDeg   Camera yaw (deg), 0 = north, CCW positive
     * @return double[]{ targetLat, targetLon, distanceToTarget }
     */
    public static double[] computeTargetGeodeticCoords(
            double droneLat,
            double droneLon,
            double droneAlt,
            double cameraRollDeg,
            double cameraPitchDeg,
            double cameraYawDeg
    ) {

        // Convert degrees → radians AND apply negative sign (same as Python)
        double yaw = -cameraYawDeg * Math.PI / 180.0;
        double roll = -cameraRollDeg * Math.PI / 180.0;
        double pitch = -cameraPitchDeg * Math.PI / 180.0;

        // Rotation matrices Rz * Ry * Rx
        double[][] Rz = {
                {Math.cos(yaw), -Math.sin(yaw), 0},
                {Math.sin(yaw), Math.cos(yaw), 0},
                {0, 0, 1}
        };

        double[][] Ry = {
                {Math.cos(pitch), 0, Math.sin(pitch)},
                {0, 1, 0},
                {-Math.sin(pitch), 0, Math.cos(pitch)}
        };

        double[][] Rx = {
                {1, 0, 0},
                {0, Math.cos(roll), -Math.sin(roll)},
                {0, Math.sin(roll), Math.cos(roll)}
        };

        double[][] Rzy = multiplyMatrices(Rz, Ry);
        double[][] R = multiplyMatrices(Rzy, Rx); // ZYX rotation

        // Multiply rotation matrix by vector (1,0,0)
        double[] v3 = {
                R[0][0],
                R[1][0],
                R[2][0]
        };

        // Normalize v3
        double mag = Math.sqrt(v3[0] * v3[0] + v3[1] * v3[1] + v3[2] * v3[2]);
        v3[0] /= mag;
        v3[1] /= mag;
        v3[2] /= mag;

        // Intersect vector with ground plane z=0
        double distToTarget = -droneAlt / v3[2];

        double xt = distToTarget * v3[0];
        double yt = distToTarget * v3[1];

        // Convert to geodetic
        Converter conv = new Converter(droneLat, droneLon);
        double[] geodetic = conv.cartesianToGeodetic(xt, yt);

        return new double[]{geodetic[0], geodetic[1], distToTarget};
    }


    // ======== Helper: Matrix Multiplication ========
    private static double[][] multiplyMatrices(double[][] A, double[][] B) {
        double[][] result = new double[A.length][B[0].length];

        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < B[0].length; j++) {
                result[i][j] = 0;

                for (int k = 0; k < B.length; k++) {
                    result[i][j] += A[i][k] * B[k][j];
                }
            }
        }

        return result;
    }
}
