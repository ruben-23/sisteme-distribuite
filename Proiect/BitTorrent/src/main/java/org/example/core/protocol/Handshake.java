package org.example.core.protocol;

import org.example.util.Hash;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * BitTorrent handshake message
 * Format: <pstrlen><pstr><reserved><info_hash><peer_id>
 * - pstrlen: 1 byte (19)
 * - pstr: 19 bytes ("BitTorrent protocol")
 * - reserved: 8 bytes (all zeros)
 * - info_hash: 20 bytes
 * - peer_id: 20 bytes
 */
public class Handshake {
    private static final String PROTOCOL = "BitTorrent protocol";
    private static final int HANDSHAKE_LENGTH = 68;

    private byte[] infoHash;
    private byte[] peerId;

    public Handshake(byte[] infoHash, byte[] peerId) {
        if (infoHash.length != 20) throw new IllegalArgumentException("Info hash must be 20 bytes");
        if (peerId.length != 20) throw new IllegalArgumentException("Peer ID must be 20 bytes");

        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    /**
     * Serialize handshake to bytes
     */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // Protocol length
            out.write(PROTOCOL.length());
            // Protocol string
            out.write(PROTOCOL.getBytes(StandardCharsets.UTF_8));
            // Reserved bytes
            out.write(new byte[8]);
            // Info hash
            out.write(infoHash);
            // Peer ID
            out.write(peerId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /**
     * Parse handshake from input stream
     */
    public static Handshake parse(InputStream in) throws IOException {

        // Read protocol length
        int pstrlen = in.read();

//        System.out.println("Protocol length: " + pstrlen);
        if (pstrlen != PROTOCOL.length()) {
            throw new IOException("Invalid protocol length: " + pstrlen);
        }

        // Read protocol string
        byte[] pstr = new byte[pstrlen];
        in.read(pstr);
//        System.out.println("Protocol string: " + new String(pstr, StandardCharsets.UTF_8));
        String protocol = new String(pstr, StandardCharsets.UTF_8);
        if (!protocol.equals(PROTOCOL)) {
            throw new IOException("Invalid protocol: " + protocol);
        }

        // Skip reserved bytes
        in.skip(8);

        // Read info hash
        byte[] infoHash = new byte[20];
        in.read(infoHash);
//        System.out.println("Info hash: " + Hash.toHex(infoHash));


        // Read peer ID
        byte[] peerId = new byte[20];
        in.read(peerId);

//        System.out.println("Peer id: " + Hash.toHex(peerId));
        return new Handshake(infoHash, peerId);
    }

    public byte[] getInfoHash() { return infoHash; }
    public byte[] getPeerId() { return peerId; }

    public boolean matchesInfoHash(byte[] expectedInfoHash) {
        return Arrays.equals(infoHash, expectedInfoHash);
    }
}