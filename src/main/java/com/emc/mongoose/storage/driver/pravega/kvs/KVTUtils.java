package com.emc.mongoose.storage.driver.pravega.kvs;

import io.pravega.client.admin.KeyValueTableManager;
import io.pravega.client.tables.KeyValueTable;

public interface KVTUtils {
    public KeyValueTableManager createManager();

    public KeyValueTable<String, String> KVT();

}
