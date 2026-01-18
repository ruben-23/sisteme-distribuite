package org.example.dht;

import org.example.util.Bencode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DHT messages for peer discovery
 * Types:
 * - PING: check if node is alive
 * - FIND_NODE: find nodes close to target
 * - ANNOUNCE_PEER: announce we have a torrent
 * - GET_PEERS: find peers for a torrent
 */
public class Message {
    public enum Type {
        PING, FIND_NODE, ANNOUNCE_PEER, GET_PEERS, RESPONSE
    }

    private Type type;
    private String transactionId;
    private Map<String, Object> data;

    public Message(Type type, String transactionId) {
        this.type = type;
        this.transactionId = transactionId;
        this.data = new HashMap<>();
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public Object get(String key) {
        return data.get(key);
    }

    /**
     * Serialize to bencode
     */
    public byte[] toBytes() throws IOException{
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("t", transactionId);
        if (type == Type.RESPONSE) {
            message.put("y", "r");
            message.put("r", data);
        } else {
            message.put("y", "q");
            message.put("q", type.name().toLowerCase());
            message.put("a", data);
        }    return Bencode.encode(message);
    }/**
     * Parse from bencode
     */
//    public static Message parse(byte[] bytes) throws IOException {
//        Map<String, Object> map = (Map<String, Object>)Bencode.decode(bytes);
//
//        String tid = new String((byte[])map.get("t"));
//        String messageType = new String((byte[])map.get("y"));
//
//        Message msg;
//        if (messageType.equals("r")) {
//            msg = new Message(Type.RESPONSE, tid);
//            msg.data = (Map<String, Object>)map.get("r");
//        } else {
//            String queryType = new String((byte[])map.get("q"));
//            msg = new Message(Type.valueOf(queryType.toUpperCase()), tid);
//            msg.data = (Map<String, Object>)map.get("a");
//        }    return msg;
//    }
    public static Message parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty message");
        }

        // ADD THIS DEFENSIVE CHECK
        if (bytes[0] != 'd') {
            String preview = new String(bytes, 0, Math.min(bytes.length, 50), StandardCharsets.ISO_8859_1)
                    .replace("\r", "\\r").replace("\n", "\\n");
            throw new IOException("Received invalid/non-bencoded DHT message (starts with 0x" +
                    String.format("%02X", bytes[0] & 0xFF) + "): '" + preview + "'");
        }

        Map<String, Object> map;
        try {
            map = (Map<String, Object>) Bencode.decode(bytes);
        } catch (Exception e) {
            String preview = new String(bytes, 0, Math.min(bytes.length, 100), StandardCharsets.ISO_8859_1);
            throw new IOException("Failed to decode DHT message: " + e.getMessage() +
                    " | Raw data: '" + preview + "'", e);
        }

        // Rest unchanged...
        String tid = new String((byte[])map.get("t"));
        String messageType = new String((byte[])map.get("y"));

        Message msg;
        if ("r".equals(messageType)) {
            msg = new Message(Type.RESPONSE, tid);
            Map<String, Object> r = (Map<String, Object>) map.get("r");
            msg.data = (r != null) ? r : new HashMap<>();
        } else if ("q".equals(messageType)) {
            String queryType = new String((byte[])map.get("q"));
            msg = new Message(Type.valueOf(queryType.toUpperCase()), tid);
            Map<String, Object> a = (Map<String, Object>) map.get("a");
            msg.data = (a != null) ? a : new HashMap<>();
        } else {
            throw new IOException("Invalid message type: " + messageType);
        }

        return msg;
    }

    public Type getType() { return type; }

    public String getTransactionId() { return transactionId; }
}