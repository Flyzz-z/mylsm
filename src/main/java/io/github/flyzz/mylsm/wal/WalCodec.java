package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.zip.CRC32C;

/** 版本 1 的单条 WAL 帧编解码；不执行文件 I/O，也不决定恢复时的截尾策略。 */
public final class WalCodec {
    /** 长度字段与校验和字段共 8 字节。 */
    public static final int PREFIX_SIZE = 8;
    /** 操作类型、键长度和值长度共 9 字节。 */
    public static final int MIN_PAYLOAD_SIZE = 9;
    /** 上限包含载荷中的类型和长度字段，不包含帧前缀。 */
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    private static final int PUT = 1;
    private static final int DELETE = 2;

    private WalCodec() {
    }

    /**
     * 编码为独立数组：长度(4) | CRC32C(4) | 类型(1) | 键长(4) | 值长(4) | 键 | 值。
     *
     * @throws NullPointerException 如果 record 为 null
     * @throws IllegalArgumentException 如果载荷超过上限
     */
    public static byte[] encode(WalRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] key = record.key().toByteArray();
        boolean isPut = record.entry() instanceof Value;
        byte[] value = isPut ? ((Value) record.entry()).toByteArray() : new byte[0];
        // 先使用 long 求和，避免 int 溢出后绕过大小检查。
        long payloadSize = (long) MIN_PAYLOAD_SIZE + key.length + value.length;
        if (payloadSize > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("WAL payload exceeds " + MAX_PAYLOAD_SIZE + " bytes");
        }

        int payloadLength = (int) payloadSize;
        byte[] frame = new byte[PREFIX_SIZE + payloadLength];
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(payloadLength);
        buffer.putInt(0); // 校验字段暂时占位，写完载荷再计算。
        buffer.put((byte) (isPut ? PUT : DELETE));
        buffer.putInt(key.length);
        buffer.putInt(value.length);
        buffer.put(key);
        buffer.put(value);
        buffer.putInt(Integer.BYTES, checksum(frame, payloadLength));
        return frame;
    }

    /**
     * 解码恰好一条完整帧；输入保持不变，结果不保留可变数组引用。
     *
     * @throws NullPointerException 如果 frame 为 null
     * @throws EOFException 如果前缀不完整，或合法载荷长度下字节不足
     * @throws IOException 如果长度非法、有额外字节、校验失败或载荷结构非法
     */
    public static WalRecord decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < PREFIX_SIZE) {
            throw new EOFException("Incomplete WAL record prefix");
        }

        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int payloadLength = buffer.getInt();
        if (payloadLength < MIN_PAYLOAD_SIZE || payloadLength > MAX_PAYLOAD_SIZE) {
            throw new IOException("Invalid WAL payload length: " + payloadLength);
        }
        int frameLength = PREFIX_SIZE + payloadLength; // 上限检查后不会溢出。
        if (frame.length < frameLength) {
            throw new EOFException("Incomplete WAL record payload");
        }
        if (frame.length > frameLength) {
            throw new IOException("Trailing bytes after WAL record");
        }
        int storedChecksum = buffer.getInt();
        if (storedChecksum != checksum(frame, payloadLength)) {
            throw new IOException("WAL checksum mismatch");
        }

        int operation = Byte.toUnsignedInt(buffer.get());
        int keyLength = buffer.getInt();
        int valueLength = buffer.getInt();
        // 校验结构后才按字段分配数组，损坏输入不能控制未经验证的分配大小。
        if (keyLength < 0 || valueLength < 0
                || (long) MIN_PAYLOAD_SIZE + keyLength + valueLength != payloadLength) {
            throw new IOException("Invalid WAL key/value lengths");
        }
        if (operation != PUT && operation != DELETE) {
            throw new IOException("Unknown WAL operation: " + operation);
        }
        if (operation == DELETE && valueLength != 0) {
            throw new IOException("DELETE must not contain a value");
        }

        byte[] key = new byte[keyLength];
        buffer.get(key);
        Entry entry;
        if (operation == PUT) {
            byte[] value = new byte[valueLength];
            buffer.get(value);
            entry = Value.copyOf(value);
        } else {
            entry = Tombstone.INSTANCE;
        }
        return new WalRecord(ByteKey.copyOf(key), entry);
    }

    private static int checksum(byte[] frame, int payloadLength) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, Integer.BYTES);
        crc.update(frame, PREFIX_SIZE, payloadLength);
        return (int) crc.getValue();
    }
}
