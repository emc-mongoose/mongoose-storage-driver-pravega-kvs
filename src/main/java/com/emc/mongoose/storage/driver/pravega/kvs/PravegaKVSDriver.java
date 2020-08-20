package com.emc.mongoose.storage.driver.pravega.kvs;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogContextThreadFactory;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTFactoryCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.ScopeCreateFunction;
import com.github.akurilov.confuse.Config;
import io.pravega.client.ClientConfig;
import io.pravega.client.KeyValueTableFactory;
import io.pravega.client.control.impl.Controller;
import io.pravega.client.control.impl.ControllerImpl;
import io.pravega.client.control.impl.ControllerImplConfig;
import io.pravega.client.stream.impl.UTF8StringSerializer;
import io.pravega.client.tables.KeyValueTable;
import io.pravega.client.tables.KeyValueTableClientConfiguration;
import io.pravega.client.tables.KeyValueTableConfiguration;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.emc.mongoose.base.item.op.Operation.SLASH;
import static com.emc.mongoose.base.item.op.Operation.Status.*;
import static com.emc.mongoose.storage.driver.pravega.kvs.PravegaKVSConstants.MAX_BACKOFF_MILLIS;
import static com.emc.mongoose.storage.driver.pravega.kvs.PravegaKVSConstants.MAX_KVP_VALUE;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class PravegaKVSDriver<I extends DataItem, O extends DataOperation<I>>
    extends CoopStorageDriverBase<I, O> {

    protected final String uriSchema;
    protected final String scopeName;
    protected final String[] endpointAddrs;
    protected final int nodePort;
    protected final int maxConnectionsPerSegmentstore;
    protected final int partitionCount;
    protected final long controlApiTimeoutMillis;
    private final HashingKeyFunction<I> hashingKeyFunc;
    private final boolean controlScopeFlag;
    private final boolean controlKVTFlag;
    private final ScheduledExecutorService bgExecutor;
    private final AtomicInteger rrc = new AtomicInteger(0);

    // caches allowing the lazy creation of the necessary things:
    // * endpoints
    private final Map<String, URI> endpointCache = new ConcurrentHashMap<>();
    private final Map<URI, ClientConfig> clientConfigCache = new ConcurrentHashMap<>();
    // * controllers
    private final Map<ClientConfig, Controller> controllerCache = new ConcurrentHashMap<>();
    // * scopes
    private final Map<Controller, ScopeCreateFunction> scopeCreateFuncCache = new ConcurrentHashMap<>();
    private final Map<String, KVTCreateFunction> kvtCreateFuncCache = new ConcurrentHashMap<>();
    // * streams
    private final Map<String, Map<String, KeyValueTableConfiguration>> scopeKVTsCache = new ConcurrentHashMap<>();
    // * kvt factories
    private final Map<ClientConfig, KVTFactoryCreateFunction> kvtFactoryCreateFuncCache = new ConcurrentHashMap<>();
    private final Map<String, KeyValueTableFactory> kvtFactoryCache = new ConcurrentHashMap<>();


    @Value
    final class ScopeCreateFunctionImpl
        implements ScopeCreateFunction {

        Controller controller;

        @Override
        public final KVTCreateFunction apply(final String scopeName) {
            if (controlScopeFlag) {
                try {
                    if (controller.createScope(scopeName).get(controlApiTimeoutMillis, MILLISECONDS)) {
                        Loggers.MSG.trace("Scope \"{}\" was created", scopeName);
                    } else {
                        Loggers.MSG.info(
                            "Scope \"{}\" was not created, may be already existing before", scopeName);
                    }
                } catch (final InterruptedException e) {
                    throwUnchecked(e);
                } catch (final Throwable cause) {
                    LogUtil.exception(
                        Level.WARN, cause, "{}: failed to create the scope \"{}\"", stepId, scopeName
                    );
                }
            }
            return new KVTCreateFunctionImpl(controller, scopeName);
        }
    }

    @Value
    final class KVTCreateFunctionImpl
        implements KVTCreateFunction {

        Controller controller;
        String scopeName;

        @Override
        public final KeyValueTableConfiguration apply(final String kvtName) {
            KeyValueTableConfiguration kvtConfig = KeyValueTableConfiguration.builder()
                .partitionCount(partitionCount)
                .build();
            if (controlKVTFlag) {
                try {
                    val createKVTFuture = controller.createKeyValueTable(scopeName, kvtName, kvtConfig);
                    if (createKVTFuture.get(controlApiTimeoutMillis, MILLISECONDS)) {
                        Loggers.MSG.trace(
                            "KVT \"{}/{}\" was created using the config: {}",
                            scopeName,
                            kvtName,
                            kvtConfig);
                    } else {
                        //TODO: once it is supported
                        //scaleToPartitionCount(
                        //        controller, controlApiTimeoutMillis, scopeName, kvtName, scalingPolicy);
                    }
                } catch (final InterruptedException e) {
                    throwUnchecked(e);
                } catch (final Throwable cause) {
                    LogUtil.exception(
                        Level.WARN, cause, "{}: failed to create the KVT \"{}\"", stepId, kvtName);
                }
            }
            return kvtConfig;
        }
    }

    @Value
    final class KVTFactoryCreateFunctionImpl
        implements KVTFactoryCreateFunction {

        ClientConfig clientConfig;

        @Override
        public final KeyValueTableFactory apply(final String scopeName) {
            return KeyValueTableFactory.withScope(scopeName, clientConfig);
        }
    }


    private Controller createController(final ClientConfig clientConfig) {
        val controllerConfig = ControllerImplConfig
            .builder()
            .clientConfig(clientConfig)
            .maxBackoffMillis(MAX_BACKOFF_MILLIS)
            .build();
        return new ControllerImpl(controllerConfig, bgExecutor);
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
        this.controlKVTFlag = controlConfig.boolVal("kvt");
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
        val createHashingKeysConfig = hashingConfig.configVal("key");
        val createHashingKeys = createHashingKeysConfig.boolVal("enabled");
        val createHashingKeysPeriod = createHashingKeysConfig.longVal("count");
        this.hashingKeyFunc = createHashingKeys ? new HashingKeyFunctionImpl<>(createHashingKeysPeriod) : null;
        this.endpointAddrs = endpointAddrList.toArray(new String[endpointAddrList.size()]);
        this.requestAuthTokenFunc = null; // do not use
        this.requestNewPathFunc = null; // do not use
        val scalingConfig = driverConfig.configVal("scaling");
        this.partitionCount = scalingConfig.intVal("partitions");
        this.bgExecutor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            new LogContextThreadFactory(toString(), true));
    }

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


    private <K, V> Map<K, V> createInstanceCache(final Object ignored) {
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
        if (null != lastPrevItem) {
            throw new EOFException();
        }
        // as we don't know how many items in the kvt, we allocate memory for 1 batch of ops
        return makeItems(itemFactory, path, prefix, count);
    }

    private List<I> makeItems(
        final ItemFactory<I> itemFactory, final String path, final String prefix, final int count) throws EOFException {

        val items = new ArrayList<I>();
        for (var i = 0; i < count; i++) {
            items.add(itemFactory.getItem(path + SLASH + (prefix == null ? i : prefix + i), 0, 0));
        }
        return items;
    }

    @Override
    protected boolean prepare(final O operation) {
        if (super.prepare(operation)) {
            var endpointAddr = operation.nodeAddr();
            if (endpointAddr == null) {
                endpointAddr = nextEndpointAddr();
                operation.nodeAddr(endpointAddr);
            }
            return true;
        }
        return false;
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
                    submitRead(op);
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
            } else LogUtil.exception(Level.DEBUG, Objects.requireNonNullElse(cause, e), "Unexpected failure");
        }
        return false;
    }

    private boolean noop(final O op) {
        op.startRequest();
        op.finishRequest();
        op.startResponse();
        try {
            op.countBytesDone(op.item().size());
        } catch (final IOException ignored) {
        }
        op.finishResponse();
        completeOperation(op, SUCC);
        return true;
    }

    @Override
    protected final int submit(final List<O> ops, final int from, final int to)
        throws IllegalStateException {
        val anyOp = ops.get(from);
        val opType = anyOp.type();
        switch (opType) {
            case NOOP:
                for (var i = from; i < to && noop(ops.get(i)); i++) ;
                return to - from;
            case CREATE:
                for (var i = from; i < to && submitCreate(ops.get(i)); i++) ;
                return to - from;
            case READ:
                for (var i = from; i < to && submitRead(ops.get(i)); i++) ;
                return to - from;
            default:
                throw new AssertionError("Unexpected operation type: " + opType);
        }
    }

    @Override
    protected final int submit(final List<O> ops)
        throws IllegalStateException {
        return submit(ops, 0, ops.size());
    }

    private boolean submitCreate(O op) {
        val nodeAddr = op.nodeAddr();
        val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
        val kvtName = extractKVTName(op.dstPath());
        val clientConfig = clientConfigCache.computeIfAbsent(endpointUri, this::createClientConfig);
        val controller = controllerCache.computeIfAbsent(clientConfig, this::createController);
        val scopeCreateFunc = scopeCreateFuncCache.computeIfAbsent(controller, ScopeCreateFunctionImpl::new);
        // create the scope if necessary

        val kvtCreateFunc = kvtCreateFuncCache.computeIfAbsent(scopeName, scopeCreateFunc);
        scopeKVTsCache
            .computeIfAbsent(scopeName, this::createInstanceCache)
            .computeIfAbsent(kvtName, kvtCreateFunc);
        // create the kvt factory create function if necessary
        val kvtFactoryCreateFunc = kvtFactoryCreateFuncCache.computeIfAbsent(
            clientConfig,
            KVTFactoryCreateFunctionImpl::new);
        // create the kvt factory if necessary
        val kvtFactory = kvtFactoryCache.computeIfAbsent(scopeName, kvtFactoryCreateFunc);

        //KeyValueTableManagerImpl kvtManager = new KeyValueTableManagerImpl(clientConfig);
//        KeyValueTableConfiguration kvtConfig = KeyValueTableConfiguration.builder()
//                .partitionCount(partitionCount)
//                .build();
        //kvtManager.createKeyValueTable(scopeName, kvtName, kvtConfig);
        //controller.createKeyValueTable(scopeName, keyValueTableName, config)
        //val factory = KeyValueTableFactory.withScope(scopeName, clientConfig);
        KeyValueTable<String, String> kvt = kvtFactory.forKeyValueTable(kvtName, new UTF8StringSerializer(), new UTF8StringSerializer(),
            KeyValueTableClientConfiguration.builder().build());

        //KeyValueTableInfo kvtInfo = new KeyValueTableInfo(scopeName, kvtName);
        try {
            val kvpValueSize = op.item().size();
            if (kvpValueSize > MAX_KVP_VALUE) {
                completeOperation(op, FAIL_IO);
            } else if (kvpValueSize < 0) {
                completeOperation(op, FAIL_IO);
            } else {
                if (concurrencyThrottle.tryAcquire()) {
                    op.startRequest();
                    val kvtPutFuture = kvt.put(null, "a", "b"); // first parameter is key family
                    try {
                        op.finishRequest();
                    } catch (final IllegalStateException ignored) {
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
                completeOperation(op, SUCC);
            } else {
                completeOperation(op, FAIL_UNKNOWN);
            }
        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }


    private boolean submitRead(final O kvpOp) {
        try {
            val nodeAddr = kvpOp.nodeAddr();
            val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
            val kvtName = extractKVTName(kvpOp.srcPath());

            val kvtUtils = new KVTUtilsImpl(endpointUri, this.scopeName, kvtName);
            val kvTable = kvtUtils.KVT();

            // read by key
            val tableEntryFuture = kvTable.get("", "");
            val kvp = tableEntryFuture.get(controlApiTimeoutMillis, MILLISECONDS);

            if (kvp == null) { // can kvp be null ???
                //completeOperation(kvpOp, ...);
            } else if (concurrencyThrottle.tryAcquire()) {
                kvpOp.startRequest();
                try {
                    kvpOp.finishRequest();
                } catch (final IllegalStateException ignored) {
                }
                val bytesDone = new UTF8StringSerializer().serialize(kvp.getValue()).remaining();
                tableEntryFuture.handle((tableEntry, thrown) -> handleGetFuture(kvpOp, thrown, bytesDone));
            }
        } catch (final InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }
        return true;
    }

    private Object handleGetFuture(final O op, final Throwable thrown, final long transferSize) {
        try {
            if (null == thrown) {
                op.startResponse();
                op.finishResponse();
                op.countBytesDone(transferSize);
                completeOperation(op, SUCC);
            } else {
                completeOperation(op, FAIL_UNKNOWN);
            }
        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }

    private void completeOperation(final O op, final Operation.Status status) {
        op.status(status);
        handleCompleted(op);
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

    private static String extractKVTName(final String itemPath) {
        String kvtName = itemPath;
        if (kvtName.startsWith(SLASH)) {
            kvtName = kvtName.substring(1);
        }
        if (kvtName.endsWith(SLASH) && kvtName.length() > 1) {
            kvtName = kvtName.substring(0, kvtName.length() - 1);
        }
        return kvtName;
    }
}
