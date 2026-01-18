package org.example.core.protocol;

import java.nio.ByteBuffer;

/**
 * Request message - ask for a piece
 * Format: <length><id><index><begin><length>
 * - length: 4 bytes (13)
 * - id: 1 byte (6)
 * - index: 4 bytes (piece index)
 * - begin: 4 bytes (offset within piece)
 * - length: 4 bytes (amount to request, usually 16KB)
 */
public class Request {
    public static final byte MESSAGE_ID = 6;
    public static final int BLOCK_SIZE = 16384; // 16 KB

    private int pieceIndex;
    private int begin;
    private int length;

    public Request(int pieceIndex, int begin, int length) {
        this.pieceIndex = pieceIndex;
        this.begin = begin;
        this.length = length;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putInt(pieceIndex);
        buffer.putInt(begin);
        buffer.putInt(length);
        return buffer.array();
    }

    public static Request parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int pieceIndex = buffer.getInt();
        int begin = buffer.getInt();
        int length = buffer.getInt();
        return new Request(pieceIndex, begin, length);
    }

    public int getPieceIndex() { return pieceIndex; }
    public int getBegin() { return begin; }
    public int getLength() { return length; }
}