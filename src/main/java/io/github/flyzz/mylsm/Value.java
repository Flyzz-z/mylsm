package io.github.flyzz.mylsm;

import java.util.Arrays;
import java.util.Objects;

/** 不可变的字节值；空数组是合法值，不表示删除。 */
public final class Value implements Entry {
    private final byte[] bytes;

    private Value(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /**
     * 复制输入内容创建值；允许空数组。
     *
     * @throws NullPointerException 如果 bytes 为 null
     */
    public static Value copyOf(byte[] bytes) {
        return new Value(bytes);
    }

    /** 返回独立副本，修改它不会影响此值。 */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Value value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
