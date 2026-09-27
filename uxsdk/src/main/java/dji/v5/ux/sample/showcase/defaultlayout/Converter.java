package dji.v5.ux.sample.showcase.defaultlayout;

public class Converter {


    private boolean initialised = false;
    private int utmZoneNumber;
    private boolean northHemisphere;
    private boolean westHemisphere;
    private double cartesianPointRefX;
    private double cartesianPointRefY;

    public Converter(double originLat, double originLon) {
        setHemisphereAndUTMZone(originLat, originLon);
    }

    private void setHemisphereAndUTMZone(double lat, double lon) {
        northHemisphere = lat > 0;
        westHemisphere = lon <= 0;

        if (lon < 0) {
            utmZoneNumber = (int)((180 - lon) / 6) + 1;
        } else {
            utmZoneNumber = 31 + (int)(lon / 6);
        }

        initialised = true;
        double[] ref = convertGeodeticToUTM(lat, lon);
        cartesianPointRefX = ref[0];
        cartesianPointRefY = ref[1];
    }

    public double[] convertUTMToGeodetic(double north, double east) {

        final double fe = 500000;
        final double k0 = 0.9996;

        double fn = northHemisphere ? 0 : 10000000;

        double axialMeridianLon;
        if (westHemisphere) {
            axialMeridianLon = (30 - utmZoneNumber) * 6 + 3;
        } else {
            axialMeridianLon = (utmZoneNumber - 31) * 6 + 3;
        }

        double alpha = 6378206.4;
        double e2 = 0.006768657997;
        double ee2 = 0.006814784946;
        double c = 6399902.55159;

        double xx = (east - fe) / k0;
        double yy = (north - fn) / k0;

        double a0 = 1 + 3 * e2 / 4 + 45 * e2 * e2 / 64
                + 175 * Math.pow(e2, 3) / 256
                + 11025 * Math.pow(e2, 4) / 16384
                + 43659 * Math.pow(e2, 5) / 65536
                + 693693 * Math.pow(e2, 6) / 1048576;

        double lat1 = yy / (a0 * c);
        double mm;

        int cnt = 0;
        do {
            mm = 6335034.50224227 * 1.005108920388050 * lat1
                    - 6335034.50224227 * Math.cos(lat1) *
                    (0.005108920388050 * Math.sin(lat1)
                            + 0.000021617926721 * Math.pow(Math.sin(lat1), 3)
                            + 0.000000113817221 * Math.pow(Math.sin(lat1), 5)
                            + 0.000000000650041 * Math.pow(Math.sin(lat1), 7)
                            + 0.000000000003872 * Math.pow(Math.sin(lat1), 9)
                            + 0.000000000000024 * Math.pow(Math.sin(lat1), 11));

            lat1 += (yy - mm) / (a0 * c);

            if (++cnt > 50) break;

        } while (Math.abs(yy - mm) > 1e-12);

        double v1 = alpha / Math.sqrt(1 - e2 * Math.sin(lat1) * Math.sin(lat1));
        double t1 = Math.tan(lat1);
        double n12 = ee2 * Math.cos(lat1) * Math.cos(lat1);

        double beta = xx / v1;
        double gamma = t1 * (1 + n12);

        double lat = lat1 + (-gamma / 2) * beta * beta;

        double dl = (beta - (1 + 2 * t1 * t1 + n12) * Math.pow(beta, 3) / 6) / Math.cos(lat1);

        lat = Math.toDegrees(lat);
        dl = Math.toDegrees(dl);

        double lon = westHemisphere ? axialMeridianLon - dl : axialMeridianLon + dl;

        return new double[]{lat, lon};
    }

    public double[] convertUTMToCartesian(double north, double east) {
        double x = north - cartesianPointRefX;
        double y = -east + cartesianPointRefY;
        return new double[]{x, y};
    }

    public double[] convertCartesianToUTM(double x, double y) {
        double north = x + cartesianPointRefX;
        double east = -y + cartesianPointRefY;
        return new double[]{north, east};
    }

    public double[] convertGeodeticToUTM(double lat, double lon) {

        final double fe = 500000;
        final double k0 = 0.9996;

        double fn = lat > 0 ? 0 : 10000000;

        int zoneNumber = (lon < 0)
                ? (int)((180 - lon) / 6) + 1
                : 31 + (int)(lon / 6);

        double axialMeridianLon = (lon < 0)
                ? (30 - zoneNumber) * 6 + 3
                : (zoneNumber - 31) * 6 + 3;

        double dl = (lon < 0)
                ? Math.toRadians(lon - axialMeridianLon)
                : Math.toRadians(axialMeridianLon - lon);

        double latRad = Math.toRadians(lat);

        double alpha = 6378206.4;
        double e2 = 0.006768657997;
        double ee2 = 0.006814784946;

        double mm = 6335034.50224227 * 1.005108920388050 * latRad
                - 6335034.50224227 * Math.cos(latRad) *
                (0.005108920388050 * Math.sin(latRad)
                        + 0.000021617926721 * Math.pow(Math.sin(latRad), 3)
                        + 0.000000113817221 * Math.pow(Math.sin(latRad), 5)
                        + 0.000000000650041 * Math.pow(Math.sin(latRad), 7));

        double v = alpha / Math.sqrt(1 - e2 * Math.sin(latRad) * Math.sin(latRad));
        double t = Math.tan(latRad);
        double n2 = ee2 * Math.cos(latRad) * Math.cos(latRad);

        double aa = v * Math.cos(latRad);
        double bb = v * Math.pow(Math.cos(latRad), 3) * (1 - t * t + n2) / 6;

        double xx = aa * dl + bb * Math.pow(dl, 3);

        double ff = v * Math.sin(latRad) * Math.cos(latRad) / 2;
        double yy = mm + ff * dl * dl;

        double east = fe - k0 * xx;
        double north = fn + k0 * yy;

        return new double[]{north, east};
    }

    public double[] geodeticToCartesian(double lat, double lon) {
        double[] utm = convertGeodeticToUTM(lat, lon);
        return convertUTMToCartesian(utm[0], utm[1]);
    }

    public double[] cartesianToGeodetic(double x, double y) {
        double[] utm = convertCartesianToUTM(x, y);
        return convertUTMToGeodetic(utm[0], utm[1]);
    }

}
