/*******************************************************************************
 * Copyright (c) 2016 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package application;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Properties;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import com.ibm.iotf.client.device.DeviceClient;

import org.gameontext.util.reg.RegistrationUtility;
import org.gameontext.util.reg.RegistrationUtility.HTTP_METHOD;
import org.gameontext.iotboard.registration.DeviceRegistration;
import org.gameontext.iotboard.registration.DeviceRegistrationRequest;
import org.gameontext.iotboard.registration.DeviceRegistrationResponse;

/**
 * A very simple room.
 * 
 * All configuration for the room is contained / derived from the Config class.
 * This application has the following library dependencies (which are managed by jitpack.io).
 *
 * - com.github.gameontext:regutil
 *
 */
@ServerEndpoint("/room")
@WebListener
public class Application implements ServletContextListener {

    private final static String USERNAME = "username";
    private final static String USERID = "userId";
    private final static String BOOKMARK = "bookmark";
    private final static String CONTENT = "content";
    private final static String LOCATION = "location";
    private final static String TYPE = "type";
    private final static String NAME = "name";
    private final static String EXIT = "exit";
    private final static String EXIT_ID = "exitId";
    private final static String FULLNAME = "fullName";
    private final static String DESCRIPTION = "description";


    // IoT variables
    // Once you set up your room in the GameOn Edit Rooms panel,
    // you should be able to find these variables there.
    private static final String PLAYER_ID = "google:105084429722850913278";
    private static final String ROOM_NAME = "IoTRoom";
    private static final String SITE_ID = "c8771a19974afa9744495ad611af657f";
    private DeviceRegistrationResponse iotResponse = null;
    
    
    // ALl rooms should have a device type of GameOnRoom
    private static final String DEVICE_TYPE = "GameOnRoom";
    
    
    private Config config = new Config();
    
    private Set<String> playersInRoom = Collections.synchronizedSet(new HashSet<String>());

    List<String> directions = Arrays.asList( "n", "s", "e", "w", "u", "d");

    private static long bookmark = 0;

    private final Set<Session> sessions = new CopyOnWriteArraySet<Session>();

    private static volatile DeviceClient iotClient = null;
    private boolean light = false;

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room registration
    /////////////////////////////////////////////////////////////////////////////////////////////////////////////// 
    /**
     * Entry point at application start, we use this to test for & perform room registration.
     */
    @Override
    public final void contextInitialized(final ServletContextEvent e) {
        configureIoT();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Here we could deregister, if we wanted.. we'd need to read the registration/query
        // response to cache the room id, so we could remove it as we shut down.
    }

    private void configureIoT() {


        DeviceRegistrationRequest request = new DeviceRegistrationRequest();
        request.setPlayerId(PLAYER_ID);
        request.setRoomName(ROOM_NAME);
        request.setSiteId(SITE_ID);
        request.setDeviceType(DEVICE_TYPE);

        DeviceRegistration registration = new DeviceRegistration();
        iotResponse = registration.registerDevice(request);

	/**
	  * Load device properties
	  */
	Properties props = new Properties();
        props.setProperty("id",iotResponse.getDeviceId());
        props.setProperty("org", "jdy152");
        props.setProperty("type", DEVICE_TYPE);
        props.setProperty("auth-method", "token");
        props.setProperty("auth-token", iotResponse.getDeviceAuthToken());
		
	try {
           iotClient = new DeviceClient(props);
      	   iotClient.connect();
  	} catch (Exception e) {
  	   e.printStackTrace();
	   System.exit(-1);
        }
        System.out.println("Client connected:" + iotClient);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Websocket methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @OnOpen
    public void onOpen(Session session, EndpointConfig ec) {
        System.out.println("A new connection has been made to the room.");

        //send ack
        sendRemoteTextMessage(session, "ack,{\"version\":[1]}");
    }

    @OnClose
    public void onClose(Session session, CloseReason r) {
        System.out.println("A connection to the room has been closed");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        if(session!=null){
            sessions.remove(session);
        }
        System.out.println("Websocket connection has broken");
        t.printStackTrace();
    }

    @OnMessage
    public void receiveMessage(String message, Session session) throws IOException {
        String[] contents = splitRouting(message);

        // Who doesn't love switch on strings in Java 8?
        switch(contents[0]) {
            case "roomHello":
                sessions.add(session);
                addNewPlayer(session, contents[2]);
                break;
            case "room":
                processCommand(session, contents[2]);
                break;
            case "roomGoodbye":
                removePlayer(session, contents[2]);
                break;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // add a new player to the room
    private void addNewPlayer(Session session, String json) throws IOException {
        if (session.getUserProperties().get(USERNAME) != null) {
            return; // already seen this user before on this socket
        }
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));

        if (playersInRoom.add(userid)) {
            // broadcast that the user has entered the room
            sendMessageToRoom(session, "Player " + username + " has entered the room", "You have entered the room",
                    userid);

            // now send the room info
            // this is the required response to a roomHello event, which is the
            // only reason we are in this method.
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add(TYPE, LOCATION);
            response.add(NAME, config.getName());
            response.add(FULLNAME, config.getFullname());
            response.add(DESCRIPTION, config.getDescription());
            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
        }
    }

    // remove a player from the room.
    private void removePlayer(Session session, String json) throws IOException {
        sessions.remove(session);
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));
        playersInRoom.remove(userid);

