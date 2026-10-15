package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/** 版本 1 文件头、索引块和 Footer 的内存编解码；不读取数据块或执行文件 I/O。 */
final class SsTableMetadataCodec {
    static final int HEADER_SIZE = 8;
    static final int FOOTER_SIZE = 24;
    static final int MAX_INDEX_PAYLOAD_SIZE = 64 * 1024 * 1024;
    static final int MIN_INDEX_FRAME_SIZE = SsTableBlockCodec.PREFIX_SIZE + Integer.BYTES;
    static final int MAX_INDEX_FRAME_SIZE = SsTableBlockCodec.PREFIX_SIZE + MAX_INDEX_PAYLOAD_SIZE;
    static final int MIN_FILE_SIZE = HEADER_SIZE + MIN_INDEX_FRAME_SIZE + FOOTER_SIZE;

    private static final int HEADER_MAGIC = 0x4d595354; // MYST
    private static final int FOOTER_MAGIC = 0x4d594654; // MYFT
    private static final int VERSION = 1;
    private static final int INDEX_ENTRY_FIXED_SIZE = 16;
    private static final int MAX_KEY_SIZE = SsTableBlockCodec.MAX_RECORD_SIZE
            - SsTableBlockCodec.RECORD_HEADER_SIZE;
    private static final int MIN_DATA_FRAME_SIZE = SsTableBlockCodec.PREFIX_SIZE
            + SsTableBlockCodec.MIN_PAYLOAD_SIZE;
    private static final int MAX_DATA_FRAME_SIZE = SsTableBlockCodec.PREFIX_SIZE
            + SsTableBlockCodec.MAX_PAYLOAD_SIZE;

    private SsTableMetadataCodec() {
    }

    /** 描述一个块；数值字段及跨项约束由编解码边界统一验证。 */
    record IndexEntry(ByteKey maxKey, long offset, int frameLength) {
        IndexEntry {
            Objects.requireNonNull(maxKey, "maxKey");
        }
    }

    /** indexLength 包含完整索引帧的 8 字节前缀。 */
    record Footer(long indexOffset, int indexLength) {
    }

    static byte[] encodeHeader() {
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
                .putInt(HEADER_MAGIC).putInt(VERSION).array();
    }

