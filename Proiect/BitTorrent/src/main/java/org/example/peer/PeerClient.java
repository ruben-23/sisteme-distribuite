package org.example.peer;

import org.example.core.*;
import org.example.core.protocol.*;
import org.example.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Connects to peers and downloads pieces
 */
public class PeerClient {
    private PeerNode peerNode;
    private ExecutorService executor;
    private Map<byte[], Set<Connection>> activeConnections;

    public PeerClient(PeerNode peerNode) {
        this.peerNode = peerNode;
        this.executor = Executors.newFixedThreadPool(5);
        this.activeConnections = new ConcurrentHashMap<>();
    }

    /**
     * Connect to a peer
     */
    public void connectToPeer(InetSocketAddress peerAddress, byte[] infoHash) {
        executor.submit(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(peerAddress, 5000);
                Logger.info("Connected to peer: " + peerAddress);

                Connection conn = new Connection(socket);

                // Send handshake
                conn.sendHandshake(peerNode.createHandshake(infoHash));

                // Receive handshake
                Handshake handshake = conn.receiveHandshake();
                if (!Arrays.equals(handshake.getInfoHash(), infoHash)) {
                    Logger.warn("Info hash mismatch");
                    conn.close();
                    return;
                }

                // Prevent connecting to self
                if (Arrays.equals(handshake.getPeerId(), peerNode.getPeerId())) {
                    Logger.debug("Connected to self, closing connection");
                    conn.close();
                    return;
                }

                // Add connection
                activeConnections.computeIfAbsent(infoHash, k -> ConcurrentHashMap.newKeySet())
                        .add(conn);

                // Handle connection
                handleConnection(conn, infoHash);

            } catch (IOException e) {
                Logger.error("Failed to connect to " + peerAddress + ": " + e.getMessage());
            }
        });
    }

    private void handleConnection(Connection conn, byte[] infoHash) throws IOException {
        FileManager fileManager = peerNode.getFileManager(infoHash);
        TorrentFile torrent = peerNode.getTorrent(infoHash);
        BitSet peerPieces = new BitSet();
        Map<Integer, ByteArrayOutputStream> pendingPieces = new ConcurrentHashMap<>();

        // Send our bitfield
        Bitfield ourBitfield = new Bitfield(fileManager.getBitfield(), torrent.getNumPieces());
        conn.sendBitfield(ourBitfield);

        // Send interested
        conn.sendInterested();

        try {
            while (true) {
                Connection.Message msg = conn.receiveMessage();

                switch (msg.id) {
                    case 1: // unchoke
                        conn.setPeerChoked(false);
                        requestPieces(conn, fileManager, torrent, peerPieces, pendingPieces);
                        break;

                    case Bitfield.MESSAGE_ID:
                        Bitfield bitfield = Bitfield.parse(msg.payload, torrent.getNumPieces());
                        peerPieces = bitfield.getPieces();
                        break;

                    case Piece.MESSAGE_ID:
                        Piece piece = Piece.parse(msg.payload);
                        handlePieceBlock(piece, fileManager, torrent, pendingPieces,
                                conn, peerPieces);
                        break;

                    case 4: // have
                        int pieceIndex = ByteBuffer.wrap(msg.payload).getInt();
                        peerPieces.set(pieceIndex);
                        break;
                }
            }
        } catch (IOException e) {
            Logger.debug("Connection closed: " + e.getMessage());
            conn.close();
            activeConnections.get(infoHash).remove(conn);
        }
    }

    private void requestPieces(Connection conn, FileManager fileManager,
                               TorrentFile torrent, BitSet peerPieces,
                               Map<Integer, ByteArrayOutputStream> pendingPieces) {
        if (conn.isPeerChoked()) return;

        BitSet ourPieces = fileManager.getBitfield();

        // Find pieces we need that peer has
        for (int i = 0; i < torrent.getNumPieces(); i++) {
            if (!ourPieces.get(i) && peerPieces.get(i) && !pendingPieces.containsKey(i)) {
                requestPiece(conn, i, torrent, pendingPieces);
                break; // Request one piece at a time
            }
        }
    }

    private void requestPiece(Connection conn, int pieceIndex, TorrentFile torrent,
                              Map<Integer, ByteArrayOutputStream> pendingPieces) {
        try {
            int pieceLength = (pieceIndex == torrent.getNumPieces() - 1) ?
                    torrent.getLastPieceLength() : (int)torrent.getPieceLength();

            pendingPieces.put(pieceIndex, new ByteArrayOutputStream());

            // Request in blocks
            int offset = 0;
            while (offset < pieceLength) {
                int blockSize = Math.min(Request.BLOCK_SIZE, pieceLength - offset);
                Request request = new Request(pieceIndex, offset, blockSize);
                conn.sendRequest(request);
                offset += blockSize;
            }

        } catch (IOException e) {
            Logger.error("Error requesting piece: " + e.getMessage());
            pendingPieces.remove(pieceIndex);
        }
    }

    private void handlePieceBlock(Piece piece, FileManager fileManager, TorrentFile torrent,
                                  Map<Integer, ByteArrayOutputStream> pendingPieces,
                                  Connection conn, BitSet peerPieces) {
        int pieceIndex = piece.getPieceIndex();
        ByteArrayOutputStream pieceData = pendingPieces.get(pieceIndex);

        if (pieceData == null) {
            Logger.warn("Received unexpected piece block: " + pieceIndex);
            return;
        }

        try {
            pieceData.write(piece.getBlock());

            // Check if piece is complete
            int expectedLength = (pieceIndex == torrent.getNumPieces() - 1) ?
                    torrent.getLastPieceLength() : (int)torrent.getPieceLength();

            if (pieceData.size() >= expectedLength) {
                byte[] completePiece = pieceData.toByteArray();

                if (fileManager.writePiece(pieceIndex, completePiece)) {
//                    Logger.info("Downloaded piece " + pieceIndex + " (" +
//                            fileManager.getCompletionPercentage() + "% complete)");

                    String peerAddress = conn.getRemoteAddress(); // Get the IP
//                    Logger.info("Downloaded piece " + pieceIndex +
//                            " from " + peerAddress +
//                            " (" + fileManager.getCompletionPercentage() + "% complete)");
                    Logger.debug("Downloaded block for Piece " + pieceIndex + " from " + peerAddress + " (" +
                            fileManager.getCompletionPercentage() + "% complete)");


                    if (fileManager.isComplete()) {
                        Logger.info("Download complete!");
                    }
                }

                pendingPieces.remove(pieceIndex);

                // Request next piece
                requestPieces(conn, fileManager, torrent, peerPieces, pendingPieces);
            }
        } catch (IOException e) {
            Logger.error("Error handling piece block: " + e.getMessage());
            pendingPieces.remove(pieceIndex);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        for (Set<Connection> conns : activeConnections.values()) {
            conns.forEach(Connection::close);
        }
    }
}