        // broadcast that the user has left the room
        sendMessageToRoom(session, "Player " + username + " has left the room", null, userid);
    }

    // process a command
    private void processCommand(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = getValue(msg.get(USERID));
        String username = getValue(msg.get(USERNAME));
        String content = getValue(msg.get(CONTENT)).toString();
        String lowerContent = content.toLowerCase();

        System.out.println("Command received from the user, " + content);
        
        if (lowerContent.equals("/switch")) {
            // Indicate if the light is on or off
            light = !light;
            
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add(TYPE, LOCATION);
            response.add(NAME, config.getName());
            response.add(DESCRIPTION, "You switch the light " + light );

            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());


            System.out.println("Building IoT JSON Object");
            IoTPayload payload = new IoTPayload();
            payload.setPlayerId(PLAYER_ID);
            payload.setRoomName(ROOM_NAME);
            payload.setSiteId(SITE_ID);
            Data data = new Data();
            data.setLightAddress("pi1:1");
            data.setLightState(light);
            payload.setData(data);

            
            System.out.println("Sending IoT message; AppClient="+iotClient);
            
            iotClient.publishEvent("request", payload);
            System.out.println("Done processing switch");
            return; 
        }

        // handle look command
        if (lowerContent.equals("/look")) {
            // resend the room description when we receive /look
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add(TYPE, LOCATION);
            response.add(NAME, config.getName());
            response.add(DESCRIPTION, config.getDescription());

            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
            return;
        }

        if (lowerContent.startsWith("/go")) {

            String exitDirection = null;
            if (lowerContent.length() > 4) {
                exitDirection = lowerContent.substring(4).toLowerCase();
            }

            if ( exitDirection == null || !directions.contains(exitDirection) ) {
                sendMessageToRoom(session, null, "Hmm. That direction didn't make sense. Try again?", userid);
            } else {
                // Trying to go somewhere, eh?
                JsonObjectBuilder response = Json.createObjectBuilder();
                response.add(TYPE, EXIT)
                .add(EXIT_ID, exitDirection)
                .add(BOOKMARK, bookmark++)
                .add(CONTENT, "Run Away!");

                sendRemoteTextMessage(session, "playerLocation," + userid + "," + response.build().toString());
            }
            return;
        }

        // reject all unknown commands
        if (lowerContent.startsWith("/")) {
            sendMessageToRoom(session, null, "Unrecognised command - sorry :-(", userid);
            return;
        }

        // everything else is just chat.
        sendChatMessage(session, content, userid, username);
        return;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Reply methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void sendMessageToRoom(Session session, String messageForRoom, String messageForUser, String userid)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "event");

        JsonObjectBuilder content = Json.createObjectBuilder();
        if (messageForRoom != null) {
            content.add("*", messageForRoom);
        }
        if (messageForUser != null) {
            content.add(userid, messageForUser);
        }

        response.add(CONTENT, content.build());
        response.add(BOOKMARK, bookmark++);

        if(messageForRoom==null){
            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
        }else{
            broadcast(sessions, "player,*," + response.build().toString());
        }
    }

    private void sendChatMessage(Session session, String message, String userid, String username) throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "chat");
        response.add(USERNAME, username);
        response.add(CONTENT, message);
        response.add(BOOKMARK, bookmark++);
        broadcast(sessions, "player,*," + response.build().toString());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Util fns.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String[] splitRouting(String message) {
        ArrayList<String> list = new ArrayList<>();

        int brace = message.indexOf('{');
        int i = 0;
        int j = message.indexOf(',');
        while (j > 0 && j < brace) {
            list.add(message.substring(i, j));
            i = j + 1;
            j = message.indexOf(',', i);
        }
        list.add(message.substring(i));

        return list.toArray(new String[] {});
    }

    private static String getValue(JsonValue value) {
        if (value.getValueType().equals(ValueType.STRING)) {
            JsonString s = (JsonString) value;
            return s.getString();
        } else {
            return value.toString();
        }
    }

    /**
     * Simple text based broadcast.
     *
     * @param session
     *            Target session (used to find all related sessions)
     * @param message
     *            Message to send
     * @see #sendRemoteTextMessage(Session, RoutedMessage)
     */
    public void broadcast(Set<Session> sessions, String message) {
        for (Session s : sessions) {
            sendRemoteTextMessage(s, message);
        }
    }

    /**
     * Try sending the {@link RoutedMessage} using
     * {@link Session#getBasicRemote()}, {@link Basic#sendObject(Object)}.
     *
     * @param session
     *            Session to send the message on
     * @param message
     *            Message to send
     * @return true if send was successful, or false if it failed
     */
    public boolean sendRemoteTextMessage(Session session, String message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                return true;
            } catch (IOException ioe) {
                // An IOException, on the other hand, suggests the connection is
                // in a bad state.
                System.out.println("Unexpected condition writing message: " + ioe);
                tryToClose(session, new CloseReason(CloseCodes.UNEXPECTED_CONDITION, trimReason(ioe.toString())));
            }
        }
        return false;
    }

    /**
     * {@code CloseReason} can include a value, but the length of the text is
     * limited.
     *
     * @param message
     *            String to trim
     * @return a string no longer than 123 characters.
     */
    private static String trimReason(String message) {
        return message.length() > 123 ? message.substring(0, 123) : message;
    }

    /**
     * Try to close the WebSocket session and give a reason for doing so.
     *
     * @param s
     *            Session to close
     * @param reason
     *            {@link CloseReason} the WebSocket is closing.
     */
    public void tryToClose(Session s, CloseReason reason) {
        try {
            s.close(reason);
        } catch (IOException e) {
            tryToClose(s);
        }
    }

    /**
     * Try to close a {@code Closeable} (usually once an error has already
     * occurred).
     *
     * @param c
     *            Closable to close
     */
    public void tryToClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e1) {
            }
        }
    }

}
