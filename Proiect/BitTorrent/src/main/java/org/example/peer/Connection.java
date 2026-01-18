package org.example.peer;

import org.example.core.protocol.*;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Manages connection to a single peer
 */
public class Connection {
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean choked = true;
    private volatile boolean interested = false;
    private volatile boolean peerChoked = true;
    private volatile boolean peerInterested = false;

    public Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /**
     * Send handshake
     */
    public void sendHandshake(Handshake handshake) throws IOException {
        out.write(handshake.toBytes());
        out.flush();
    }

    /**
     * Receive handshake
     */
    public Handshake receiveHandshake() throws IOException {
        return Handshake.parse(in);
    }

    /**
     * Send message with length prefix
     */
    public synchronized void sendMessage(byte messageId, byte[] payload) throws IOException {
        int length = 1 + (payload != null ? payload.length : 0);

        out.writeInt(length);
        out.writeByte(messageId);
        if (payload != null) {
            out.write(payload);
        }
        out.flush();
    }

    public String getRemoteAddress() {
        if (socket != null && socket.getRemoteSocketAddress() != null) {
            return socket.getRemoteSocketAddress().toString();
        }
        return "Unknown";
    }

    /**
     * Receive message
     */
    public Message receiveMessage() throws IOException {
        int length = in.readInt();

        if (length == 0) {
            return new Message((byte)-1, null); // keep-alive
        }

        byte messageId = in.readByte();
        byte[] payload = null;

        if (length > 1) {
            payload = new byte[length - 1];
            in.readFully(payload);
        }

        return new Message(messageId, payload);
    }

    /**
     * Send bitfield
     */
    public void sendBitfield(Bitfield bitfield) throws IOException {
        sendMessage(Bitfield.MESSAGE_ID, bitfield.toBytes());
    }

    /**
     * Send interested
     */
    public void sendInterested() throws IOException {
        sendMessage((byte)2, null);
        interested = true;
    }

    /**
     * Send unchoke
     */
    public void sendUnchoke() throws IOException {
        sendMessage((byte)1, null);
        choked = false;
    }

    /**
     * Send request
     */
    public void sendRequest(Request request) throws IOException {
        sendMessage(Request.MESSAGE_ID, request.toBytes());
    }

    /**
     * Send piece
     */
    public void sendPiece(Piece piece) throws IOException {
        sendMessage(Piece.MESSAGE_ID, piece.toBytes());
    }

    public boolean isChoked() { return choked; }
    public boolean isPeerChoked() { return peerChoked; }
    public void setPeerChoked(boolean choked) { this.peerChoked = choked; }
    public void setPeerInterested(boolean interested) { this.peerInterested = interested; }

    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Message wrapper
     */
    public static class Message {
        public byte id;
        public byte[] payload;

        public Message(byte id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }
}
