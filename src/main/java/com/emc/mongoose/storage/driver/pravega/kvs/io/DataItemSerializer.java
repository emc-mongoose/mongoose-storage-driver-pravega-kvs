package com.emc.mongoose.storage.driver.pravega.kvs.io;

import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.logging.Loggers;
import com.github.akurilov.commons.system.SizeInBytes;
import io.pravega.client.stream.Serializer;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;

public final class DataItemSerializer<I extends DataItem>
				implements Serializer<I>, Serializable {

	private final boolean useDirectMem;
	private final boolean shouldRecordTime;
	/**
	 * @param useDirectMem Specifies whether it should use direct memory for the resulting buffer containing the
	 *                     serialized data or not. Using the direct memory may lead to the better performance in case of
	 *                     large data chunks but less safe.
	 * @param shouldRecordTime Specifies whether it should add an 8-byte timestamp in ms required for e2e latency mode
	 */
	public DataItemSerializer(final boolean useDirectMem, final boolean shouldRecordTime) {
		this.useDirectMem = useDirectMem;
		this.shouldRecordTime = shouldRecordTime;
	}

	/**
	 * @param dataItem the Mongoose's data item to serialize
	 * @return the resulting byte buffer filled with data described by the given data item
	 * @throws OutOfMemoryError if useDirectMem is disabled and not enough memory to allocate the buffer
	 */
	@Override
	public final ByteBuffer serialize(final I dataItem)
					throws OutOfMemoryError, IllegalArgumentException {
		try {
			final var dataItemSize = dataItem.size();
			if (Integer.MAX_VALUE < dataItemSize) {
				throw new IllegalArgumentException("Can't serialize the data item with size > 2^31 - 1");
			}
			if (MAX_EVENT_SIZE < dataItemSize) {
				Loggers.ERR.warn(
								"Event size is {}, Pravega storage doesn't support the event size more than {}",
								SizeInBytes.formatFixedSize(dataItemSize), SizeInBytes.formatFixedSize(MAX_EVENT_SIZE));
			}
			final var dstBuff = useDirectMem ? ByteBuffer.allocateDirect((int) dataItemSize) : // will crash if not enough memory
							ByteBuffer.allocate((int) dataItemSize); // will throw OOM error if not enough memory
			if (shouldRecordTime) {
				dstBuff.putLong(System.currentTimeMillis()); // adding timestamp as first 8 bytes
			}
			while (dstBuff.remaining() > 0) {
				dataItem.read(dstBuff);
			}
			dstBuff.flip();
			return dstBuff;
		} catch (final IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Not implemented. Do not invoke this.
	 * @throws AssertionError
	 */
	@Override
	public final I deserialize(final ByteBuffer serializedValue) {
		throw new AssertionError("Not implemented");
	}
}
