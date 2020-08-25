package com.emc.mongoose.storage.driver.pravega.kvs.cache;

import io.pravega.client.tables.KeyValueTable;

import java.util.function.Function;

/**
 A function for KVT client create function using the serializer name as the function argument
 */

public interface KVTClientCreateFunction
        extends Function<String, KeyValueTable> {

    /**
     @param kvtName the scope name
     @return the KVT create function
     */
    @Override
    KeyValueTable apply(final String kvtName);
}
