package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/** 版本 1 的完整数据块编解码；不执行文件 I/O，不负责按目标大小分块。 */
final class SsTableBlockCodec {
    static final int PREFIX_SIZE = 8;
    static final int COUNT_SIZE = Integer.BYTES;
    static final int RECORD_HEADER_SIZE = 9;
    static final int MAX_RECORD_SIZE = 16 * 1024 * 1024;
    static final int MIN_PAYLOAD_SIZE = COUNT_SIZE + RECORD_HEADER_SIZE;
    static final int MAX_PAYLOAD_SIZE = COUNT_SIZE + MAX_RECORD_SIZE;

    private static final int VALUE = 1;
    private static final int TOMBSTONE = 2;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private SsTableBlockCodec() {
    }

    /**
     * 将非空且按键严格递增的记录编码为独立数组。
     *
     * @throws NullPointerException 如果列表或其中的记录为 null
     * @throws IllegalArgumentException 如果块为空、键非严格递增、记录或块超限
     */
    static byte[] encode(List<SsTableRecord> records) {
        Objects.requireNonNull(records, "records");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("SSTable data block must not be empty");
        }

        // 暂存独立字节副本，先检查整个块，再一次分配输出帧。
        List<EncodedRecord> encoded = new ArrayList<>();
        ByteKey previous = null;
        long payloadSize = COUNT_SIZE;
        for (SsTableRecord record : records) {
            Objects.requireNonNull(record, "record");
            if (previous != null && previous.compareTo(record.key()) >= 0) {
                throw new IllegalArgumentException("SSTable keys must be strictly increasing");
            }
            byte[] key = record.key().toByteArray();
            boolean isValue = record.entry() instanceof Value;
            byte[] value = isValue ? ((Value) record.entry()).toByteArray() : EMPTY_BYTES;
            long recordSize = (long) RECORD_HEADER_SIZE + key.length + value.length;
            if (recordSize > MAX_RECORD_SIZE) {
                throw new IllegalArgumentException("SSTable record exceeds " + MAX_RECORD_SIZE + " bytes");
            }
            payloadSize += recordSize;
            if (payloadSize > MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException("SSTable data block exceeds payload limit");
            }
            encoded.add(new EncodedRecord(key, value, isValue));
            previous = record.key();
        }

        int payloadLength = (int) payloadSize;
        byte[] frame = new byte[PREFIX_SIZE + payloadLength];
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(payloadLength).putInt(0).putInt(encoded.size());
        for (EncodedRecord record : encoded) {
            buffer.put((byte) (record.isValue() ? VALUE : TOMBSTONE));
            buffer.putInt(record.key().length).putInt(record.value().length);
            buffer.put(record.key()).put(record.value());
        }
        buffer.putInt(Integer.BYTES, checksum(frame, payloadLength));
        return frame;
    }

    /**
     * 解码恰好一个完整非空块，全部验证成功后返回不可修改的记录列表。
     * 不修改或保留输入数组；调用方不得在解码期间并发修改输入。
     *
     * @throws NullPointerException 如果 frame 为 null
     * @throws EOFException 如果前缀不完整，或合法载荷长度下字节不足
     * @throws IOException 如果长度、校验、记录结构或键顺序非法，或存在额外字节
     */
    static List<SsTableRecord> decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < PREFIX_SIZE) {
            throw new EOFException("Incomplete SSTable data block prefix");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int payloadLength = buffer.getInt();
        if (payloadLength < MIN_PAYLOAD_SIZE || payloadLength > MAX_PAYLOAD_SIZE) {
            throw new IOException("Invalid SSTable data block payload length: " + payloadLength);
        }
        int frameLength = PREFIX_SIZE + payloadLength;
        if (frame.length < frameLength) {
            throw new EOFException("Incomplete SSTable data block payload");
        }
        if (frame.length > frameLength) {
            throw new IOException("Trailing bytes after SSTable data block");
        }
        if (buffer.getInt() != checksum(frame, payloadLength)) {
            throw new IOException("SSTable data block checksum mismatch");
        }

        int recordCount = buffer.getInt();
        // 每条记录至少有 9 字节头部，先约束数量，再按数量分配列表。
        if (recordCount <= 0 || recordCount > buffer.remaining() / RECORD_HEADER_SIZE) {
            throw new IOException("Invalid SSTable record count: " + recordCount);
        }
        List<SsTableRecord> records = new ArrayList<>(recordCount);
        ByteKey previous = null;
        for (int i = 0; i < recordCount; i++) {
            if (buffer.remaining() < RECORD_HEADER_SIZE) {
                throw new IOException("Incomplete SSTable record header at index " + i);
            }
            int kind = Byte.toUnsignedInt(buffer.get());
            int keyLength = buffer.getInt();
            int valueLength = buffer.getInt();
            long recordSize = (long) RECORD_HEADER_SIZE + keyLength + valueLength;
            if (keyLength < 0 || valueLength < 0 || recordSize > MAX_RECORD_SIZE
                    || recordSize - RECORD_HEADER_SIZE > buffer.remaining()) {
                throw new IOException("Invalid SSTable key/value lengths at index " + i);
            }
            if (kind != VALUE && kind != TOMBSTONE) {
                throw new IOException("Unknown SSTable record kind: " + kind);
            }
            if (kind == TOMBSTONE && valueLength != 0) {
                throw new IOException("SSTable tombstone must not contain a value");
            }

            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            ByteKey key = ByteKey.copyOf(keyBytes);
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new IOException("SSTable keys must be strictly increasing at index " + i);
            }
            Entry entry;
            if (kind == VALUE) {
                byte[] value = new byte[valueLength];
                buffer.get(value);
                entry = Value.copyOf(value);
            } else {
                entry = Tombstone.INSTANCE;
            }
            records.add(new SsTableRecord(key, entry));
            previous = key;
        }
        if (buffer.hasRemaining()) {
            throw new IOException("Unused bytes in SSTable data block payload");
        }
        return List.copyOf(records);
    }

    private static int checksum(byte[] frame, int payloadLength) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, Integer.BYTES);
        crc.update(frame, PREFIX_SIZE, payloadLength);
        return (int) crc.getValue();
    }

    private record EncodedRecord(byte[] key, byte[] value, boolean isValue) {
    }
}
