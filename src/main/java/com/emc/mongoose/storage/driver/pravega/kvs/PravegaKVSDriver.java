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
import com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTClientCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.KVTFactoryCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.cache.ScopeCreateFunction;
import com.emc.mongoose.storage.driver.pravega.kvs.io.ByteBufferSerializer;
import com.emc.mongoose.storage.driver.pravega.kvs.io.DataItemSerializer;
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
import io.pravega.client.tables.TableEntry;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.nio.ByteBuffer;
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
    protected final int kvpKeyLength;
    protected final long controlApiTimeoutMillis;
    private final HashingKeyFunction<I> hashingKeyFunc;
    private final boolean controlScopeFlag;
    private final boolean controlKVTFlag;
    private final ScheduledExecutorService bgExecutor;
    private final AtomicInteger rrc = new AtomicInteger(0);
    private final boolean allowEmptyFamily;
    private final boolean useMultiKeyOperations;

    // caches allowing the lazy creation of the necessary things:
    private final Map<Controller, ScopeCreateFunction> scopeCreateFuncCache = new ConcurrentHashMap<>();
    private final Map<String, KVTCreateFunction> kvtCreateFuncCache = new ConcurrentHashMap<>();
    private final Map<ClientConfig, KVTFactoryCreateFunction> kvtFactoryCreateFuncCache = new ConcurrentHashMap<>();
    private final Map<KeyValueTableFactory, KVTClientCreateFunction> kvtClientCreateFuncCache = new ConcurrentHashMap<>();
    //TODO: describe why we introduce create functions not don't simple use a method (2 parameters)
    private final Map<String, URI> endpointCache = new ConcurrentHashMap<>();
    private final Map<URI, ClientConfig> clientConfigCache = new ConcurrentHashMap<>();
    private final Map<ClientConfig, Controller> controllerCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, KeyValueTableConfiguration>> scopeKVTsCache = new ConcurrentHashMap<>();
    private final Map<String, KeyValueTableFactory> kvtFactoryCache = new ConcurrentHashMap<>();
    private final Map<String, KeyValueTable> kvtCache = new ConcurrentHashMap<>();


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
                            "Scope \"{}\" was not created, likely created earlier", scopeName);
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
                .keyLength(kvpKeyLength)
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
                        Loggers.MSG.info(
                            "KVT \"{}\" was not created, likely created earlier", kvtName);
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

    @Value
    final class KVTClientForCreateOpCreateFunctionImpl
        implements KVTClientCreateFunction {

        KeyValueTableFactory kvtFactory;

        @Override
        public final KeyValueTable apply(final String kvtName) {
            val kvtConfig = KeyValueTableClientConfiguration.builder()
                .build();
            return kvtFactory.forKeyValueTableFixedKeyLength(kvtName, new UTF8StringSerializer(),
                new DataItemSerializer(false, false), kvtConfig);
        }
    }

    @Value
    final class KVTClientForReadOpCreateFunctionImpl<T>
        implements KVTClientCreateFunction {

        KeyValueTableFactory kvtFactory;

        @Override
        public final KeyValueTable apply(final String kvtName) {
            val kvtConfig = KeyValueTableClientConfiguration.builder()
                .build();
            return kvtFactory.forKeyValueTableFixedKeyLength(kvtName, new UTF8StringSerializer(), new ByteBufferSerializer(), kvtConfig);
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
        // TODO: pass 1 or batchSize depending on whether we work with a single KVP or with a batch
        super(stepId, dataInput, storageConfig, verifyFlag, batchSize);
        val driverConfig = storageConfig.configVal("driver");
        kvpKeyLength = driverConfig.intVal("kvp-key-length");
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
        val familyConfig = driverConfig.configVal("family");
        val createFamilyKeysConfig = familyConfig.configVal("key");
        val createFamilyKeys = createFamilyKeysConfig.boolVal("enabled");
        this.allowEmptyFamily = createFamilyKeysConfig.boolVal("allow-empty");
        // if empty family is allowed we add one more family key, so that
        // we could treat one value of family key as an empty key. E.g., key-family=0
        // is basically null key family
        val familyKeysAmount = allowEmptyFamily ? createFamilyKeysConfig.longVal("count") + 1 :
            createFamilyKeysConfig.longVal("count");
        this.hashingKeyFunc = createFamilyKeys ? new HashingKeyFunctionImpl<>(familyKeysAmount) : null;
        val multiKeyConfig = driverConfig.configVal("family-key");
        this.useMultiKeyOperations = multiKeyConfig.boolVal("enabled");
        if (useMultiKeyOperations) {
            Loggers.MSG.info("Running in multi-key mode");
            if (null == hashingKeyFunc) {
                throw new AssertionError("Multi key operations are only supported when key families are enabled");
            }
        }
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
                    return submitNoop(op);
                case CREATE:
                case UPDATE:
                    return submitCreate(op);
                case READ:
                    return submitRead(op);
                case DELETE:
                    return submitDelete(op);
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

    private boolean submitNoop(final O op) {
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

    private boolean submitMultiKeyNoop(final List<O> ops) {
        for (O op : ops) {
            op.startRequest();
            op.finishRequest();
            op.startResponse();
            try {
                op.countBytesDone(op.item().size());
            } catch (final IOException ignored) {
            }
            op.finishResponse();
            completeOperation(op, SUCC);
        }
        return true;
    }

    @Override
    protected final int submit(final List<O> ops, final int from, final int to)
        throws IllegalStateException {
        if (useMultiKeyOperations) {
            return submitMultiKey(ops, from, to);
        }

        val anyOp = ops.get(from);
        val opType = anyOp.type();
        var i = from;
        switch (opType) {
            case NOOP:
                for (; i < to && submitNoop(ops.get(i)); i++) ;
                return i - from;
            case CREATE:
            case UPDATE:
                for (; i < to && submitCreate(ops.get(i)); i++) ;
                return i - from;
            case READ:
                for (; i < to && submitRead(ops.get(i)); i++) ;
                return i - from;
            case DELETE:
                for (; i < to && submitDelete(ops.get(i)); i++) ;
                return i - from;
            default:
                throw new AssertionError("Unexpected operation type: " + opType);
        }
    }

    protected final int submitMultiKey(final List<O> ops, final int from, final int to)
        throws IllegalStateException {
        val anyOp = ops.get(from);
        val opType = anyOp.type();

        // we shouldn't throw away the whole batch. question mark.
        switch (opType) {
            case NOOP:
                submitMultiKeyNoop(ops);
                return to - from;
            case CREATE:
                submitMultiKeyCreate(ops);
                return to - from;
            case READ:
                // submitMultiKeyRead(ops);
                return to - from;
            default:
                throw new AssertionError("Unexpected operation type: " + opType);
        }
    }

    @Override
    protected final int submit(final List<O> ops)
        throws IllegalStateException {
        return useMultiKeyOperations ?
            submitMultiKey(ops, 0, ops.size()) :
            submit(ops, 0, ops.size());
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
        scopeKVTsCache.computeIfAbsent(scopeName, this::createInstanceCache).computeIfAbsent(kvtName, kvtCreateFunc);

        // create the kvt factory create function if necessary
        val kvtFactoryCreateFunc = kvtFactoryCreateFuncCache.computeIfAbsent(
            clientConfig,
            KVTFactoryCreateFunctionImpl::new);
        // create the kvt factory if necessary
        val kvtFactory = kvtFactoryCache.computeIfAbsent(scopeName, kvtFactoryCreateFunc);
        val kvtClientCreateFunc = kvtClientCreateFuncCache.computeIfAbsent(kvtFactory, KVTClientForCreateOpCreateFunctionImpl::new);
        //TODO: probably should be thread local
        KeyValueTable<String, I> kvt = kvtCache.computeIfAbsent(kvtName, kvtClientCreateFunc);
        val kvpKeyFamily = (null != hashingKeyFunc) ? hashingKeyFunc.apply(op.item()) : null;
        // TODO: see if StringBuilder faster
        // TODO: check how affects perf
        val kvpKey = op.item().name();
        if ((null == kvpKeyFamily) || (allowEmptyFamily && kvpKeyFamily.equals("0"))) {
            op.item().name("/" + kvpKey);
        } else {
            op.item().name(kvpKeyFamily + "/" + kvpKey);
        }
        try {
            val kvpValueSize = op.item().size();
            if (kvpValueSize > MAX_KVP_VALUE) {
                completeOperation(op, FAIL_IO);
                throw new AssertionError(stepId + ": KVP value size cannot exceed 1016KB ");
            } else if (kvpValueSize < 0) {
                completeOperation(op, FAIL_IO);
            } else {
                if (concurrencyThrottle.tryAcquire()) {
                    op.startRequest();
                    val kvtPutFuture = kvt.put(kvpKeyFamily, kvpKey, op.item());
                    try {
                        op.finishRequest();
                    } catch (final IllegalStateException ignored) {
                    }
                    kvtPutFuture.handle((version, thrown) -> handlePutFuture(op, thrown, kvpValueSize));
                } else {
                    return false;
                }
            }
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        return true;
    }

    protected Object handlePutFuture(final O op, final Throwable thrown, final long transferSize) {
        try {
            if (null != thrown) {
                completeOperation(op, FAIL_UNKNOWN);
            } else {
                op.startResponse();
                op.finishResponse();
                op.countBytesDone(transferSize);
                completeOperation(op, SUCC);
            }

        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }

    private boolean submitMultiKeyCreate(final List<O> ops) {
        val anyOp = ops.get(0);
        val nodeAddr = anyOp.nodeAddr();
        val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
        val kvtName = extractKVTName(anyOp.dstPath());
        val clientConfig = clientConfigCache.computeIfAbsent(endpointUri, this::createClientConfig);

        val controller = controllerCache.computeIfAbsent(clientConfig, this::createController);
        val scopeCreateFunc = scopeCreateFuncCache.computeIfAbsent(controller, ScopeCreateFunctionImpl::new);
        // create the scope if necessary
        val kvtCreateFunc = kvtCreateFuncCache.computeIfAbsent(scopeName, scopeCreateFunc);
        scopeKVTsCache.computeIfAbsent(scopeName, this::createInstanceCache).computeIfAbsent(kvtName, kvtCreateFunc);

        // create the kvt factory create function if necessary
        val kvtFactoryCreateFunc = kvtFactoryCreateFuncCache.computeIfAbsent(
            clientConfig,
            KVTFactoryCreateFunctionImpl::new);
        // create the kvt factory if necessary
        val kvtFactory = kvtFactoryCache.computeIfAbsent(scopeName, kvtFactoryCreateFunc);
        val kvtClientCreateFunc = kvtClientCreateFuncCache.computeIfAbsent(kvtFactory, KVTClientForCreateOpCreateFunctionImpl::new);
        KeyValueTable<String, I> kvt = kvtCache.computeIfAbsent(kvtName, kvtClientCreateFunc);
        val kvpKeyFamily = hashingKeyFunc.apply(anyOp.item());

        Map<String, I> pairs = new HashMap<>();
        for (O op : ops) {
            val kvpKey = op.item().name();
            pairs.put(kvpKey, op.item());
            op.item().name(kvpKeyFamily + "/" + kvpKey);
            try {
                val kvpValueSize = op.item().size();
                if (kvpValueSize > MAX_KVP_VALUE) {
                    Loggers.ERR.warn("{}: KVP value size cannot exceed 1016KB ", stepId);
                    completeOperation(op, FAIL_IO);
                } else if (kvpValueSize < 0) {
                    completeOperation(op, FAIL_IO);
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }
        
        if (concurrencyThrottle.tryAcquire()) {
            for (O op : ops) {
                op.startRequest();
            }
            val kvtPutFuture = kvt.putAll(kvpKeyFamily, pairs.entrySet());
            try {
                for (O op : ops) {
                    op.finishRequest();
                }
            } catch (final IllegalStateException ignored) {
            }
            kvtPutFuture.handle((version, thrown) -> handleMultiKeyPutFuture(ops, thrown));
        } else {
            return false;
        }
        return true;
    }

    protected Object handleMultiKeyPutFuture(final List<O> ops, final Throwable thrown) {
        try {
            if (null != thrown) {
                for (O op : ops) {
                    completeOperation(op, FAIL_UNKNOWN);
                }
            } else {
                // TODO: the larger the batch, the bigger latency for later ops. Any resolution?
                for (O op : ops) {
                    op.startResponse();
                    op.finishResponse();
                    try {
                        op.countBytesDone(op.item().size());
                    } catch (final IOException ignored) {
                    }
                    completeOperation(op, SUCC);
                }
            }
        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }


    private boolean submitRead(final O kvpOp) {
        val nodeAddr = kvpOp.nodeAddr();
        val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
        val clientConfig = clientConfigCache.computeIfAbsent(endpointUri, this::createClientConfig);
        // op.srcPath() looks like kvtName/kvtKeyFamily. But in case family is null we get "kvtName/".
        val kvtNameAndKeyFamily = kvpOp.srcPath().split("/");
        val kvtName = kvtNameAndKeyFamily[0];
        String kvpKeyFamily = null;
        if (kvtNameAndKeyFamily.length > 1) {
            kvpKeyFamily = kvtNameAndKeyFamily[1];
        }
        val kvpKey = kvpOp.item().name();

        // create the kvt factory create function if necessary
        val kvtFactoryCreateFunc = kvtFactoryCreateFuncCache.computeIfAbsent(
            clientConfig,
            KVTFactoryCreateFunctionImpl::new);
        // create the kvt factory if necessary
        val kvtFactory = kvtFactoryCache.computeIfAbsent(scopeName, kvtFactoryCreateFunc);
        val kvtClientCreateFunc = kvtClientCreateFuncCache.computeIfAbsent(
            kvtFactory,
            KVTClientForReadOpCreateFunctionImpl::new);
        KeyValueTable<String, ByteBuffer> kvTable = kvtCache.computeIfAbsent(kvtName, kvtClientCreateFunc);

        if (concurrencyThrottle.tryAcquire()) {
            kvpOp.startRequest();
            // read by key
            val tableEntryFuture = kvTable.get(kvpKeyFamily, kvpKey);
            try {
                kvpOp.finishRequest();
            } catch (final IllegalStateException ignored) {
            }
            tableEntryFuture.handle((tableEntry, thrown) -> handleGetFuture(kvpOp, tableEntry, thrown));
        } else {
            return false;
        }
        return true;
    }

    private Object handleGetFuture(final O op, final TableEntry<String, ByteBuffer> tableEntry, final Throwable thrown) {
        try {
            if (null != thrown) {
                completeOperation(op, FAIL_UNKNOWN);
            } else {
                op.startResponse();
                op.finishResponse();
                if (null == tableEntry) {
                    completeOperation(op, RESP_FAIL_NOT_FOUND);
                    Loggers.MSG.info(
                        "Key \"{}\" was not found", op.item().name());
                } else {
                    val bytesDone = tableEntry.getValue().remaining();
                    op.countBytesDone(bytesDone);
                    completeOperation(op, SUCC);
                }
            }
        } finally {
            concurrencyThrottle.release();
        }
        return null;
    }

    private boolean submitDelete(O op) {
        val nodeAddr = op.nodeAddr();
        val endpointUri = endpointCache.computeIfAbsent(nodeAddr, this::createEndpointUri);
        val kvtName = extractKVTName(op.dstPath());
        val clientConfig = clientConfigCache.computeIfAbsent(endpointUri, this::createClientConfig);

        val controller = controllerCache.computeIfAbsent(clientConfig, this::createController);
        val scopeCreateFunc = scopeCreateFuncCache.computeIfAbsent(controller, ScopeCreateFunctionImpl::new);
        // create the scope if necessary
        val kvtCreateFunc = kvtCreateFuncCache.computeIfAbsent(scopeName, scopeCreateFunc);
        scopeKVTsCache.computeIfAbsent(scopeName, this::createInstanceCache).computeIfAbsent(kvtName, kvtCreateFunc);

        // create the kvt factory create function if necessary
        val kvtFactoryCreateFunc = kvtFactoryCreateFuncCache.computeIfAbsent(
            clientConfig,
            KVTFactoryCreateFunctionImpl::new);
        // create the kvt factory if necessary
        val kvtFactory = kvtFactoryCache.computeIfAbsent(scopeName, kvtFactoryCreateFunc);
        val kvtClientCreateFunc = kvtClientCreateFuncCache.computeIfAbsent(kvtFactory, KVTClientForCreateOpCreateFunctionImpl::new);
        //TODO: probably should be thread local
        KeyValueTable<String, I> kvt = kvtCache.computeIfAbsent(kvtName, kvtClientCreateFunc);
        val kvpKeyFamily = (null != hashingKeyFunc) ? hashingKeyFunc.apply(op.item()) : null;
        // TODO: see if StringBuilder faster
        // TODO: check how affects perf
        val kvpKey = op.item().name();
        if ((null == kvpKeyFamily) || (allowEmptyFamily && kvpKeyFamily.equals("0"))) {
            op.item().name("/" + kvpKey);
        } else {
            op.item().name(kvpKeyFamily + "/" + kvpKey);
        }
        try {
            val kvpValueSize = op.item().size();
            if (kvpValueSize > MAX_KVP_VALUE) {
                completeOperation(op, FAIL_IO);
                throw new AssertionError(stepId + ": KVP value size cannot exceed 1016KB ");
            } else if (kvpValueSize < 0) {
                completeOperation(op, FAIL_IO);
            } else {
                if (concurrencyThrottle.tryAcquire()) {
                    op.startRequest();
                    val kvtDeleteFuture = kvt.remove(kvpKeyFamily, kvpKey);
                    try {
                        op.finishRequest();
                    } catch (final IllegalStateException ignored) {
                    }
                    kvtDeleteFuture.handle((Void, thrown) -> handleDeleteFuture(op, thrown, kvpValueSize));
                } else {
                    return false;
                }
            }
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        return true;

    }

    protected Object handleDeleteFuture(final O op, final Throwable thrown, final long transferSize) {
        try {
            if (null != thrown) {
                completeOperation(op, FAIL_UNKNOWN);
            }
            op.startResponse();
            op.finishResponse();
            op.countBytesDone(transferSize);
            completeOperation(op, SUCC);
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
        kvtClientCreateFuncCache.clear();
        scopeCreateFuncCache.clear();
        kvtCreateFuncCache.clear();
        kvtFactoryCreateFuncCache.clear();

        endpointCache.clear();
        closeAllWithTimeout(kvtCache.values());
        kvtCache.clear();
        scopeKVTsCache.values().forEach(Map::clear);
        scopeKVTsCache.clear();
        closeAllWithTimeout(kvtFactoryCache.values());
        kvtFactoryCache.clear();
        clientConfigCache.clear();
        closeAllWithTimeout(controllerCache.values());
        controllerCache.clear();
        endpointCache.clear();
        bgExecutor.shutdownNow();
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

    private static String extractKVFName(final String itemName) {
        // TODO
        return "";
    }
}
