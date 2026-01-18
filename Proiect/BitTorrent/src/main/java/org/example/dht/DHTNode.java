package org.example.dht;

import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * Represents a node in the DHT
 */
public class DHTNode {
    private byte[] nodeId; // 20-byte ID
    private InetSocketAddress address;
    private long lastSeen;

    public DHTNode(byte[] nodeId, InetSocketAddress address) {
        this.nodeId = nodeId;
        this.address = address;
        this.lastSeen = System.currentTimeMillis();
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * Calculate XOR distance between node IDs
     */
    public byte[] distanceTo(byte[] targetId) {
        byte[] distance = new byte[20];
        for (int i = 0; i < 20; i++) {
            distance[i] = (byte)(nodeId[i] ^ targetId[i]);
        }
        return distance;
    }

    public byte[] getNodeId() { return nodeId; }
    public InetSocketAddress getAddress() { return address; }
    public long getLastSeen() { return lastSeen; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DHTNode)) return false;
        return Arrays.equals(nodeId, ((DHTNode)o).nodeId);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(nodeId);
    }
}