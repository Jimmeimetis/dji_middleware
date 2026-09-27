package dji.v5.ux.sample.showcase.defaultlayout;

public class Vehicle {
    private String name;
    private int typeId;
    private int groupId;
    private String id;
    private String fbDevtoken;

    public Vehicle(String id,String name, int typeId, int groupId, String fbDevtoken) {
        this.id = id;
        this.name = name;
        this.typeId = typeId;
        this.groupId = groupId;
        this.fbDevtoken = fbDevtoken;
    }

    public Vehicle() {
        this.id = "";
        this.name = "";
        this.typeId = 0;
        this.groupId = 0;
        this.fbDevtoken = "";
    }

    public void setAllValues(String id, String name, int typeId, int groupId, String fbDevtoken) {
        this.id = id;
        this.name = name;
        this.typeId = typeId;
        this.groupId = groupId;
        this.fbDevtoken = fbDevtoken;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getTypeId() {
        return typeId;
    }
    public int getGroupId() {
        return groupId;
    }


    public void setId(String id) {
        this.id = id;
    }

    public void setTypeId(int typeId) {
        this.typeId = typeId;
    }



    public void setGroupId(int groupId) {
        this.groupId = groupId;
    }

    public String getFbDevtoken() {
        return fbDevtoken;
    }

    public void setFbDevtoken(String fbDevtoken) {
        this.fbDevtoken = fbDevtoken;
    }
}
