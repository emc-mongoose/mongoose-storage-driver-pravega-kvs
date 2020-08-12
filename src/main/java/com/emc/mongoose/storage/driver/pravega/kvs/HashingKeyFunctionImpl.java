package com.emc.mongoose.storage.driver.pravega.kvs;

import com.emc.mongoose.base.item.DataItem;
import lombok.Value;

@Value
public class HashingKeyFunctionImpl<I extends DataItem>
				implements HashingKeyFunction<I> {

	long period;

	@Override
	public final String apply(final I evtItem) {
		return Long.toString(period > 0 ? evtItem.offset() % period : evtItem.offset(), Character.MAX_RADIX);
	}

	@Override
	public final long period() {
		return period;
	}
}
