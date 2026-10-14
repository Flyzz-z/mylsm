package io.github.flyzz.mylsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueTest {
    @Test
    void inputMutationDoesNotChangeStoredValueOrIdentity() {
        byte[] input = {0, (byte) 0x80, (byte) 0xff};
        Value value = Value.copyOf(input);
        int originalHash = value.hashCode();

        input[0] = 9;

        byte[] expected = {0, (byte) 0x80, (byte) 0xff};
        assertArrayEquals(expected, value.toByteArray());
        assertEquals(Value.copyOf(expected), value);
        assertEquals(originalHash, value.hashCode());
    }

    @Test
    void exportedArrayMutationDoesNotChangeStoredValueOrIdentity() {
        Value value = Value.copyOf(new byte[]{1, 2});
        int originalHash = value.hashCode();
        byte[] exported = value.toByteArray();

        exported[0] = 9;

        assertArrayEquals(new byte[]{1, 2}, value.toByteArray());
        assertEquals(Value.copyOf(new byte[]{1, 2}), value);
        assertEquals(originalHash, value.hashCode());
    }

    @Test
    void distinctArraysWithEqualContentsProduceEqualValues() {
        Value first = Value.copyOf(new byte[]{1, 2});
        Value second = Value.copyOf(new byte[]{1, 2});

        assertNotSame(first, second);
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void equalityRequiresMatchingContentLengthAndType() {
        Value value = Value.copyOf(new byte[]{1, 2});

        assertNotEquals(value, Value.copyOf(new byte[]{1, 3}));
        assertNotEquals(value, Value.copyOf(new byte[]{1}));
        assertNotEquals(value, Value.copyOf(new byte[]{1, 2, 0}));
        assertNotEquals(value, ByteKey.copyOf(new byte[]{1, 2}));
        assertNotEquals(value, new byte[]{1, 2});
        assertNotEquals(value, null);
    }

    @Test
    void emptyValueIsValidAndDistinctFromDeletion() {
        Entry empty = Value.copyOf(new byte[0]);
        Entry deleted = Tombstone.INSTANCE;

        Value value = assertInstanceOf(Value.class, empty);
        assertArrayEquals(new byte[0], value.toByteArray());
        assertEquals(Value.copyOf(new byte[0]), value);
        assertInstanceOf(Tombstone.class, deleted);
        assertNotEquals(empty, deleted);
        assertNotEquals(deleted, empty);
    }

    @Test
    void rejectsNullRatherThanTreatingItAsDeletion() {
        assertThrows(NullPointerException.class, () -> Value.copyOf(null));
    }
}
