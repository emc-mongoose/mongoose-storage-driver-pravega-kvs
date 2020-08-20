package com.emc.mongoose.storage.driver.pravega.kvs;

import static com.emc.mongoose.base.Constants.MIB;

public interface PravegaKVSConstants {

	String DRIVER_NAME = "pravega-kvs";

	int MAX_BACKOFF_MILLIS = 5_000;
	int MAX_KVP_VALUE = 1 * MIB;
}
