package com.emc.mongoose.storage.driver.pravega.kvs.cache;

import java.util.function.Function;

/**
 A function to create the scope using the scope name as a function argument
 */
public interface ScopeCreateFunction
				extends Function<String, com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTCreateFunction> {

	/**
	 @param scopeName the name of the scope to create
	 @return the function to create a stream in the given scope
	 */
	@Override
    com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTCreateFunction apply(final String scopeName);
}
