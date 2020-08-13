package com.emc.mongoose.storage.driver.pravega.kvs;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.emc.mongoose.base.item.op.OpType.NOOP;
import static com.emc.mongoose.base.item.op.Operation.SLASH;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_TIMEOUT;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.INTERRUPTED;
import static com.emc.mongoose.base.item.op.Operation.Status.PENDING;
import static com.emc.mongoose.base.item.op.Operation.Status.RESP_FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.storage.driver.pravega.kvs.PravegaKVSConstants.DRIVER_NAME;
import static com.emc.mongoose.storage.driver.pravega.kvs.PravegaKVSConstants.MAX_BACKOFF_MILLIS;

import static com.emc.mongoose.storage.driver.pravega.kvs.PravegaKVSConstants.MAX_KVP_VALUE;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.emc.mongoose.base.item.op.Operation.Status;

import com.github.akurilov.commons.system.DirectMemUtil;
import com.github.akurilov.confuse.Config;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;


import io.pravega.client.ClientConfig;
import io.pravega.client.KeyValueTableFactory;
import io.pravega.client.admin.KeyValueTableManager;
import io.pravega.client.admin.impl.KeyValueTableManagerImpl;
import io.pravega.client.stream.impl.UTF8StringSerializer;
import io.pravega.client.tables.KeyValueTable;
import io.pravega.client.tables.KeyValueTableClientConfiguration;
import io.pravega.client.tables.KeyValueTableConfiguration;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;

