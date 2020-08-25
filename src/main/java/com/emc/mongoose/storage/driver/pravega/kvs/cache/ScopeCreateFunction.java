package com.emc.mongoose.storage.driver.pravega.kvs.cache;

import java.util.function.Function;

/**
 A function to create the kvt using the scope name as a function argument
 */
public interface ScopeCreateFunction
				extends Function<String, KVTCreateFunction> {

	/**
	 @param scopeName the name of the scope where to create kvt
	 @return the function to create a kvt in the given scope
	 */
	@Override
    KVTCreateFunction apply(final String scopeName);
}
