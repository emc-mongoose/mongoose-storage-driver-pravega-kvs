package com.emc.mongoose.storage.driver.pravega.kvs;

public interface PravegaKVSConstants {

	String DRIVER_NAME = "pravega-kvs";
	int KIB = 0x400;
	int MAX_BACKOFF_MILLIS = 5_000;
	int MAX_KVP_VALUE = 1016 * KIB; // 1MB - 8KB for keys
}
