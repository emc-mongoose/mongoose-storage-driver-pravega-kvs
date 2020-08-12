package com.emc.mongoose.storage.driver.pravega.kvs;

import com.emc.mongoose.base.item.DataItem;
import java.util.function.Function;

public interface HashingKeyFunction<I extends DataItem>
				extends Function<I, String> {

	long period();
}
