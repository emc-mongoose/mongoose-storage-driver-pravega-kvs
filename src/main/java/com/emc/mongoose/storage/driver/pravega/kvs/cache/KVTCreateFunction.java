package com.emc.mongoose.storage.driver.pravega.kvs.cache;

import io.pravega.client.tables.KeyValueTableConfiguration;

import java.util.function.Function;

/**
 A function to create the stream using the stream name as a function argument
 */
public interface KVTCreateFunction
				extends Function<String, KeyValueTableConfiguration> {

	/**
	 @param kvtName the name of the kvt to create
	 @return the corresponding kvt configuration
	 */
	@Override
	KeyValueTableConfiguration apply(final String kvtName);
}
