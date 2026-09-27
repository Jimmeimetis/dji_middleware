package dji.v5.ux.sample.showcase.defaultlayout;





public class VehicleData {

    private String vehicleId;
    private String timestamp;
    private String missionId;
    private String fbDevtoken;
    private int battery;
    private int nextPoint;
    private double heading;
    private double speed;
    private Point point;

    public VehicleData(String vehicleId, String timestamp, String missionId, String fbDevtoken, int battery, int nextPoint, double heading, double speed, Point point) {
        this.vehicleId = vehicleId;
        this.timestamp = timestamp;
        this.missionId = missionId;
        this.fbDevtoken = fbDevtoken;
        this.battery = battery;
        this.nextPoint = nextPoint;
        this.heading = heading;
        this.speed = speed;
        this.point = point;
    }

    // Getters and Setters
    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getMissionId() {
        return missionId;
    }

    public void setMissionId(String missionId) {
        this.missionId = missionId;
    }

    public String getFbDevtoken() {
        return fbDevtoken;
    }

    public void setFbDevtoken(String fbDevtoken) {
        this.fbDevtoken = fbDevtoken;
    }

    public int getBattery() {
        return battery;
    }

    public void setBattery(int battery) {
        this.battery = battery;
    }

    public int getNextPoint() {
        return nextPoint;
    }

    public void setNextPoint(int nextPoint) {
        this.nextPoint = nextPoint;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public Point getPoint() {
        return point;
    }

    public void setPoint(Point point) {
        this.point = point;
    }

    @Override
    public String toString() {
        return "{\"vehicleId\":\"" + vehicleId + "\"," +
                "\"timestamp\":\"" + timestamp + "\"," +
                "\"missionId\":\"" + missionId + "\"," +
                "\"fbDevtoken\":\"" + fbDevtoken + "\"," +
                "\"battery\":" + battery + "," +
                "\"nextPoint\":" + nextPoint + "," +
                "\"heading\":" + heading + "," +
                "\"speed\":\"" + speed + "\"," +
                "\"point\":" + point.toString() + "}";
    }
}
