package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;

import java.util.Objects;

/** 一次不可变的 WAL 操作；Value 表示 PUT，Tombstone 表示 DELETE。 */
public record WalRecord(ByteKey key, Entry entry) {
    /** @throws NullPointerException 如果 key 或 entry 为 null */
    public WalRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
    }
}
