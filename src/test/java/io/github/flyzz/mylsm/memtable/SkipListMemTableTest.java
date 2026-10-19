package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 当前验收读写与删除标记；完整 MemTable 契约在快照完成后复用。 */
class SkipListMemTableTest {
    @Test
    void emptyReadsDoNotGenerateHeights() {
        var table = new SkipListMemTable(() -> {
            throw new AssertionError("Reads must not generate a node height");
        });

        assertEquals(Optional.empty(), table.get(key(1)));
        assertEquals(Optional.empty(), table.get(ByteKey.copyOf(new byte[0])));
        assertEquals(Optional.empty(), table.get(key(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 32})
    void insertsAtHeadMiddleAndTailForUniformHeights(int height) {
        var table = new SkipListMemTable(() -> height);
        var reference = new TreeMapMemTable();

        for (int next : new int[]{18, 5, 25, 2, 12, 20, 9, 17}) {
            table.put(key(next), value(next));
            reference.put(key(next), value(next));
            // 每次插入后验证全部命中和头部、间隙、尾部未命中。
            for (int query = 0; query <= 27; query++) {
                assertEquals(reference.get(key(query)), table.get(key(query)),
                        "height=" + height + ", inserted=" + next + ", query=" + query);
            }
        }
    }

    @Test
    void searchesAcrossMixedHeightsAndNewHighestLevels() {
        int[] heights = {1, 2, 1, 32, 3, 1, 4, 2};
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> heights[generated.getAndIncrement()]);
        var reference = new TreeMapMemTable();

        for (int next : new int[]{12, 5, 25, 18, 2, 20, 9, 17}) {
            table.put(key(next), value(next));
            reference.put(key(next), value(next));
            for (int query = 0; query <= 27; query++) {
                assertEquals(reference.get(key(query)), table.get(key(query)),
                        "inserted=" + next + ", query=" + query);
            }
        }
        assertEquals(heights.length, generated.get());
    }

    @Test
    void overwritesAnEqualKeyWithoutGeneratingAnotherHeight() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> {
            assertEquals(0, generated.getAndIncrement(), "Only the first put inserts a node");
            return 32;
        });

        table.put(key(12), value(1));
        table.put(key(12), value(2));
        table.put(key(12), Value.copyOf(new byte[0]));