    /** 接收恰好 8 字节，验证 magic 和版本。 */
    static void decodeHeader(byte[] header) throws IOException {
        requireExactLength(header, HEADER_SIZE, "header");
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != HEADER_MAGIC || buffer.getInt() != VERSION) {
            throw new IOException("Unsupported SSTable header magic or version");
        }
    }

    /**
     * 编码按块顺序排列的索引。空索引仅在 indexOffset=HEADER_SIZE 时合法。
     *
     * @throws NullPointerException 如果列表或条目为 null
     * @throws IllegalArgumentException 如果键或数据区布局非法
     * @throws IOException 如果索引载荷超过容量上限
     */
    static byte[] encodeIndex(List<IndexEntry> entries, long indexOffset) throws IOException {
        List<IndexEntry> snapshot = List.copyOf(entries);
        if (indexOffset < HEADER_SIZE) {
            throw new IllegalArgumentException("Index offset precedes data region");
        }
        List<byte[]> keys = new ArrayList<>();
        long payloadSize = Integer.BYTES;
        long expectedOffset = HEADER_SIZE;
        ByteKey previous = null;
        for (IndexEntry entry : snapshot) {
            byte[] key = entry.maxKey().toByteArray();
            String error = entryError(previous, entry.maxKey(), key.length,
                    entry.offset(), entry.frameLength(), expectedOffset, indexOffset);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
            payloadSize += (long) INDEX_ENTRY_FIXED_SIZE + key.length;
            if (payloadSize > MAX_INDEX_PAYLOAD_SIZE) {
                throw new IOException("SSTable index exceeds payload limit");
            }
            keys.add(key);
            expectedOffset += entry.frameLength(); // entryError 已验证不超过 indexOffset。
            previous = entry.maxKey();
        }
        if (expectedOffset != indexOffset) {
            throw new IllegalArgumentException("Data region does not end at index offset");
        }

        int payloadLength = (int) payloadSize;
        byte[] frame = new byte[SsTableBlockCodec.PREFIX_SIZE + payloadLength];
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(payloadLength).putInt(0).putInt(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            IndexEntry entry = snapshot.get(i);
            byte[] key = keys.get(i);
            buffer.putInt(key.length).put(key).putLong(entry.offset()).putInt(entry.frameLength());
        }
        buffer.putInt(Integer.BYTES, indexChecksum(frame, payloadLength));
        return frame;
    }

    /**
     * 校验完整索引帧及其描述的数据区，返回不可修改的索引对象列表。
     * 不保留或修改输入数组；合法帧截断抛 EOFException，非法结构抛 IOException。
     */
    static List<IndexEntry> decodeIndex(byte[] frame, long indexOffset) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (indexOffset < HEADER_SIZE) {
            throw new IOException("Index offset precedes data region");
        }
        if (frame.length < SsTableBlockCodec.PREFIX_SIZE) {
            throw new EOFException("Incomplete SSTable index prefix");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int payloadLength = buffer.getInt();
        if (payloadLength < Integer.BYTES || payloadLength > MAX_INDEX_PAYLOAD_SIZE) {
            throw new IOException("Invalid SSTable index payload length: " + payloadLength);
        }
        requireExactLength(frame, SsTableBlockCodec.PREFIX_SIZE + payloadLength, "index frame");
        if (buffer.getInt() != indexChecksum(frame, payloadLength)) {
            throw new IOException("SSTable index checksum mismatch");
        }
        int count = buffer.getInt();
        if (count < 0 || count > buffer.remaining() / INDEX_ENTRY_FIXED_SIZE) {
            throw new IOException("Invalid SSTable index entry count: " + count);
        }
        List<IndexEntry> entries = new ArrayList<>(count);
        long expectedOffset = HEADER_SIZE;
        ByteKey previous = null;
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < INDEX_ENTRY_FIXED_SIZE) {
                throw new IOException("Incomplete SSTable index entry at index " + i);
            }
            int keyLength = buffer.getInt();
            if (keyLength < 0 || keyLength > MAX_KEY_SIZE
                    || keyLength > buffer.remaining() - Long.BYTES - Integer.BYTES) {
                throw new IOException("Invalid SSTable index key length at index " + i);
            }
            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            ByteKey key = ByteKey.copyOf(keyBytes);
            long offset = buffer.getLong();
            int frameLength = buffer.getInt();
            String error = entryError(previous, key, keyLength,
                    offset, frameLength, expectedOffset, indexOffset);
            if (error != null) {
                throw new IOException(error + " at index " + i);
            }
            entries.add(new IndexEntry(key, offset, frameLength));
            expectedOffset += frameLength;
            previous = key;
        }
        if (buffer.hasRemaining() || expectedOffset != indexOffset) {
            throw new IOException("SSTable index does not exactly describe data region");
        }
        return List.copyOf(entries);
    }

    /** 非法调用参数抛 IllegalArgumentException，不生成会溢出 long 文件偏移的 Footer。 */
    static byte[] encodeFooter(Footer footer) {
        Objects.requireNonNull(footer, "footer");
        String error = footerError(footer.indexOffset(), footer.indexLength());
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        byte[] bytes = ByteBuffer.allocate(FOOTER_SIZE).order(ByteOrder.BIG_ENDIAN)
                .putLong(footer.indexOffset()).putInt(footer.indexLength())
                .putInt(VERSION).putInt(FOOTER_MAGIC).putInt(0).array();
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                .putInt(FOOTER_SIZE - Integer.BYTES, footerChecksum(bytes));
        return bytes;
    }

    /** 验证固定 Footer 及索引与文件末尾的关系，不读取索引或数据块。 */
    static Footer decodeFooter(byte[] bytes, long fileSize) throws IOException {
        requireExactLength(bytes, FOOTER_SIZE, "footer");
        if (fileSize < MIN_FILE_SIZE) {
            throw new IOException("SSTable file is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long indexOffset = buffer.getLong();
        int indexLength = buffer.getInt();
        if (buffer.getInt() != VERSION || buffer.getInt() != FOOTER_MAGIC) {
            throw new IOException("Unsupported SSTable footer magic or version");
        }
        if (buffer.getInt() != footerChecksum(bytes)) {
            throw new IOException("SSTable footer checksum mismatch");
        }
        String error = footerError(indexOffset, indexLength);
        if (error != null) {
            throw new IOException(error);
        }
        long footerOffset = fileSize - FOOTER_SIZE;
        // 先用减法比较，避免损坏的 offset + length 溢出后绕过文件边界检查。
        if (indexOffset > footerOffset || indexLength != footerOffset - indexOffset) {
            throw new IOException("SSTable index does not end at footer");
        }
        return new Footer(indexOffset, indexLength);
    }

    private static String entryError(ByteKey previous, ByteKey key, int keyLength,
                                     long offset, int frameLength, long expectedOffset, long indexOffset) {
        if (keyLength > MAX_KEY_SIZE) {
            return "SSTable index key exceeds record key limit";
        }
        if (previous != null && previous.compareTo(key) >= 0) {
            return "SSTable index keys must be strictly increasing";
        }
        if (frameLength < MIN_DATA_FRAME_SIZE || frameLength > MAX_DATA_FRAME_SIZE
                || keyLength > frameLength - MIN_DATA_FRAME_SIZE) {
            return "Invalid SSTable data block frame length";
        }
        if (offset != expectedOffset || frameLength > indexOffset - expectedOffset) {
            return "SSTable data block ranges must be contiguous and within data region";
        }
        return null;
    }

    private static String footerError(long indexOffset, int indexLength) {
        if (indexOffset < HEADER_SIZE
                || indexLength < MIN_INDEX_FRAME_SIZE || indexLength > MAX_INDEX_FRAME_SIZE) {
            return "Invalid SSTable footer index offset or length";
        }
        if (indexOffset > Long.MAX_VALUE - indexLength - FOOTER_SIZE) {
            return "SSTable file length would overflow";
        }
        return null;
    }

    private static void requireExactLength(byte[] bytes, int expectedLength, String name)
            throws IOException {
        Objects.requireNonNull(bytes, name);
        if (bytes.length < expectedLength) {
            throw new EOFException("Incomplete SSTable " + name);
        }
        if (bytes.length > expectedLength) {
            throw new IOException("Trailing bytes after SSTable " + name);
        }
    }

    private static int indexChecksum(byte[] frame, int payloadLength) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, Integer.BYTES);
        crc.update(frame, SsTableBlockCodec.PREFIX_SIZE, payloadLength);
        return (int) crc.getValue();
    }

    private static int footerChecksum(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, FOOTER_SIZE - Integer.BYTES);
        return (int) crc.getValue();
    }
}
