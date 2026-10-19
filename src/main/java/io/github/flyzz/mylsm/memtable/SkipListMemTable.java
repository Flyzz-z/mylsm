package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * 跳表内存表的读写核心，非线程安全。
 * 当前仅在包内使用；后续完成快照后再实现完整 MemTable 契约。
 */
final class SkipListMemTable {
    private static final int MAX_HEIGHT = 32;

    private final Node head = new Node(null, null, MAX_HEIGHT);
    private final IntSupplier heightSupplier;
    private int height = 1;
    private int size;

    SkipListMemTable() {
        this(SkipListMemTable::randomHeight);
    }

    /** 包内测试入口；仅新键使用高度生成器，合法高度为 1 至 32。 */
    SkipListMemTable(IntSupplier heightSupplier) {
        this.heightSupplier = Objects.requireNonNull(heightSupplier, "heightSupplier");
    }

    void put(ByteKey key, Value value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        upsert(key, value);
    }

    /** 保存删除标记，包括尚不存在的键；已有节点保留其高度与指针。 */
    void delete(ByteKey key) {
        Objects.requireNonNull(key, "key");
        upsert(key, Tombstone.INSTANCE);
    }

    Optional<Entry> get(ByteKey key) {
        Objects.requireNonNull(key, "key");
        Node candidate = findPredecessor(key, null).next[0];
        if (candidate != null && candidate.key.compareTo(key) == 0) {
            return Optional.of(candidate.entry);
        }
        return Optional.empty();
    }

    private void upsert(ByteKey key, Entry entry) {
        var update = new Node[MAX_HEIGHT];
        Node candidate = findPredecessor(key, update).next[0];
        if (candidate != null && candidate.key.compareTo(key) == 0) {
            // 保留同一个节点及其高度，各层指针都无需改变。
            candidate.entry = entry;
            return;
        }

        int nodeHeight = heightSupplier.getAsInt();
        if (nodeHeight < 1 || nodeHeight > MAX_HEIGHT) {
            throw new IllegalArgumentException("Node height must be between 1 and " + MAX_HEIGHT);
        }
        var node = new Node(key, entry, nodeHeight);
        for (int level = height; level < nodeHeight; level++) {
            // 新启用的层尚无数据节点，前驱只能是头哨兵。
            update[level] = head;
        }
        for (int level = 0; level < nodeHeight; level++) {
            node.next[level] = update[level].next[level];
            update[level].next[level] = node;
        }
        height = Math.max(height, nodeHeight);
        size++;
    }

    /** 返回底层前驱；需要插入时同时保存每一层的前驱。 */
    private Node findPredecessor(ByteKey key, Node[] update) {
        Node current = head;
        for (int level = height - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
            if (update != null) {
                update[level] = current;
            }
        }
        return current;
    }

    private static int randomHeight() {
        int result = 1;
        while (result < MAX_HEIGHT && ThreadLocalRandom.current().nextBoolean()) {
            result++;
        }
        return result;
    }

    private static final class Node {
        private final ByteKey key;
        private Entry entry;
        private final Node[] next;

        private Node(ByteKey key, Entry entry, int height) {
            // null key/entry 仅供头哨兵使用；真实节点的入参已在写入入口校验。
            this.key = key;
            this.entry = entry;
            this.next = new Node[height];
        }
    }
}
