package io.github.flyzz.mylsm;

import java.util.Arrays;
import java.util.Objects;

/**
 * 不可变的字节键，按无符号字节字典序排序。
 * 内容相等与自然顺序一致，适用于有序索引和哈希索引。
 */
public final class ByteKey implements Comparable<ByteKey> {
    private final byte[] bytes;

    private ByteKey(byte[] bytes) {
        // final 只能阻止引用重新赋值；复制才隔离调用方对数组内容的修改。
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /**
     * 复制输入内容创建键；允许空数组。
     *
     * @throws NullPointerException 如果 bytes 为 null
     */
    public static ByteKey copyOf(byte[] bytes) {
        return new ByteKey(bytes);
    }

    /** 返回独立副本，调用方可以修改它而不影响此键。 */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /**
     * 按首个不同字节的 0 至 255 值比较；共同前缀相同时短键在前。
     *
     * @throws NullPointerException 如果 other 为 null
     */
    @Override
    public int compareTo(ByteKey other) {
        Objects.requireNonNull(other, "other");
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
