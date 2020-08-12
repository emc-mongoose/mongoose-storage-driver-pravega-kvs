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
    private final String controllerUri;
    @NonNull
    private final String scopeName;

    public KVTUtilsImpl(final String controllerUri, final String scopeName) {
        this.controllerUri = controllerUri;
        this.scopeName = scopeName;
    }

    public KeyValueTableManager createManager() {
        return KeyValueTableManager.create(URI.create(controllerUri));
    }

    public KeyValueTableFactory createKVTFactory() {
        return KeyValueTableFactory
                .withScope(scopeName, ClientConfig.builder().controllerURI(URI.create(controllerUri)).build());
    }

    public KeyValueTable<String, String> createKVT(final KeyValueTableFactory factory) {
        return factory.forKeyValueTable(scopeName, new UTF8StringSerializer(), new UTF8StringSerializer(),
                KeyValueTableClientConfiguration.builder().build());
    }

}