        assertEquals(Optional.of(Value.copyOf(new byte[0])), table.get(key(12)));
        assertEquals(Optional.empty(), table.get(key(11)));
        assertEquals(Optional.empty(), table.get(key(13)));
        assertEquals(1, generated.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 32})
    void deletingExistingKeysRetainsMarkersWithoutGeneratingNewHeights(int height) {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> {
            generated.incrementAndGet();
            return height;
        });
        table.put(key(5), value(5));
        table.put(key(12), value(12));
        table.put(key(20), value(20));

        // 依次删除中间、头部和尾部节点，每次删除仍能定位其他键。
        table.delete(key(12));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(12)));
        assertEquals(Optional.of(value(5)), table.get(key(5)));
        assertEquals(Optional.of(value(20)), table.get(key(20)));
        table.delete(key(5));
        table.delete(key(20));
        table.delete(key(12));

        for (int deleted : new int[]{5, 12, 20}) {
            assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(deleted)));
        }
        assertEquals(Optional.empty(), table.get(key(13)));
        assertEquals(3, generated.get(), "Deleting existing keys must reuse their nodes");
    }

    @Test
    void deletingAbsentKeysIncludingEmptyKeyInsertsPersistentMarkers() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> {
            generated.incrementAndGet();
            return 32;
        });
        ByteKey emptyKey = ByteKey.copyOf(new byte[0]);

        table.delete(key(7));
        table.delete(emptyKey);
        table.delete(key(7));
        table.delete(ByteKey.copyOf(new byte[0]));

        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(7)));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(emptyKey));
        assertEquals(Optional.empty(), table.get(key(8)));
        assertEquals(2, generated.get());
    }

    @Test
    void putAfterDeletionRestoresValueInTheSameNode() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> {
            assertEquals(0, generated.getAndIncrement(), "Only the first delete inserts a node");
            return 3;
        });

        table.delete(key(12));
        table.put(key(12), value(99));
        assertEquals(Optional.of(value(99)), table.get(key(12)));
        table.delete(key(12));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(12)));
        table.put(key(12), Value.copyOf(new byte[0]));
        assertEquals(Optional.of(Value.copyOf(new byte[0])), table.get(key(12)));
        assertEquals(1, generated.get());
    }

    @Test
    void supportsEmptyPrefixAndUnsignedByteKeys() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> 1 + generated.getAndIncrement() % 4);
        List<ByteKey> keys = List.of(
                key(0xff), key(1), ByteKey.copyOf(new byte[]{1, 0}),
                key(0x80), ByteKey.copyOf(new byte[0]), key(0x7f), key(0));

        for (int i = 0; i < keys.size(); i++) {
            table.put(keys.get(i), value(i));
        }
        for (int i = 0; i < keys.size(); i++) {
            ByteKey equalKey = ByteKey.copyOf(keys.get(i).toByteArray());
            assertEquals(Optional.of(value(i)), table.get(equalKey));
        }
        assertEquals(Optional.empty(), table.get(ByteKey.copyOf(new byte[]{1, 0, 0})));
        assertEquals(Optional.empty(), table.get(ByteKey.copyOf(new byte[]{(byte) 0xff, 0})));
    }

    @Test
    void rejectsNullArgumentsBeforeGeneratingHeightsOrChangingRecords() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> {
            generated.incrementAndGet();
            return 2;
        });
        table.put(key(1), value(10));

        assertThrows(NullPointerException.class, () -> table.put(null, value(20)));
        assertThrows(NullPointerException.class, () -> table.put(key(1), null));
        assertThrows(NullPointerException.class, () -> table.put(key(2), null));
        assertThrows(NullPointerException.class, () -> table.get(null));
        assertThrows(NullPointerException.class, () -> table.delete(null));
        assertThrows(NullPointerException.class, () -> new SkipListMemTable(null));

        assertEquals(1, generated.get());
        assertEquals(Optional.of(value(10)), table.get(key(1)));
        assertEquals(Optional.empty(), table.get(key(2)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 33, Integer.MAX_VALUE})
    void invalidHeightDoesNotPublishANodeAndLaterWritesStillWork(int invalidHeight) {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> switch (generated.getAndIncrement()) {
            case 0 -> 32;
            case 1 -> invalidHeight;
            default -> 1;
        });
        table.put(key(10), value(10));

        assertThrows(IllegalArgumentException.class, () -> table.put(key(5), value(5)));
        assertEquals(Optional.of(value(10)), table.get(key(10)));
        assertEquals(Optional.empty(), table.get(key(5)));

        table.put(key(5), value(50));
        assertEquals(Optional.of(value(50)), table.get(key(5)));
        assertEquals(Optional.of(value(10)), table.get(key(10)));
    }

    @Test
    void invalidHeightOnDeleteDoesNotPublishMarkerOrChangeOtherKeys() {
        var generated = new AtomicInteger();
        var table = new SkipListMemTable(() -> switch (generated.getAndIncrement()) {
            case 0 -> 32;
            case 1 -> 33;
            default -> 1;
        });
        table.put(key(10), value(10));

        assertThrows(IllegalArgumentException.class, () -> table.delete(key(5)));
        assertEquals(Optional.empty(), table.get(key(5)));
        assertEquals(Optional.of(value(10)), table.get(key(10)));

        table.delete(key(5));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(5)));
        assertEquals(Optional.of(value(10)), table.get(key(10)));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 42, 8675309})
    void matchesTreeMapForDeterministicMixedReadsWritesAndDeletes(long seed) {
        var operations = new Random(seed);
        var levels = new Random(seed ^ 0x5deece66dL);
        var table = new SkipListMemTable(() -> {
            int height = 1;
            while (height < 32 && levels.nextBoolean()) {
                height++;
            }
            return height;
        });
        var reference = new TreeMapMemTable();
        var keys = new ArrayList<ByteKey>();
        keys.add(ByteKey.copyOf(new byte[0]));
        for (int i = 0; i < 128; i++) {
            keys.add(key(i * 2));
            keys.add(ByteKey.copyOf(new byte[]{(byte) (i * 2), 0}));
        }

        for (int step = 0; step < 1000; step++) {
            ByteKey next = keys.get(operations.nextInt(keys.size()));
            int operation = operations.nextInt(3);
            if (operation == 0) {
                byte[] bytes = new byte[operations.nextInt(8)];
                operations.nextBytes(bytes);
                Value value = Value.copyOf(bytes);
                table.put(next, value);
                reference.put(next, value);
            } else if (operation == 1) {
                table.delete(next);
                reference.delete(next);
            }
            assertEquals(reference.get(next), table.get(next), "seed=" + seed + ", step=" + step);
        }
        for (ByteKey key : keys) {
            assertEquals(reference.get(key), table.get(key));
        }
    }

    @Test
    void defaultInstancesKeepIndependentState() {
        var first = new SkipListMemTable();
        var second = new SkipListMemTable();
        for (int i = 255; i >= 0; i--) {
            first.put(key(i), value(i));
        }
        second.put(key(1), value(99));

        for (int i = 0; i <= 255; i++) {
            assertEquals(Optional.of(value(i)), first.get(key(i)));
        }
        assertEquals(Optional.of(value(99)), second.get(key(1)));
        assertEquals(Optional.empty(), second.get(key(2)));
    }

    private static ByteKey key(int value) {
        return ByteKey.copyOf(new byte[]{(byte) value});
    }

    private static Value value(int value) {
        return Value.copyOf(new byte[]{(byte) value});
    }
}
