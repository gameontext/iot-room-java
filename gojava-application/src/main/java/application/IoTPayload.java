package application;

public class IoTPayload {

    private Data data;
    private String playerId;
    private String siteId;
    private String roomName;
    
    @Override
    public String toString() {
        return "IoTPayload [data=" + data + ", playerId=" + playerId + ", siteId=" + siteId + ", roomName=" + roomName
                + "]";
    }
    public Data getData() {
        return data;
    }
    public void setData(Data data) {
        this.data = data;
    }
    public String getPlayerId() {
        return playerId;
    }
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }
    public String getSiteId() {
        return siteId;
    }
    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }
    public String getRoomName() {
        return roomName;
    }
    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }
    

}
