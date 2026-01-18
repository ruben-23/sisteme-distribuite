package org.example.core.protocol;

import java.util.BitSet;

/**
 * Bitfield message - tells peers which pieces we have
 * Message format: <length><id><bitfield>
 * - length: 4 bytes
 * - id: 1 byte (5 for bitfield)
 * - bitfield: variable length
 */
public class Bitfield {
    public static final byte MESSAGE_ID = 5;

    private BitSet pieces;
    private int numPieces;

    public Bitfield(BitSet pieces, int numPieces) {
        this.pieces = pieces;
        this.numPieces = numPieces;
    }

    /**
     * Convert to byte array for transmission
     */
    public byte[] toBytes() {
        int numBytes = (numPieces + 7) / 8; // round up
        byte[] bitfield = new byte[numBytes];

        for (int i = 0; i < numPieces; i++) {
            if (pieces.get(i)) {
                int byteIndex = i / 8;
                int bitIndex = 7 - (i % 8); // MSB first
                bitfield[byteIndex] |= (1 << bitIndex);
            }
        }

        return bitfield;
    }

    /**
     * Parse from byte array
     */
    public static Bitfield parse(byte[] data, int numPieces) {
        BitSet pieces = new BitSet(numPieces);

        for (int i = 0; i < numPieces; i++) {
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8);

            if (byteIndex < data.length) {
                boolean hasPiece = ((data[byteIndex] >> bitIndex) & 1) == 1;
                pieces.set(i, hasPiece);
            }
        }

        return new Bitfield(pieces, numPieces);
    }

    public BitSet getPieces() { return pieces; }
    public boolean hasPiece(int index) { return pieces.get(index); }
}