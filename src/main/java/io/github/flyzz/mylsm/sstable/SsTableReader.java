package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.Footer;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.IndexEntry;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.READ;

/** 单线程只读 SSTable；元信息在打开时验证，数据块按访问时验证，不缓存数据块。 */
public final class SsTableReader implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }

    private final ReadIo input;
    private final List<IndexEntry> index;
    private State state = State.OPEN;
    private boolean scanning;

    private SsTableReader(ReadIo input, List<IndexEntry> index) {
        this.input = input;
        this.index = index;
    }

    /**
     * 只读打开已有文件，不创建、不修复文件。调用方须保证使用期间文件不被外部修改。
     * 成功仅代表文件头、Footer 与完整索引合法，不代表所有数据块已经验证。
     *
     * @throws NullPointerException 如果 path 为 null
     * @throws IOException 如果打开、读取或元信息验证失败；失败时尝试关闭资源
     */
    public static SsTableReader open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(new ChannelInput(FileChannel.open(path, READ)));
    }

    /** 包内故障注入入口；输入资源所有权交给 Reader，包括打开失败时的释放。 */
    static SsTableReader open(ReadIo input) throws IOException {
        Objects.requireNonNull(input, "input");
        try {
            long fileSize = input.size();
            if (fileSize < SsTableMetadataCodec.MIN_FILE_SIZE) {
                throw new IOException("SSTable file is too short");
            }
            SsTableMetadataCodec.decodeHeader(readFully(input, 0, SsTableMetadataCodec.HEADER_SIZE));
            Footer footer = SsTableMetadataCodec.decodeFooter(readFully(input,
                    fileSize - SsTableMetadataCodec.FOOTER_SIZE, SsTableMetadataCodec.FOOTER_SIZE), fileSize);
            // Footer 已检查长度上限及文件边界，然后才为索引分配数组。
            List<IndexEntry> index = SsTableMetadataCodec.decodeIndex(
                    readFully(input, footer.indexOffset(), footer.indexLength()), footer.indexOffset());
            return new SsTableReader(input, index);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * 点查并保留 Value、Tombstone 与缺失三态；读取整个候选块并验证后才返回结果。
     *
     * @throws IllegalStateException 如果实例已关闭、发生过文件错误，或在 scan 回调中重入
     * @throws NullPointerException 如果 key 为 null；参数错误不使实例失效
     * @throws IOException 如果读取或块验证失败；此后仅允许 close
     */
    public Optional<Entry> get(ByteKey key) throws IOException {
        requireAvailable();
        Objects.requireNonNull(key, "key");
        int blockIndex = findBlock(key);
        if (blockIndex == index.size()) {
            return Optional.empty();
        }
        try {
            List<SsTableRecord> records = readBlock(blockIndex);
            for (SsTableRecord record : records) {
                int comparison = record.key().compareTo(key);
                if (comparison == 0) {
                    return Optional.of(record.entry());
                }
                if (comparison > 0) {
                    break;
                }
            }
            return Optional.empty();
        } catch (IOException failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /**
     * 按键顺序遍历，包括删除标记；每个块完成验证后才向回调产生记录。
     * 文件错误使实例失效；回调自身抛错只终止本次遍历，不回滚已产生的副作用。
     * 回调不得重入或关闭本 Reader，也不得修改正在读取的文件。
     *
     * @throws IllegalStateException 如果实例已关闭、已失败或正在 scan
     * @throws NullPointerException 如果 consumer 为 null；参数错误不使实例失效
     * @throws IOException 如果读取或块验证失败；此后仅允许 close
     */
    public void scan(Consumer<SsTableRecord> consumer) throws IOException {
        requireAvailable();
        Objects.requireNonNull(consumer, "consumer");
        scanning = true;
        try {
            for (int blockIndex = 0; blockIndex < index.size(); blockIndex++) {
                List<SsTableRecord> records;
                try {
                    records = readBlock(blockIndex);
                } catch (IOException failure) {
                    state = State.FAILED;
                    throw failure;
                }
                // 回调放在读取错误的 catch 范围外，不能将回调失败误判为文件损坏。
                for (SsTableRecord record : records) {
                    consumer.accept(record);
                }
            }
        } finally {
            scanning = false;
        }
    }

    /**
     * 先变为 CLOSED 再释放资源；关闭失败后仍不能使用，重复关闭不重复操作资源。
     * scan 回调期间禁止关闭，抛 IllegalStateException，不改变生命周期。
     */
    @Override
    public void close() throws IOException {
        if (scanning) {
            throw new IllegalStateException("Cannot close SSTable reader during scan");
        }
        if (state != State.CLOSED) {
            state = State.CLOSED;
            input.close();
        }
    }

    /** 找到第一个 maxKey >= key 的块；不存在时返回 index.size()。 */
    private int findBlock(ByteKey key) {
        int low = 0;
        int high = index.size();
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (index.get(mid).maxKey().compareTo(key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private List<SsTableRecord> readBlock(int blockIndex) throws IOException {
        IndexEntry entry = index.get(blockIndex);
        // 长度和区间已在索引解码时验证；帧中的长度还会由数据块 Codec 交叉检查。
        List<SsTableRecord> records = SsTableBlockCodec.decode(
                readFully(input, entry.offset(), entry.frameLength()));
        if (!records.getLast().key().equals(entry.maxKey())) {
            throw new IOException("SSTable block maximum key does not match index at offset " + entry.offset());
        }
        if (blockIndex > 0 && records.getFirst().key().compareTo(index.get(blockIndex - 1).maxKey()) <= 0) {
            throw new IOException("SSTable block overlaps previous key range at offset " + entry.offset());
        }
        return records;
    }

    private void requireAvailable() {
        if (state != State.OPEN) {
            throw new IllegalStateException("SSTable reader is " + state);
        }
        if (scanning) {
            throw new IllegalStateException("Cannot reenter SSTable reader during scan");
        }
    }

    private static byte[] readFully(ReadIo input, long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        ByteBuffer target = ByteBuffer.wrap(bytes);
        long position = offset;
        while (target.hasRemaining()) {
            int read = input.read(target, position);
            if (read < 0) {
                throw new EOFException("Unexpected EOF in SSTable at offset " + position);
            }
            if (read == 0) {
                throw new IOException("SSTable read made no progress at offset " + position);
            }
            position += read;
        }
        return bytes;
    }

    /** 读取所需的最小边界，独立于 Writer 的创建、删除和写入操作。 */
    interface ReadIo extends Closeable {
        long size() throws IOException;

        int read(ByteBuffer target, long offset) throws IOException;
    }

    private record ChannelInput(FileChannel channel) implements ReadIo {
        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            return channel.read(target, offset);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
