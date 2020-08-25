package com.emc.mongoose.storage.driver.pravega.kvs.cache;

import io.pravega.client.KeyValueTableFactory;

import java.util.function.Function;

/**
 A function to create the KVT factory using the scope name as the function argument
 */
public interface KVTFactoryCreateFunction
				extends Function<String, KeyValueTableFactory> {

	/**
	 @param scopeName the scope name
	 @return the created KVT factory
	 */
	@Override
	KeyValueTableFactory apply(final String scopeName);
}
