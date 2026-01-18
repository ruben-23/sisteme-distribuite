package org.example.core;

import org.example.util.*;
import java.io.*;
import java.util.*;

/**
 * Represents a .torrent file metadata
 * Structure:
 * - announce: tracker URL (we'll use DHT instead)
 * - info:
 *   - name: filename
 *   - piece length: size of each piece
 *   - pieces: concatenated SHA-1 hashes
 *   - length: total file size
 */
public class TorrentFile {
    private String name;
    private long pieceLength;
    private byte[] pieces; // concatenated 20-byte SHA-1 hashes
    private long totalLength;
    private byte[] infoHash; // SHA-1 of bencoded info dict

    public static final long DEFAULT_PIECE_LENGTH = 256 * 1024; // 256 KB

    /**
     * Create torrent from file
     */
    public static TorrentFile createFromFile(File file) throws IOException {
        TorrentFile torrent = new TorrentFile();
        torrent.name = file.getName();
        torrent.totalLength = file.length();
        torrent.pieceLength = DEFAULT_PIECE_LENGTH;

        // Calculate piece hashes
        int numPieces = (int)Math.ceil((double)torrent.totalLength / torrent.pieceLength);

        // the buffer which will contain the concatenated hashes of the pieces
        ByteArrayOutputStream piecesStream = new ByteArrayOutputStream();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int)torrent.pieceLength];

            for (int i = 0; i < numPieces; i++) {
                int bytesRead = fis.read(buffer);
                if (bytesRead == -1) break;

                // if the piece doesn't have exactly pieceLength(256) KB - truncate irrelevant data
                byte[] pieceData = Arrays.copyOf(buffer, bytesRead);
                byte[] hash = Hash.sha1(pieceData);
                piecesStream.write(hash);

            }
        }

        torrent.pieces = piecesStream.toByteArray();

        // Calculate info_hash
        Map<String, Object> infoDict = torrent.buildInfoDict();
        byte[] bencodedInfo = Bencode.encode(infoDict);
        torrent.infoHash = Hash.sha1(bencodedInfo);

        Logger.info("Created torrent for " + file.getName() +
                " (" + numPieces + " pieces, info_hash: " +
                Hash.toHex(torrent.infoHash) + ")");

        return torrent;
    }

    /**
     * Build info dictionary for bencoding
     */
    private Map<String, Object> buildInfoDict() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("piece length", pieceLength);
        info.put("pieces", pieces);
        info.put("length", totalLength);
        return info;
    }

    /**
     * Save torrent to .torrent file
     */
    public void saveTo(File file) throws IOException {
        Map<String, Object> torrent = new LinkedHashMap<>();
        torrent.put("info", buildInfoDict());

        byte[] encoded = Bencode.encode(torrent);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(encoded);
        }

        Logger.info("Saved torrent to " + file.getPath());
    }

    /**
     * Load torrent from .torrent file
     */
    public static TorrentFile loadFrom(File file) throws IOException {
        byte[] data = new byte[(int)file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(data);
        }

        Map<String, Object> torrent = (Map<String, Object>)Bencode.decode(data);
        Map<String, Object> info = (Map<String, Object>)torrent.get("info");

        TorrentFile tf = new TorrentFile();
        tf.name = new String((byte[])info.get("name"));
        tf.pieceLength = (Long)info.get("piece length");
        tf.pieces = (byte[])info.get("pieces");
        tf.totalLength = (Long)info.get("length");

        // Calculate info_hash
        byte[] bencodedInfo = Bencode.encode(info);
        tf.infoHash = Hash.sha1(bencodedInfo);

        Logger.info("Loaded torrent: " + tf.name +
                " (" + tf.getNumPieces() + " pieces)");

        return tf;
    }

    // Getters
    public String getName() { return name; }
    public long getPieceLength() { return pieceLength; }
    public long getTotalLength() { return totalLength; }
    public byte[] getInfoHash() { return infoHash; }
    public int getNumPieces() { return pieces.length / 20; }

    public byte[] getPieceHash(int index) {
        byte[] hash = new byte[20];
        System.arraycopy(pieces, index * 20, hash, 0, 20);
        return hash;
    }

    public int getLastPieceLength() {
        long remainder = totalLength % pieceLength;
        return (int)(remainder == 0 ? pieceLength : remainder);
    }
}