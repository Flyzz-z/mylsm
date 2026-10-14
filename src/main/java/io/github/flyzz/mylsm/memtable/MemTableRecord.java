package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;

import java.util.Objects;

/** 固定的键与记录状态配对；两者均不可变，可以安全共享引用。 */
public record MemTableRecord(ByteKey key, Entry entry) {
    /** @throws NullPointerException 如果 key 或 entry 为 null */
    public MemTableRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
    }
}
