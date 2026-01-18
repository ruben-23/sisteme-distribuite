package org.example.peer;

import org.example.core.*;
import org.example.core.protocol.*;
import org.example.util.Logger;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accepts incoming peer connections
 */
public class PeerServer {
    private ServerSocket serverSocket;
    private PeerNode peerNode;
    private ExecutorService executor;
    private volatile boolean running;

    public PeerServer(int port, PeerNode peerNode) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.peerNode = peerNode;
        this.executor = Executors.newCachedThreadPool();
        Logger.info("Peer server listening on port " + port);
    }

    public void start() {
        running = true;
        executor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Logger.info("Accepted connection from " + socket.getRemoteSocketAddress());
                executor.submit(() -> handlePeer(socket));
            } catch (IOException e) {
                if (running) {
                    Logger.error("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handlePeer(Socket socket) {
        try {
            Connection conn = new Connection(socket);

            // Receive handshake
            Handshake handshake = conn.receiveHandshake();

            // Verify info hash
            if (!peerNode.hasInfoHash(handshake.getInfoHash())) {
                Logger.warn("Unknown info_hash from " + socket.getRemoteSocketAddress());
                conn.close();
                return;
            }

            // Send our handshake
            conn.sendHandshake(peerNode.createHandshake(handshake.getInfoHash()));

            // Send bitfield
            FileManager fileManager = peerNode.getFileManager(handshake.getInfoHash());
            Bitfield bitfield = new Bitfield(
                    fileManager.getBitfield(),
                    peerNode.getTorrent(handshake.getInfoHash()).getNumPieces()
            );
            conn.sendBitfield(bitfield);

            // Unchoke immediately (optimistic unchoking)
            conn.sendUnchoke();

            // Handle messages from this peer
            peerNode.addConnection(handshake.getInfoHash(), conn);
            handlePeerMessages(conn, handshake.getInfoHash());

        } catch (IOException e) {
            Logger.error("Error handling peer: " + e.getMessage());
        }
    }

    private void handlePeerMessages(Connection conn, byte[] infoHash) {
        FileManager fileManager = peerNode.getFileManager(infoHash);

        try {
            while (true) {
                Connection.Message msg = conn.receiveMessage();

                switch (msg.id) {
                    case 2: // interested
                        conn.setPeerInterested(true);
                        break;

                    case Request.MESSAGE_ID:
                        Request request = Request.parse(msg.payload);
                        handleRequest(conn, fileManager, request);
                        break;

                }
            }
        } catch (IOException e) {
            Logger.info("Peer disconnected: " + e.getMessage());
            conn.close();
        }
    }

    private void handleRequest(Connection conn, FileManager fileManager, Request request) {
        try {
            byte[] pieceData = fileManager.readPiece(request.getPieceIndex());
            if (pieceData == null) {
                Logger.warn("Don't have piece " + request.getPieceIndex());
                return;
            }

            // Extract requested block
            int end = Math.min(request.getBegin() + request.getLength(), pieceData.length);
            byte[] block = new byte[end - request.getBegin()];
            System.arraycopy(pieceData, request.getBegin(), block, 0, block.length);

            Piece piece = new Piece(request.getPieceIndex(), request.getBegin(), block);
            conn.sendPiece(piece);

        } catch (IOException e) {
            Logger.error("Error sending piece: " + e.getMessage());
        }
    }


    public void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // Ignore
        }
        executor.shutdownNow();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }
}
