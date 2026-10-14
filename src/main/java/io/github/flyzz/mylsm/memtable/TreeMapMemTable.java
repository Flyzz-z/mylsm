package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;

import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** 基于 ByteKey 自然顺序的内存表；非线程安全。 */
public final class TreeMapMemTable implements MemTable {
    private final TreeMap<ByteKey, Entry> entries = new TreeMap<>();

    @Override
    public void put(ByteKey key, Value value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        entries.put(key, value);
    }

    @Override
    public Optional<Entry> get(ByteKey key) {
        Objects.requireNonNull(key, "key");
        // 表内不保存 null，因此 null 查找结果只表示“当前表未找到”。
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void delete(ByteKey key) {
        Objects.requireNonNull(key, "key");
        // 不能 remove：删除标记需要遮蔽未来磁盘层中可能存在的旧值。
        entries.put(key, Tombstone.INSTANCE);
    }
}
