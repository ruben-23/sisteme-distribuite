package org.example.discovery;

import org.example.util.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local multicast Discovery implementation for peer discovery in LAN.
 * Binds to all interfaces to improve LAN visibility across virtualized networks.
 */
public class LocalDiscovery {
    private static final String MULTICAST_GROUP = "239.192.1.1";
    private static final int DISCOVERY_PORT = 6881;

    private final byte[] nodeId;
    private MulticastSocket socket;
    private InetAddress group;

    // storage
    private Map<String, Set<InetSocketAddress>> torrentPeers; // info_hash -> peers
    private Map<String, DiscoveryNode> nodes; // nodeId -> DiscoveryNode
    private final Map<String, String> torrentNames = new ConcurrentHashMap<>();   // infoHashHex -> filename
    private final Map<String, Long> torrentSizes = new ConcurrentHashMap<>();     // infoHashHex -> size

    private volatile boolean running;
    private ExecutorService executor;

    public LocalDiscovery() throws IOException {
        this.nodeId = generateNodeId();
        this.torrentPeers = new ConcurrentHashMap<>();
        this.nodes = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();

        this.socket = new MulticastSocket(DISCOVERY_PORT);
        this.socket.setReuseAddress(true);
        this.socket.setLoopbackMode(false);

        this.group = InetAddress.getByName(MULTICAST_GROUP);
        joinGroupOnAllInterfaces();

        Logger.info("Discovery started with node ID: " + Hash.toHex(nodeId));
    }

    private void joinGroupOnAllInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                if (iface.isLoopback() || !iface.isUp() || !iface.supportsMulticast()) {
                    continue;
                }

