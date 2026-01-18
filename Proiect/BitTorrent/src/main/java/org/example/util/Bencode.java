package org.example.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Bencode {

    private static final boolean DEBUG = true;  //  set to false to silence

    /**
     * Decode bencoded data into Java objects
     * Returns: String, Long, List, or Map
     */
    public static Object decode(byte[] data) throws IOException {

        if (data.length > 0) {
            String hex = bytesToHex(data, 0, Math.min(data.length, 100));
            String textPreview = safeTextPreview(data, 100);
        }
        return decode(new ByteArrayInputStream(data));
    }


public static Object decode(InputStream in) throws IOException {
    int firstByte = in.read();
    if (firstByte == -1) {
        throw new IOException("Unexpected end of stream");
    }

    char firstChar = (char) firstByte;

    if (firstByte == 'i') {
        return decodeInteger(in);
    } else if (firstByte == 'l') {
        return decodeList(in);
    } else if (firstByte == 'd') {
        return decodeDictionary(in);
    } else if (firstByte >= '0' && firstByte <= '9') {
        return decodeString(in, firstByte);
    }

    throw new IOException("Invalid bencode: '" + firstChar + "' (0x" + Integer.toHexString(firstByte) + ") — valid starts are 'd', 'l', 'i', or digit");
}

    private static Long decodeInteger(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != 'e') {
            if (b == -1) throw new IOException("Unexpected end in integer");
            sb.append((char)b);
        }
        return Long.parseLong(sb.toString());
    }

    private static byte[] decodeString(InputStream in, int firstDigit) throws IOException {
        StringBuilder lengthStr = new StringBuilder();
        lengthStr.append((char)firstDigit);

        int b;
        while ((b = in.read()) != ':') {
            if (b == -1) throw new IOException("Unexpected end in string length");
            lengthStr.append((char)b);
        }

        int length = Integer.parseInt(lengthStr.toString());
        byte[] data = new byte[length];
        int bytesRead = 0;
        while (bytesRead < length) {
            int result = in.read(data, bytesRead, length - bytesRead);
            if (result == -1) throw new IOException("Unexpected end in string data");
            bytesRead += result;
        }

        return data;
    }

    private static List<Object> decodeList(InputStream in) throws IOException {
        List<Object> list = new ArrayList<>();
        in.mark(1);
        int b = in.read();

        while (b != 'e') {
            in.reset();
            list.add(decode(in));
            in.mark(1);
            b = in.read();
        }

        return list;
    }


    private static Map<String, Object> decodeDictionary(InputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();

        int b;
        while ((b = in.read()) != 'e') {
            if (b == -1) {
                throw new IOException("Unexpected end of dictionary");
            }

            // Keys MUST be strings and start with a digit
            if (b < '0' || b > '9') {
                throw new IOException("Invalid dictionary key start: " + (char) b);
            }

            byte[] keyBytes = decodeString(in, b);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            Object value = decode(in);
            map.put(key, value);
        }

        return map;
    }


    /**
     * Encode Java objects to bencode format
     */
    public static byte[] encode(Object obj) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encode(obj, out);

        byte[] result = out.toByteArray();
        return result;
    }

    // ==================== HELPERS ====================

    private static String bytesToHex(byte[] bytes, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + len && i < bytes.length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private static String safeTextPreview(byte[] data, int max) {
        int len = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char c = (char)(data[i] & 0xFF);
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append("\\x").append(String.format("%02X", (int)c));
            }
        }
        if (data.length > max) sb.append("...");
        return sb.toString();
    }

    private static void encode(Object obj, OutputStream out) throws IOException {
        if (obj instanceof String) {
            encodeString(((String)obj).getBytes(StandardCharsets.UTF_8), out);
        } else if (obj instanceof byte[]) {
            encodeString((byte[])obj, out);
        } else if (obj instanceof Long || obj instanceof Integer) {
            encodeInteger(((Number)obj).longValue(), out);
        } else if (obj instanceof List) {
            encodeList((List<?>)obj, out);
        } else if (obj instanceof Map) {
            encodeDictionary((Map<?, ?>)obj, out);
        } else {
            throw new IOException("Unsupported type: " + obj.getClass());
        }
    }

    private static void encodeInteger(long value, OutputStream out) throws IOException {
        out.write('i');
        out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        out.write('e');
    }

    private static void encodeString(byte[] data, OutputStream out) throws IOException {
        out.write(Integer.toString(data.length).getBytes(StandardCharsets.UTF_8));
        out.write(':');
        out.write(data);
    }

    private static void encodeList(List<?> list, OutputStream out) throws IOException {
        out.write('l');
        for (Object item : list) {
            encode(item, out);
        }
        out.write('e');
    }

    private static void encodeDictionary(Map<?, ?> map, OutputStream out) throws IOException {
        out.write('d');
        // Keys must be sorted
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            keys.add(key.toString());
        }
        Collections.sort(keys);

        for (String key : keys) {
            encodeString(key.getBytes(StandardCharsets.UTF_8), out);
            encode(map.get(key), out);
        }
        out.write('e');
    }
}
