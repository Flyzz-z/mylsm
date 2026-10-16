package io.github.flyzz.mylsm.lsm;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/** 版本 1 Manifest 的纯内存编解码；不执行 I/O、同步、历史恢复或截尾。 */
final class ManifestCodec {
    static final int HEADER_SIZE = 8;
    static final int PREFIX_SIZE = 8;
    static final int MIN_PAYLOAD_SIZE = 4 * Long.BYTES + Integer.BYTES;
    static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;
    static final int MAX_SSTABLE_COUNT = (MAX_PAYLOAD_SIZE - MIN_PAYLOAD_SIZE) / Long.BYTES;

    private static final int MAGIC = 0x4d594d46; // MYMF
    private static final int VERSION = 1;

    private ManifestCodec() {
    }

    static byte[] encodeHeader() {
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(VERSION).array();
    }

    /** 恰好接收一个文件头；截断抛 EOFException，其余格式错误抛 IOException。 */
    static void decodeHeader(byte[] header) throws IOException {
        requireExactLength(header, HEADER_SIZE, "header");
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
            throw new IOException("Unsupported Manifest magic or version");
        }
    }

    /** 状态已在构造时验证；返回独立数组，null 抛 NullPointerException。 */
    static byte[] encode(ManifestState state) {
        Objects.requireNonNull(state, "state");
        int payloadLength = MIN_PAYLOAD_SIZE + state.sstIds().size() * Long.BYTES;
        byte[] frame = new byte[PREFIX_SIZE + payloadLength];
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(payloadLength).putInt(0)
                .putLong(state.revision()).putLong(state.activeWalId())
                .putLong(state.frozenWalId()).putLong(state.frozenWalLength())
                .putInt(state.sstIds().size());
        for (long id : state.sstIds()) {
            buffer.putLong(id);
        }
        buffer.putInt(Integer.BYTES, checksum(frame, payloadLength));
        return frame;
    }

    /**
     * 解码恰好一个完整帧，不保留或修改输入数组。
     * 合法长度下帧字节不足抛 EOFException；非法长度、CRC 或内容抛 IOException。
     * 只验证本帧；恢复者须另行验证首状态及相邻状态转换。
     */
    static ManifestState decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < PREFIX_SIZE) {
            throw new EOFException("Incomplete Manifest frame prefix");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int payloadLength = buffer.getInt();
        if (payloadLength < MIN_PAYLOAD_SIZE || payloadLength > MAX_PAYLOAD_SIZE
                || (payloadLength - MIN_PAYLOAD_SIZE) % Long.BYTES != 0) {
            throw new IOException("Invalid Manifest payload length: " + payloadLength);
        }
        requireExactLength(frame, PREFIX_SIZE + payloadLength, "frame");
        if (buffer.getInt() != checksum(frame, payloadLength)) {
            throw new IOException("Manifest checksum mismatch");
        }
        long revision = buffer.getLong();
        long active = buffer.getLong();
        long frozen = buffer.getLong();
        long frozenLength = buffer.getLong();
        int count = buffer.getInt();
        // 在按 count 分配前检查精确长度；long 避免 count * 8 的整数溢出。
        if (count < 0 || count > MAX_SSTABLE_COUNT
                || (long) count * Long.BYTES != buffer.remaining()) {
            throw new IOException("Invalid Manifest SSTable count: " + count);
        }
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buffer.getLong());
        }
        try {
            return new ManifestState(revision, active, frozen, frozenLength, ids);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid Manifest state: " + failure.getMessage(), failure);
        }
    }

    private static int checksum(byte[] frame, int payloadLength) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, Integer.BYTES);
        crc.update(frame, PREFIX_SIZE, payloadLength);
        return (int) crc.getValue();
    }

    private static void requireExactLength(byte[] bytes, int expected, String name) throws IOException {
        Objects.requireNonNull(bytes, name);
        if (bytes.length < expected) {
            throw new EOFException("Incomplete Manifest " + name);
        }
        if (bytes.length > expected) {
            throw new IOException("Trailing bytes after Manifest " + name);
        }
    }
}
