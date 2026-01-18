package org.example.core;

import org.example.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages file storage, reading, and writing of pieces
 */
public class FileManager {
    private final TorrentFile torrent;
    private final File downloadDir;
    private final File targetFile;
    private final BitSet havePieces; // which pieces we have
    private final Map<Integer, byte[]> pieceCache; // in-memory cache

    public FileManager(TorrentFile torrent, File downloadDir) {
        this.torrent = torrent;
        this.downloadDir = downloadDir;
        this.targetFile = new File(downloadDir, torrent.getName());
        this.havePieces = new BitSet(torrent.getNumPieces());
        this.pieceCache = new ConcurrentHashMap<>();

        // Check existing file
        if (targetFile.exists()) {
            verifyExistingFile();
        } else {
            // Pre-allocate file
            try {
                createEmptyFile();
            } catch (IOException e) {
                Logger.error("Failed to create file: " + e.getMessage());
            }
        }
    }

    /**
     * Pre-allocate file with correct size
     */
    private void createEmptyFile() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            raf.setLength(torrent.getTotalLength());
        }
        Logger.info("Created empty file: " + targetFile.getPath());
    }

    /**
     * Verify which pieces we already have
     */
    private void verifyExistingFile() {
        Logger.info("Verifying existing file...");
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "r")) {
            for (int i = 0; i < torrent.getNumPieces(); i++) {
                byte[] piece = readPieceFromDisk(raf, i);
                if (piece != null && verifyPiece(i, piece)) {
                    havePieces.set(i);
                }
            }
        } catch (IOException e) {
            Logger.error("Error verifying file: " + e.getMessage());
        }

        Logger.info("Have " + havePieces.cardinality() + "/" +
                torrent.getNumPieces() + " pieces");
    }

    /**
     * Read piece from disk
     */
    private byte[] readPieceFromDisk(RandomAccessFile raf, int pieceIndex) throws IOException {
        long offset = (long)pieceIndex * torrent.getPieceLength();
        int length = (pieceIndex == torrent.getNumPieces() - 1) ?
                torrent.getLastPieceLength() : (int)torrent.getPieceLength();

        byte[] data = new byte[length];
        raf.seek(offset);
        int bytesRead = raf.read(data);

        return (bytesRead == length) ? data : null;
    }

    /**
     * Verify piece integrity
     */
    private boolean verifyPiece(int pieceIndex, byte[] data) {
        byte[] expectedHash = torrent.getPieceHash(pieceIndex);
        byte[] actualHash = Hash.sha1(data);
        return Arrays.equals(expectedHash, actualHash);
    }

    /**
     * Write piece to disk
     */
    public synchronized boolean writePiece(int pieceIndex, byte[] data) {
        if (havePieces.get(pieceIndex)) {
            return true;
        }

        // Verify piece
        if (!verifyPiece(pieceIndex, data)) {
            Logger.warn("Piece " + pieceIndex + " failed verification");
            return false;
        }

        // Write to disk
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            long offset = (long)pieceIndex * torrent.getPieceLength();
            raf.seek(offset);
            raf.write(data);

            havePieces.set(pieceIndex);

            return true;
        } catch (IOException e) {
            Logger.error("Failed to write piece " + pieceIndex + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Read piece (from cache or disk)
     */
    public synchronized byte[] readPiece(int pieceIndex) {
        if (!havePieces.get(pieceIndex)) {
            return null;
        }

        // Check cache
        if (pieceCache.containsKey(pieceIndex)) {
            return pieceCache.get(pieceIndex);
        }

        // Read from disk
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "r")) {
            byte[] piece = readPieceFromDisk(raf, pieceIndex);
            if (piece != null) {
                // Cache it
                pieceCache.put(pieceIndex, piece);
            }
            return piece;
        } catch (IOException e) {
            Logger.error("Failed to read piece " + pieceIndex + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get bitfield of pieces we have
     */
    public synchronized BitSet getBitfield() {
        return (BitSet)havePieces.clone();
    }

    /**
     * Check if download is complete
     */
    public boolean isComplete() {
        return havePieces.cardinality() == torrent.getNumPieces();
    }

    /**
     * Get completion percentage
     */
    public double getCompletionPercentage() {
        double value = 100.0 * havePieces.cardinality() / torrent.getNumPieces();
        return Math.round(value * 100.0) / 100.0;
    }
}