package com.inqwise.context.spi;

import java.util.List;

/**
 * Protocol-neutral access to string metadata headers for one message. Adapters
 * own protocol-specific byte encoding, header naming, limits, and destination
 * policy. Implementations must preserve duplicates when reading headers.
 */
public interface ContextTransportCarrier {
    /**
     * Reads all values for a metadata key without changing the carrier.
     *
     * @param key the metadata key
     * @return a non-null list, empty when absent, containing no null values
     */
    List<String> getAll(String key);

    /**
     * Replaces all existing values for a metadata key with one value.
     *
     * @param key the metadata key
     * @param value the non-null encoded value
     */
    void set(String key, String value);

    /**
     * Removes all values for a metadata key, leaving other headers unchanged.
     *
     * @param key the metadata key
     */
    void remove(String key);
}
