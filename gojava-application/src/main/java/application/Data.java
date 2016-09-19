package application;

public class Data {
    
    @Override
    public String toString() {
        return "Data [lightId=" + lightId + ", lightAddress=" + lightAddress + ", lightState=" + lightState + "]";
    }

    private String lightId;
    private String lightAddress;
    private boolean lightState;

    public boolean getLightState() {
        return lightState;
    }

    public void setLightState(boolean lightState) {
        this.lightState = lightState;
    }

    public String getLightId() {
        return lightId;
    }

    public void setLightId(String lightId) {
        this.lightId = lightId;
    }

    public String getLightAddress() {
        return lightAddress;
    }

    public void setLightAddress(String lightAddress) {
        this.lightAddress = lightAddress;
    }

}
