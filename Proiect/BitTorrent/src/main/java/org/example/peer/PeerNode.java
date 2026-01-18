package org.example.peer;

import org.example.core.*;
import org.example.core.protocol.Handshake;
import org.example.dht.LocalDHT;
import org.example.util.*;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main peer node that coordinates everything
 */
public class PeerNode {
    private byte[] peerId;                    // Unique ID for this peer (like "-LT0001-XXXXXX...")
    private LocalDHT dht;                     // Custom DHT for peer discovery system
    private PeerServer server;                // Listens for incoming connections
    private PeerClient client;                // Connects to other peers
    private Map<String, TorrentFile> torrents;           // Loaded .torrent files (by info_hash) [torrents the peer knows about]
    private Map<String, FileManager> fileManagers;       // Handles reading/writing pieces
    private Map<String, Set<Connection>> connections;    // Active peer connections per torrent
    private int port;

    public PeerNode(int port) throws IOException {
        this.peerId = generatePeerId();  // Creates ID like -LT0001-abcd1234...
        this.port = port;
        this.dht = new LocalDHT();       // Starts local peer discovery
        this.server = new PeerServer(port, this);  // Accepts incoming connections
        this.client = new PeerClient(this);        // Makes outgoing connections
        this.torrents = new ConcurrentHashMap<>();
        this.fileManagers = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();

        Logger.info("Peer node created with ID: " + Hash.toHex(peerId));
    }

    private byte[] generatePeerId() {
        // Format: -LT0001-<12 random chars>
        byte[] id = new byte[20];
        String prefix = "-LT0001-";
        System.arraycopy(prefix.getBytes(), 0, id, 0, 8);

        // Fill the rest with random bytes
        Random random = new SecureRandom();
        byte[] randomBytes = new byte[20 - prefix.length()];
        random.nextBytes(randomBytes);
        System.arraycopy(randomBytes, 0, id, prefix.length(), randomBytes.length);

        return id;
    }

    public byte[] getPeerId() {
        return peerId;
    }

    public void start() {
        dht.start();
        server.start();
        Logger.info("Peer node started on port " + port);
    }

    /**
     * Share a file
     */
    public void shareFile(File file, File downloadDir) throws IOException {
        // Create torrent
        TorrentFile torrent = TorrentFile.createFromFile(file);
        String infoHashHex = Hash.toHex(torrent.getInfoHash());

        torrents.put(infoHashHex, torrent);

        // Create file manager (file already exists)
        FileManager fileManager = new FileManager(torrent, downloadDir);
        fileManagers.put(infoHashHex, fileManager);

        // Announce to DHT
        dht.announceTorrent(torrent.getInfoHash(), port);

        // Save .torrent file
        File torrentFile = new File(downloadDir, file.getName() + ".torrent");
        torrent.saveTo(torrentFile);

        Logger.info("Sharing file: " + file.getName());
        dht.registerTorrentName(torrent.getInfoHash(), torrent.getName(), torrent.getTotalLength());
    }

    /**
     * Download a file from torrent
     */
    public void downloadFromTorrent(File torrentFile, File downloadDir) throws IOException {
        TorrentFile torrent = TorrentFile.loadFrom(torrentFile);
        String infoHashHex = Hash.toHex(torrent.getInfoHash());

        torrents.put(infoHashHex, torrent);

        // Create file manager
        FileManager fileManager = new FileManager(torrent, downloadDir);
        fileManagers.put(infoHashHex, fileManager);

        if (fileManager.isComplete()) {
            Logger.info("File already complete!");
            return;
        }

        // Find peers via DHT
        dht.findPeers(torrent.getInfoHash());

        // Wait a bit for DHT responses
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Connect to peers
        Set<InetSocketAddress> peers = dht.getPeers(torrent.getInfoHash());
        Logger.info("Found " + peers.size() + " peers");

        for (InetSocketAddress peer : peers) {
            client.connectToPeer(peer, torrent.getInfoHash());
        }

        // Also announce ourselves
        dht.announceTorrent(torrent.getInfoHash(), port);
        dht.registerTorrentName(torrent.getInfoHash(), torrent.getName(), torrent.getTotalLength());
    }

    public boolean hasInfoHash(byte[] infoHash) {
        return torrents.containsKey(Hash.toHex(infoHash));
    }

    public TorrentFile getTorrent(byte[] infoHash) {
        return torrents.get(Hash.toHex(infoHash));
    }

    public FileManager getFileManager(byte[] infoHash) {
        return fileManagers.get(Hash.toHex(infoHash));
    }

    public Handshake createHandshake(byte[] infoHash) {
        return new Handshake(infoHash, peerId);
    }

    public void addConnection(byte[] infoHash, Connection conn) {
        connections.computeIfAbsent(Hash.toHex(infoHash), k -> ConcurrentHashMap.newKeySet())
                .add(conn);
    }

    public void stop() {
        dht.stop();
        server.stop();
        client.shutdown();

        for (Set<Connection> conns : connections.values()) {
            conns.forEach(Connection::close);
        }

        Logger.info("Peer node stopped");
    }

    public void showNetworkStatus() {
        System.out.println("\n=== LAN BITTORRENT NETWORK STATUS ===");
        System.out.println("Known nodes: " + dht.getKnownNodesCount());
        System.out.println("Active torrents: " + dht.getActiveTorrentCount());
        System.out.println();

        if (dht.getTorrentPeersMap().isEmpty()) {
            System.out.println("No activity yet. Start sharing on other machines!");
            return;
        }

        System.out.println("SHARED FILES:");
        System.out.println("──────────────────────────────────────────────────────────────");
        for (Map.Entry<String, Set<InetSocketAddress>> e : dht.getTorrentPeersMap().entrySet()) {
            String hash = e.getKey();
            String name = dht.getTorrentName(hash);
            long size = dht.getTorrentSize(hash);
            System.out.println("File: " + name);
            System.out.println("  Size: " + size + " bytes");
            System.out.println("  Info hash: " + hash.substring(0, 16) + "...");
            System.out.println("  Shared by:");
            for (InetSocketAddress p : e.getValue()) {
                System.out.println("    • " + p.getAddress().getHostAddress() + ":" + p.getPort());
            }
            System.out.println();
        }
        System.out.println("====================================================\n");
    }
}
