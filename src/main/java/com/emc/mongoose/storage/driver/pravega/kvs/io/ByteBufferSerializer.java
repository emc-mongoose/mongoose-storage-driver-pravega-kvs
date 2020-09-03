package com.emc.mongoose.storage.driver.pravega.kvs.io;

import io.pravega.client.stream.Serializer;

import java.io.Serializable;
import java.nio.ByteBuffer;

public class ByteBufferSerializer
    implements Serializer<ByteBuffer>, Serializable {

    /**
     * Not implemented. Do not invoke this.
     *
     * @throws AssertionError
     */
    @Override
    public final ByteBuffer serialize(final ByteBuffer someNumber) {
        throw new AssertionError("Not implemented");
    }

    /**
     * Deserializes the given ByteBuffer into an event.
     *
     * @param serializedValue An event that has been previously serialized.
     * @return countBytesDone.
     */
    @Override
    public final ByteBuffer deserialize(final ByteBuffer serializedValue)
        throws OutOfMemoryError, IllegalArgumentException {
        return serializedValue;
    }
}