                try {
                    socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), iface);
                } catch (IOException e) {
                    // Logger.debug("Could not join on " + iface.getName() + ": " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            // Logger.error("Error enumerating interfaces: " + e.getMessage());
        }
    }

    private byte[] generateNodeId() {
        byte[] id = new byte[20];
        new Random().nextBytes(id);
        return id;
    }

    public void start() {
        running = true;
        executor.submit(this::receiveLoop);
        Logger.info("Discovery listening on " + MULTICAST_GROUP + ":" + DISCOVERY_PORT);
    }

    /**
     * Announce that we have a torrent.
     */
    public void announceTorrent(byte[] infoHash, int port) {
        Message msg = new Message(Message.Type.ANNOUNCE_PEER, generateTxId());
        msg.put("info_hash", infoHash);
        msg.put("port", (long) port);
        msg.put("node_id", nodeId);

        sendMessage(msg);
        Logger.info("Announced torrent: " + Hash.toHex(infoHash));
    }

    /**
     * Register torrent metadata (name/size) for UI display.
     */
    public void registerTorrentName(byte[] infoHash, String name, long size) {
        String hex = Hash.toHex(infoHash);
        if (name != null) torrentNames.put(hex, name);
        torrentSizes.put(hex, size);
    }

    /**
     * Get peers we already found for a torrent.
     */
    public Set<InetSocketAddress> getPeers(byte[] infoHash) {
        String key = Hash.toHex(infoHash);
        return torrentPeers.getOrDefault(key, new HashSet<>());
    }

    /**
     * Query the local multicast discovery mechanism for peers.
     */
    public void findPeers(byte[] infoHash) {
        Message msg = new Message(Message.Type.GET_PEERS, generateTxId());
        msg.put("info_hash", infoHash);
        msg.put("node_id", nodeId);

        sendMessage(msg);
        Logger.debug("Querying for peers: " + Hash.toHex(infoHash));
    }

    private void sendMessage(Message msg) {
        try {
            byte[] data = msg.toBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, group, DISCOVERY_PORT);
            socket.send(packet);
        } catch (IOException e) {
            Logger.error("Failed to send discovery message: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[8192];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());

                handleMessage(data, sender);
            } catch (IOException e) {
                if (running) {
                    Logger.error("Discovery receive error: " + e.getMessage());
                }
            }
        }
    }

    private void handleMessage(byte[] data, InetSocketAddress sender) {
        try {
            Message msg = Message.parse(data);

            byte[] msgNodeId = (byte[]) msg.get("node_id");
            if (msgNodeId != null && Arrays.equals(msgNodeId, nodeId)) {
                return;
            }

            switch (msg.getType()) {
                case ANNOUNCE_PEER:
                    handleAnnouncePeer(msg, sender);
                    break;
                case GET_PEERS:
                    handleGetPeers(msg, sender);
                    break;
                case PING:
                    handlePing(msg, sender);
                    break;
                case RESPONSE:
                    handleResponse(msg, sender);
                    break;
            }
        } catch (Exception e) {
            Logger.debug("Failed to parse discovery message: " + e.getMessage());
        }
    }

    private void handleAnnouncePeer(Message msg, InetSocketAddress sender) {
        byte[] infoHash = (byte[]) msg.get("info_hash");
        Object portObj = msg.get("port");

        int port;
        if (portObj instanceof Long) {
            port = ((Long) portObj).intValue();
        } else if (portObj instanceof Integer) {
            port = (Integer) portObj;
        } else {
            return;
        }

        byte[] remoteNodeId = (byte[]) msg.get("node_id");

        if (sender.getAddress().isAnyLocalAddress()) return;

        String key = Hash.toHex(infoHash);

        InetSocketAddress peerAddress = new InetSocketAddress(sender.getAddress(), port);

        torrentPeers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(peerAddress);

        if (remoteNodeId != null) {
            nodes.put(Hash.toHex(remoteNodeId), new DiscoveryNode(remoteNodeId, sender));
        }

        Logger.debug("Peer announced: " + peerAddress + " for " + key.substring(0, 8));
    }

    private void handleGetPeers(Message msg, InetSocketAddress sender) {
        byte[] infoHash = (byte[]) msg.get("info_hash");
        String key = Hash.toHex(infoHash);

        Set<InetSocketAddress> peers = torrentPeers.get(key);
        if (peers != null && !peers.isEmpty()) {
            Message response = new Message(Message.Type.RESPONSE, msg.getTransactionId());

            List<byte[]> peerList = new ArrayList<>();
            for (InetSocketAddress peer : peers) {
                if (peer.getAddress().equals(sender.getAddress()) && peer.getPort() == sender.getPort()) continue;

                byte[] compact = new byte[6];
                byte[] addr = peer.getAddress().getAddress();
                System.arraycopy(addr, 0, compact, 0, 4);
                compact[4] = (byte) ((peer.getPort() >> 8) & 0xFF);
                compact[5] = (byte) (peer.getPort() & 0xFF);
                peerList.add(compact);
            }

            response.put("values", peerList);
            response.put("node_id", nodeId);
            response.put("info_hash", infoHash);

            sendMessageTo(response, sender);
        }
    }

    private void handleResponse(Message msg, InetSocketAddress sender) {
        byte[] infoHash = (byte[]) msg.get("info_hash");
        if (infoHash == null) return;

        String key = Hash.toHex(infoHash);

        @SuppressWarnings("unchecked")
        List<byte[]> values = (List<byte[]>) msg.get("values");
        if (values == null) return;

        for (byte[] compact : values) {
            if (compact.length != 6) continue;
            try {
                InetSocketAddress peer = new InetSocketAddress(
                        InetAddress.getByAddress(Arrays.copyOfRange(compact, 0, 4)),
                        ((compact[4] & 0xFF) << 8) | (compact[5] & 0xFF)
                );

                if (peer.getAddress().isAnyLocalAddress()) continue;

                torrentPeers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(peer);
                Logger.info("Discovered peer via discovery: " + peer);
            } catch (Exception ignored) {
            }
        }
    }

    private void handlePing(Message msg, InetSocketAddress sender) {
        Message response = new Message(Message.Type.RESPONSE, msg.getTransactionId());
        response.put("node_id", nodeId);
        sendMessageTo(response, sender);
    }

    private void sendMessageTo(Message msg, InetSocketAddress target) {
        try {
            byte[] data = msg.toBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, target.getAddress(), target.getPort());
            socket.send(packet);
        } catch (IOException e) {
            Logger.error("Failed to send message to " + target);
        }
    }

    private String generateTxId() {
        return String.valueOf(System.nanoTime());
    }

    public void stop() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.leaveGroup(group);
                socket.close();
            }
        } catch (IOException e) {
            Logger.error("Error stopping discovery: " + e.getMessage());
        }
        executor.shutdownNow();
    }

    // --- Accessors for UI/PeerNode ---

    public String getTorrentName(String infoHashHex) {
        return torrentNames.get(infoHashHex);
    }

    public Long getTorrentSize(String infoHashHex) {
        return torrentSizes.getOrDefault(infoHashHex, 0L);
    }

    public int getActiveTorrentCount() {
        return torrentPeers.size();
    }

    public int getKnownNodesCount() {
        return nodes.size();
    }

    public Map<String, Set<InetSocketAddress>> getTorrentPeersMap() {
        return new HashMap<>(torrentPeers);
    }
}