package org.example.core.protocol;

import java.nio.ByteBuffer;

/**
 * Piece message - contains piece data
 * Format: <length><id><index><begin><block>
 * - length: 4 bytes (9 + block length)
 * - id: 1 byte (7)
 * - index: 4 bytes
 * - begin: 4 bytes
 * - block: variable length
 */
public class Piece {
    public static final byte MESSAGE_ID = 7;

    private int pieceIndex;
    private int begin;
    private byte[] block;

    public Piece(int pieceIndex, int begin, byte[] block) {
        this.pieceIndex = pieceIndex;
        this.begin = begin;
        this.block = block;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(8 + block.length);
        buffer.putInt(pieceIndex);
        buffer.putInt(begin);
        buffer.put(block);
        return buffer.array();
    }

    public static Piece parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int pieceIndex = buffer.getInt();
        int begin = buffer.getInt();
        byte[] block = new byte[data.length - 8];
        buffer.get(block);
        return new Piece(pieceIndex, begin, block);
    }

    public int getPieceIndex() { return pieceIndex; }
    public int getBegin() { return begin; }
    public byte[] getBlock() { return block; }
}