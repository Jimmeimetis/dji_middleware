package dji.v5.ux.sample.showcase.defaultlayout;

public class Point {
    private double lat;
    private double lon;
    private double alt;

    public Point(double lat, double lon, double alt) {
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
    }

    // Getters and Setters
    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getAlt() {
        return alt;
    }

    public void setAlt(double alt) {
        this.alt = alt;
    }

    @Override
    public String toString() {
        return "{\"lat\":" + lat + "," +
                "\"lon\":" + lon + "," +
                "\"alt\":" + alt + "}";
    }

}

