package com.emc.mongoose.storage.driver.pravega.kvs;

import com.emc.mongoose.base.item.DataItem;
import lombok.Value;

@Value
public class HashingKeyFunctionImpl<I extends DataItem>
				implements HashingKeyFunction<I> {

	long period;

	@Override
	public final String apply(final I kvpItem) {
		return Long.toString(period > 0 ? kvpItem.offset() % period : kvpItem.offset(), Character.MAX_RADIX);
	}

	@Override
	public final long period() {
		return period;
	}
}
