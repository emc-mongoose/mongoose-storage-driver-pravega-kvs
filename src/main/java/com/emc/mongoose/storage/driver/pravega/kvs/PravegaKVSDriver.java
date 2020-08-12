package com.emc.mongoose.storage.driver.pravega.kvs;

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
import com.github.akurilov.confuse.Config;
import io.pravega.client.ClientConfig;
import lombok.val;
import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class PravegaKVSDriver<I extends DataItem, O extends DataOperation<I>>
        extends CoopStorageDriverBase<I, O> {

    protected final String uriSchema;
    protected final String scopeName;
    protected final String[] endpointAddrs;
    protected final int nodePort;
    protected final int maxConnectionsPerSegmentstore;
    protected final long controlApiTimeoutMillis;
    private final HashingKeyFunction<I> hashingKeyFunc;
    private final boolean controlScopeFlag;
    private final AtomicInteger rrc = new AtomicInteger(0);

    // caches allowing the lazy creation of the necessary things:
    // * endpoints
    private final Map<String, URI> endpointCache = new ConcurrentHashMap<>();

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
        val createHashingKeysConfig = hashingConfig.configVal("key");
        val createHashingKeys = createHashingKeysConfig.boolVal("enabled");
        val createHashingKeysPeriod = createHashingKeysConfig.longVal("count");
        this.hashingKeyFunc = createHashingKeys ? new HashingKeyFunctionImpl<>(createHashingKeysPeriod) : null;
        this.endpointAddrs = endpointAddrList.toArray(new String[endpointAddrList.size()]);
        this.requestAuthTokenFunc = null; // do not use
        this.requestNewPathFunc = null; // do not use
        val connConfig = nodeConfig.configVal("conn");

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

                    break;
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

    @Override
    protected int submit(List<O> ops, int from, int to) throws IllegalStateException {
        return 0;
    }

    @Override
    protected int submit(List<O> ops) throws IllegalStateException {
        return 0;
    }


    private boolean submitRead(O op) {
        return true;
    }

    private void readKVP(final O evtOp)
            throws IOException {
        evtOp.startRequest();
        evtOp.finishRequest();

        val kvtUtils = new KVTUtilsImpl("", "");
        val kvtFactory = kvtUtils.createKVTFactory();
        val kvTable = kvtUtils.createKVT(kvtFactory);

        // read by key
        val tableEntry = kvTable.get("", "");

        // read by keys
        val listTableEntries = kvTable.getAll("", null /*iterator*/);

        // read by KF
        val iterator = kvTable.entryIterator("" /*family*/, 1 /*maxBatchSize*/ , null /*state*/);

        // -----------------------
//        var evtRead_ = evtReader.readNextEvent(evtOpTimeoutMillis);
//        while (evtRead_.isCheckpoint()) {
//            Loggers.MSG.debug("{}: stream checkpoint @ position {}", stepId, evtRead_.getPosition());
//            evtRead_ = evtReader.readNextEvent(evtOpTimeoutMillis);
//        }
//        evtOp.startResponse();
//        evtOp.finishResponse();
//        val evtRead = evtRead_;
//        val evtData = evtRead.getEvent();
//        if (e2eReadModeFlag) {
//            val timestampBuffer = ByteBuffer.allocate(TIMESTAMP_LENGTH);
//            timestampBuffer.put(evtData.array(), 0, TIMESTAMP_LENGTH);
//            timestampBuffer.flip();
//            val e2eTimeMillis = System.currentTimeMillis() - timestampBuffer.getLong();
//            val msgId = evtOp.item().name();
//            val msgSize = evtOp.item().size();
//            if(e2eTimeMillis > 0) {
//                Loggers.OP_TRACES.info(new EndToEndLogMessage(msgId, msgSize, e2eTimeMillis));
//            } else {
//                Loggers.ERR.warn("{}: publish time is in the future for the message \"{}\"", stepId, msgId);
//            }
//        }
//
//        if (null == evtData) {
//            val streamPos = evtRead.getPosition();
//            if (((PositionImpl)streamPos).getOwnedSegments().isEmpty()) {
//                // means that reader doesn't own any segments, so it can't read anything
//                Loggers.MSG.debug("{}: empty reader. No EventSegmentReader assigned", stepId);
//            }
//            // received an empty answer, so don't count the operation anywhere and just do the recycling
//            completeOperation(evtOp, PENDING);
//        } else {
//            val bytesDone = evtData.remaining();
//            val evtItem = evtOp.item();
//            evtItem.size(bytesDone);
//            evtOp.countBytesDone(evtItem.size());
//            completeOperation(evtOp, SUCC);
//        }
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
