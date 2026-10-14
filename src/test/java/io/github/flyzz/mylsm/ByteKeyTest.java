package io.github.flyzz.mylsm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ByteKeyTest {
    @Test
    void inputMutationDoesNotChangeContentsIdentityOrOrder() {
        byte[] input = {1, 2};
        ByteKey key = ByteKey.copyOf(input);
        int originalHash = key.hashCode();

        input[0] = 9;

        assertArrayEquals(new byte[]{1, 2}, key.toByteArray());
        assertEquals(key("0102"), key);
        assertEquals(originalHash, key.hashCode());
        assertTrue(key.compareTo(key("02")) < 0);
    }

    @Test
    void exportedArrayMutationDoesNotChangeContentsIdentityOrOrder() {
        ByteKey key = key("0102");
        int originalHash = key.hashCode();
        byte[] exported = key.toByteArray();

        exported[0] = 9;

        assertArrayEquals(new byte[]{1, 2}, key.toByteArray());
        assertEquals(key("0102"), key);
        assertEquals(originalHash, key.hashCode());
        assertTrue(key.compareTo(key("02")) < 0);
    }

    @Test
    void distinctArraysWithEqualContentsRepresentTheSameKey() {
        ByteKey first = key("0180ff");
        ByteKey second = key("0180ff");

        assertNotSame(first, second);
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(0, first.compareTo(second));
        assertNotEquals(first, key("0180fe"));
        assertNotEquals(first, key("0180"));
        assertNotEquals(first, null);
        assertNotEquals(first, new byte[]{1, (byte) 0x80, (byte) 0xff});
    }

    @Test
    void emptyKeyIsValidAndSortsBeforeNonEmptyKeys() {
        ByteKey empty = key("");

        assertArrayEquals(new byte[0], empty.toByteArray());
        assertEquals(empty, key(""));
        assertEquals(0, empty.compareTo(key("")));
        assertTrue(empty.compareTo(key("00")) < 0);
        assertTrue(empty.compareTo(key("ff")) < 0);
    }

    @Test
    void rejectsNullInputAndNullComparison() {
        assertThrows(NullPointerException.class, () -> ByteKey.copyOf(null));
        assertThrows(NullPointerException.class, () -> key("01").compareTo(null));
    }

    @Test
    void followsUnsignedLexicographicOrderIncludingPrefixes() {
        // 独立列出期望顺序，避免用被测比较器自己生成预期结果。
        List<ByteKey> ordered = List.of(
                key(""), key("00"), key("0000"), key("0001"),
                key("007f"), key("0080"), key("00ff"), key("01"),
                key("0100"), key("01ff"), key("02"), key("7f"),
                key("80"), key("ff"), key("ff00"));

        for (int i = 0; i < ordered.size(); i++) {
            for (int j = 0; j < ordered.size(); j++) {
                assertEquals(Integer.signum(i - j),
                        Integer.signum(ordered.get(i).compareTo(ordered.get(j))),
                        "Unexpected order at positions " + i + " and " + j);
            }
        }
    }

    @Test
    void ordersAllSingleByteValuesAsUnsignedNumbers() {
        for (int i = 0; i < 256; i++) {
            ByteKey left = ByteKey.copyOf(new byte[]{(byte) i});
            for (int j = 0; j < 256; j++) {
                ByteKey right = ByteKey.copyOf(new byte[]{(byte) j});
                assertEquals(Integer.signum(i - j),
                        Integer.signum(left.compareTo(right)),
                        "Unexpected unsigned order for " + i + " and " + j);
            }
        }
    }

    @Test
    void comparisonIsConsistentWithEqualityAntisymmetricAndTransitive() {
        List<ByteKey> samples = List.of(
                key("80"), key("01ff"), key(""), key("ff"), key("00"),
                key("0100"), key("01"), key("7f"), key("02"), key("01"));

        for (ByteKey a : samples) {
            for (ByteKey b : samples) {
                int ab = a.compareTo(b);
                assertEquals(a.equals(b), ab == 0);
                assertEquals(-Integer.signum(ab), Integer.signum(b.compareTo(a)));
                for (ByteKey c : samples) {
                    if (ab <= 0 && b.compareTo(c) <= 0) {
                        assertTrue(a.compareTo(c) <= 0, "Ordering must be transitive");
                    }
                }
            }
        }
    }

    @Test
    void treeMapKeepsOrderAndReplacesEqualKeysDespiteExternalMutation() {
        byte[] input = {1};
        ByteKey storedKey = ByteKey.copyOf(input);
        var map = new TreeMap<ByteKey, String>();
        map.put(key("80"), "high");
        map.put(storedKey, "old");
        map.put(key("00"), "low");

        input[0] = (byte) 0xff;
        storedKey.toByteArray()[0] = (byte) 0xff;

        assertEquals("old", map.get(key("01")));
        assertEquals("old", map.put(key("01"), "new"));
        assertEquals(3, map.size());
        assertEquals("new", map.get(key("01")));
        assertEquals(List.of(key("00"), key("01"), key("80")),
                new ArrayList<>(map.keySet()));
    }

    @Test
    void hashMapLookupSurvivesExternalMutation() {
        byte[] input = {1, 2};
        ByteKey storedKey = ByteKey.copyOf(input);
        var map = new HashMap<ByteKey, String>();
        map.put(storedKey, "value");

        input[0] = 9;
        storedKey.toByteArray()[0] = 8;

        assertEquals("value", map.get(key("0102")));
        assertEquals("value", map.put(key("0102"), "updated"));
        assertEquals(1, map.size());
    }

    private static ByteKey key(String hex) {
        return ByteKey.copyOf(HexFormat.of().parseHex(hex));
    }
}