public class PravegaKVSDriver<I extends DataItem, O extends DataOperation<I>>
        extends CoopStorageDriverBase<I, O> {

    protected final String uriSchema;
    protected final String scopeName;
    protected final String[] endpointAddrs;
    protected final int nodePort;
    protected final int maxConnectionsPerSegmentstore;
    protected final long controlApiTimeoutMillis;
    protected final int partitionCount;
    private final RoutingKeyFunction<I> routingKeyFunc;
    private final boolean controlScopeFlag;
    private final AtomicInteger rrc = new AtomicInteger(0);

    // caches allowing the lazy creation of the necessary things:
    // * endpoints
    private final Map<String, URI> endpointCache = new ConcurrentHashMap<>();

    ClientConfig createClientConfig(final URI endpointUri) {
        val clientConfigBuilder = ClientConfig
                .builder()
                .controllerURI(endpointUri)
                .maxConnectionsPerSegmentStore(maxConnectionsPerSegmentstore);
        /*if(null != cred) {
            clientConfigBuilder.credentials(cred);
        }*/
        return clientConfigBuilder.build();
    }

    PravegaKVSDriver(
            final String stepId,
            final DataInput dataInput,
            final Config storageConfig,
            final boolean verifyFlag,
            final int batchSize
    ) throws IllegalConfigurationException, IllegalArgumentException {
        super(stepId, dataInput, storageConfig, verifyFlag, batchSize); //TODO: pass 1 or batchSize depending on
        //whether we work with a single KVP or with a batch
        val driverConfig = storageConfig.configVal("driver");
        val createConfig = driverConfig.configVal("create");
        val controlConfig = driverConfig.configVal("control");
        this.controlApiTimeoutMillis = controlConfig.longVal("timeoutMillis");
        this.controlScopeFlag = controlConfig.boolVal("scope");

        val netConfig = storageConfig.configVal("net");
        this.uriSchema = netConfig.stringVal("uri-schema");
        this.scopeName = storageConfig.stringVal("namespace");
        this.maxConnectionsPerSegmentstore = netConfig.intVal("maxConnPerSegmentstore");
        if (scopeName == null || scopeName.isEmpty()) {
            Loggers.ERR.warn("Scope name not set, use the \"storage-namespace\" configuration option");
        }
        val nodeConfig = netConfig.configVal("node");
        this.nodePort = nodeConfig.intVal("port");
        val endpointAddrList = nodeConfig.listVal("addrs");
        val hashingConfig = driverConfig.configVal("hashing");
        val createRoutingKeysConfig = hashingConfig.configVal("key");
        val createRoutingKeys = createRoutingKeysConfig.boolVal("enabled");
        val createRoutingKeysPeriod = createRoutingKeysConfig.longVal("count");
        this.routingKeyFunc = createRoutingKeys ? new RoutingKeyFunctionImpl<>(createRoutingKeysPeriod) : null;
        this.endpointAddrs = endpointAddrList.toArray(new String[endpointAddrList.size()]);
        this.requestAuthTokenFunc = null; // do not use
        this.requestNewPathFunc = null; // do not use
        val scalingConfig = storageConfig.configVal("scaling");
        this.partitionCount = scalingConfig.intVal("partitions");
    }


    String nextEndpointAddr() {
        return endpointAddrs[rrc.getAndIncrement() % endpointAddrs.length];
    }

    URI createEndpointUri(final String nodeAddr) {
        try {
            final String addr;
            final int port;
            val portSepPos = nodeAddr.lastIndexOf(':');
            if (portSepPos > 0) {
                addr = nodeAddr.substring(0, portSepPos);
                port = Integer.parseInt(nodeAddr.substring(portSepPos + 1));
            } else {
                addr = nodeAddr;
                port = nodePort;
            }
            val uid = credential == null ? null : credential.getUid();
            return new URI(uriSchema, uid, addr, port, "/", null, null);
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }


    <K, V> Map<K, V> createInstanceCache(final Object ignored) {
        return new ConcurrentHashMap<>();
    }

    /**
     * Not used in this driver implementation
     */
    @Override
    protected String requestNewPath(final String path) {
        throw new AssertionError("Should not be invoked");
    }

    /**
     * Not used in this driver implementation
     */
    @Override
    protected String requestNewAuthToken(final Credential credential) {
        throw new AssertionError("Should not be invoked");
    }

    @Override
    public List<I> list(
            final ItemFactory<I> itemFactory,
            final String path,
            final String prefix,
            final int idRadix,
            final I lastPrevItem,
            final int count)
            throws EOFException {
        return null;
    }


    @Override
    protected boolean prepare(final O operation) {
        super.prepare(operation);
        var endpointAddr = operation.nodeAddr();
        if (endpointAddr == null) {
            endpointAddr = nextEndpointAddr();
            operation.nodeAddr(endpointAddr);
        }
        return true;
    }

    /**
     * Not used in this driver implementation
     */
    @Override
    public void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
    }


    @Override
    protected boolean submit(O op) throws IllegalStateException {
        final OpType opType = op.type();
        try {
            switch (opType) {
                case NOOP:

                    break;
                case CREATE:
                    return submitCreate(op);
                case READ:

                    break;
                case UPDATE:

                    break;
                case DELETE:

                    break;
                case LIST:
                default:
                    throw new AssertionError("\"" + opType + "\" operation isn't implemented");
            }
        } catch (final RuntimeException e) {
            final Throwable cause = e.getCause();

            if (cause instanceof IOException) {
                LogUtil.exception(
                        Level.DEBUG, cause, "Failed open the file: {}"
                );
            } else if (cause != null) {
                LogUtil.exception(Level.DEBUG, cause, "Unexpected failure");
            } else {
                LogUtil.exception(Level.DEBUG, e, "Unexpected failure");
            }
        }
        return false;
    }

    private boolean submitCreate(O op) {
        val nodeAddr = op.nodeAddr();
        val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
        val kvtName = extractKVTName(op.dstPath());
        ClientConfig clientConfig = createClientConfig(endpointUri);
        KeyValueTableManagerImpl kvtManager = new KeyValueTableManagerImpl(clientConfig);
        KeyValueTableConfiguration kvtConfig = KeyValueTableConfiguration.builder()
                .partitionCount(partitionCount)
                .build();
        //kvtManager.createKeyValueTable(scopeName, kvtName, kvtConfig);
        val factory = KeyValueTableFactory.withScope(scopeName, clientConfig);
        KeyValueTable<String, String> kvt = factory.forKeyValueTable(kvtName, new UTF8StringSerializer(), new UTF8StringSerializer(),
                KeyValueTableClientConfiguration.builder().build());

        //KeyValueTableInfo kvtInfo = new KeyValueTableInfo(scopeName, kvtName);
        try {
            val kvpValueSize = op.item().size();
            if (kvpValueSize > MAX_KVP_VALUE) {
                failOperation(op, FAIL_IO);
            } else if (kvpValueSize < 0) {
                failOperation(op, FAIL_IO);
            } else {
                if (concurrencyThrottle.tryAcquire()) {
                    op.startRequest();
                    val kvtPutFuture = kvt.put(null, "a", "b"); // first parameter is key family
                    try {
                        op.finishRequest();
                    } catch(final IllegalStateException ignored) {
                    }
                    kvtPutFuture.handle((version, thrown) -> handlePutFuture(op, thrown, kvpValueSize));
                }
            }
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        return true;
    }

    protected Object handlePutFuture(final O op, final Throwable thrown, final long transferSize) {
        try {
            if (null == thrown) {
                op.startResponse();
                op.finishResponse();
                op.countBytesDone(transferSize);
                op.status(SUCC);
                handleCompleted(op);
            } else {
                failOperation(op, FAIL_UNKNOWN);
            }
        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }

    protected final void failOperation(final O op, final Status status) {
        op.status(status);
        handleCompleted(op);
    }

    @Override
    protected int submit(List<O> ops, int from, int to) throws IllegalStateException {
        return 0;
    }

    @Override
    protected int submit(List<O> ops) throws IllegalStateException {
        return 0;
    }

    static String extractKVTName(final String itemPath) {
        String kvtName = itemPath;
        if (kvtName.startsWith(SLASH)) {
            kvtName = kvtName.substring(1);
        }
        if (kvtName.endsWith(SLASH) && kvtName.length() > 1) {
            kvtName = kvtName.substring(0, kvtName.length() - 1);
        }
        return kvtName;
    }

    @Override
    protected void doClose()
            throws IOException {
        super.doClose();
        // clear all caches & pools
        endpointCache.clear();
    }

    void closeAllWithTimeout(final Collection<? extends AutoCloseable> closeables) {
        if (null != closeables && closeables.size() > 0) {
            final ExecutorService closeExecutor = Executors.newFixedThreadPool(closeables.size());
            closeables.forEach(
                    closeable -> closeExecutor.submit(
                            () -> {
                                try {
                                    closeable.close();
                                } catch (final Exception e) {
                                    throwUncheckedIfInterrupted(e);
                                    LogUtil.exception(
                                            Level.WARN,
                                            e,
                                            "{}: storage driver failed to close \"{}\"",
                                            stepId,
                                            closeable);
                                }
                            }));
            try {
                if (!closeExecutor.awaitTermination(controlApiTimeoutMillis, MILLISECONDS)) {
                    Loggers.ERR.warn(
                            "{}: storage driver timeout while closing one of \"{}\"",
                            stepId,
                            closeables.stream().findFirst().get().getClass().getSimpleName());
                }
            } catch (final InterruptedException e) {
                throwUnchecked(e);
            } finally {
                closeExecutor.shutdownNow();
            }
        }
    }

    @Override
    public String toString() {
        return String.format(super.toString(), PravegaKVSConstants.DRIVER_NAME);
    }
}
