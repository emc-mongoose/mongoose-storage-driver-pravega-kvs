package com.emc.mongoose.storage.driver.pravega.kvs;

import io.pravega.client.KeyValueTableFactory;
import io.pravega.client.admin.KeyValueTableManager;
import io.pravega.client.tables.KeyValueTable;

public interface KVTUtils {
    KeyValueTableManager createManager();

    KeyValueTableFactory createKVTFactory();

    KeyValueTable<String, String> createKVT(final KeyValueTableFactory factory);
}
