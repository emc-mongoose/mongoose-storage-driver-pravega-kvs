package com.emc.mongoose.storage.driver.pravega.kvs;

import io.pravega.client.ClientConfig;
import io.pravega.client.KeyValueTableFactory;
import io.pravega.client.admin.KeyValueTableManager;
import io.pravega.client.stream.impl.UTF8StringSerializer;
import io.pravega.client.tables.KeyValueTable;
import io.pravega.client.tables.KeyValueTableClientConfiguration;
import lombok.NonNull;

import java.net.URI;

// TODO: need to think about naming and interface
public class KVTUtilsImpl implements KVTUtils {

    @NonNull
    private final URI controllerUri;
    @NonNull
    private final KeyValueTableFactory factory;
    @NonNull
    private final KeyValueTable table;

    public KVTUtilsImpl(final URI controllerUri, final String scopeName, final String tableName) {
        this.controllerUri = controllerUri;
        this.factory = KeyValueTableFactory
            .withScope(scopeName, ClientConfig.builder().controllerURI(controllerUri).build());
        this.table = factory.forKeyValueTable(tableName, new UTF8StringSerializer(), new UTF8StringSerializer(),
            KeyValueTableClientConfiguration.builder().build());
    }

    @Override
    public KeyValueTableManager createManager() {
        return KeyValueTableManager.create(controllerUri);
    }

    @Override
    public KeyValueTable<String, String> KVT() {
        return table;
    }

}
