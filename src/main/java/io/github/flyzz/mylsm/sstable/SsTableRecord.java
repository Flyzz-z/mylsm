package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;

import java.util.Objects;

/** SSTable 中一个不可变的键与状态；Entry 保留值与删除标记的区别。 */
public record SsTableRecord(ByteKey key, Entry entry) {
    /** @throws NullPointerException 如果 key 或 entry 为 null */
    public SsTableRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
    }
}
