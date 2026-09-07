package org.example.peer;

import org.example.core.*;
import org.example.core.protocol.Handshake;
import org.example.discovery.LocalDiscovery;
import org.example.util.*;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main peer node that coordinates everything.
 */
public class PeerNode {
    private byte[] peerId;                    // Unique ID for this peer (like "-LT0001-XXXXXX...")
    private LocalDiscovery discovery;         // Local multicast discovery for peers on the LAN
    private PeerServer server;                // Listens for incoming connections
    private PeerClient client;                // Connects to other peers
    private Map<String, TorrentFile> torrents;           // Loaded .torrent files (by info_hash)
    private Map<String, FileManager> fileManagers;       // Handles reading/writing pieces
    private Map<String, Set<Connection>> connections;    // Active peer connections per torrent
    private int port;

    public PeerNode(int port) throws IOException {
        this.peerId = generatePeerId();
        this.port = port;
        this.discovery = new LocalDiscovery();
        this.server = new PeerServer(port, this);
        this.client = new PeerClient(this);
        this.torrents = new ConcurrentHashMap<>();
        this.fileManagers = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();

        Logger.info("Peer node created with ID: " + Hash.toHex(peerId));
    }

    private byte[] generatePeerId() {
        byte[] id = new byte[20];
        String prefix = "-LT0001-";
        System.arraycopy(prefix.getBytes(), 0, id, 0, 8);

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
        discovery.start();
        server.start();
        Logger.info("Peer node started on port " + port);
    }

    /**
     * Share a file.
     */
    public void shareFile(File file, File downloadDir) throws IOException {
        TorrentFile torrent = TorrentFile.createFromFile(file);
        String infoHashHex = Hash.toHex(torrent.getInfoHash());

        torrents.put(infoHashHex, torrent);

        FileManager fileManager = new FileManager(torrent, downloadDir);
        fileManagers.put(infoHashHex, fileManager);

        discovery.announceTorrent(torrent.getInfoHash(), port);

        File torrentFile = new File(downloadDir, file.getName() + ".torrent");
        torrent.saveTo(torrentFile);

        Logger.info("Sharing file: " + file.getName());
        discovery.registerTorrentName(torrent.getInfoHash(), torrent.getName(), torrent.getTotalLength());
    }

    /**
     * Download a file from torrent.
     */
    public void downloadFromTorrent(File torrentFile, File downloadDir) throws IOException {
        TorrentFile torrent = TorrentFile.loadFrom(torrentFile);
        String infoHashHex = Hash.toHex(torrent.getInfoHash());

        torrents.put(infoHashHex, torrent);

        FileManager fileManager = new FileManager(torrent, downloadDir);
        fileManagers.put(infoHashHex, fileManager);

        if (fileManager.isComplete()) {
            Logger.info("File already complete!");
            return;
        }

        discovery.findPeers(torrent.getInfoHash());

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Connect to peers
        Set<InetSocketAddress> peers = discovery.getPeers(torrent.getInfoHash());
        Logger.info("Found " + peers.size() + " peers");

        for (InetSocketAddress peer : peers) {
            client.connectToPeer(peer, torrent.getInfoHash());
        }

        discovery.announceTorrent(torrent.getInfoHash(), port);
        discovery.registerTorrentName(torrent.getInfoHash(), torrent.getName(), torrent.getTotalLength());
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
        discovery.stop();
        server.stop();
        client.shutdown();

        for (Set<Connection> conns : connections.values()) {
            conns.forEach(Connection::close);
        }

        Logger.info("Peer node stopped");
    }

    public void showNetworkStatus() {
        System.out.println("\n=== LAN BITTORRENT DISCOVERY STATUS ===");
        System.out.println("Known nodes: " + discovery.getKnownNodesCount());
        System.out.println("Active torrents: " + discovery.getActiveTorrentCount());
        System.out.println();

        if (discovery.getTorrentPeersMap().isEmpty()) {
            System.out.println("No activity yet. Start sharing on other machines!");
            return;
        }

        System.out.println("SHARED FILES:");
        System.out.println("----------------------------------------------------------");
        for (Map.Entry<String, Set<InetSocketAddress>> e : discovery.getTorrentPeersMap().entrySet()) {
            String hash = e.getKey();
            String name = discovery.getTorrentName(hash);
            long size = discovery.getTorrentSize(hash);
            System.out.println("File: " + name);
            System.out.println("Size: " + size + " bytes");
            System.out.println("Info hash: " + hash.substring(0, 16) + "...");
            System.out.println("Shared by:");
            for (InetSocketAddress p : e.getValue()) {
                System.out.println("-" + p.getAddress().getHostAddress() + ":" + p.getPort());
            }
            System.out.println();
        }
        System.out.println("====================================================\n");
    }
}
