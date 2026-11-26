package cr.logisticaportuaria.shared.domain;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generator of UUID v7 (RFC 9562): time ordered, so the primary keys of the
 * database follow the insertion order.
 *
 * <p>The layout is the one of the RFC: 48 bits of unix time in milliseconds,
 * 4 bits of version, 12 bits of randomness and 2 bits of variant.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
        // utility class
    }

    /**
     * Returns a new random UUID v7.
     *
     * @return a version 7 UUID
     */
    public static UUID random() {
        long timestamp = System.currentTimeMillis();
        byte[] bytes = new byte[16];

        // 48 bits of timestamp (big endian).
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;

        // Version 7 in the four most significant bits of byte 6.
        bytes[6] = (byte) (0x70 | RANDOM.nextInt(16));

        // Random bytes for the rest of the entropy.
        RANDOM.nextBytes(bytes);
        bytes[6] = (byte) (0x70 | (bytes[6] & 0x0F));

        // Variant 10xx in the two most significant bits of byte 8.
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
