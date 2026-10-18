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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.READ;

/** 共享只读文件和索引；每个游标独立维护进度，数据块按访问验证，无跨 context 块缓存。 */
public final class SsTableReader implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }

    private final ReadIo input;
    private final List<IndexEntry> index;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private volatile State state = State.OPEN; // 并行读取可单向标记 FAILED；关闭持写锁。

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
     * @throws IllegalStateException 如果实例已关闭或发生过文件错误
     * @throws NullPointerException 如果 key 为 null；参数错误不使实例失效
     * @throws IOException 如果读取或块验证失败；此后仅允许 close
     */
    public Optional<Entry> get(ByteKey key) throws IOException {
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
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
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 按键顺序遍历，包括删除标记；每个块完成验证后才向回调产生记录。
     * 文件错误使实例失效；回调自身抛错只终止本次遍历，不回滚已产生的副作用。
     * 每次 scan 使用独立游标，回调在 Reader 锁外执行，可启动其他独立读取。
     * 回调关闭 Reader 会使本次扫描的后续操作失败；调用方不得修改正在读取的文件。
     *
     * @throws IllegalStateException 如果实例已关闭或已失败
     * @throws NullPointerException 如果 consumer 为 null；参数错误不使实例失效
     * @throws IOException 如果读取或块验证失败；此后仅允许 close
     */
    public void scan(Consumer<SsTableRecord> consumer) throws IOException {
        try (Cursor context = openCursor()) {
            Objects.requireNonNull(consumer, "consumer");
            while (context.next()) {
                // next/record 已释放生命周期读锁；用户回调错误不使共享 Reader 失效。
                consumer.accept(context.record());
            }
        }
    }

    /**
     * 创建可暂停的有序游标，不预读数据块；调用方须显式关闭，包括正常耗尽后。
     * 每个游标是独立读 context，可与其他游标、点查和扫描并行；关闭只清理局部状态。
     *
     * @throws IllegalStateException 如果实例已关闭或已失败
     */
    public Cursor openCursor() {
        lifecycleLock.readLock().lock();
        try {
            requireOpen();
            return new Cursor();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** 单线程拉取游标，最多保留一个已验证数据块；不实现会隐藏 checked I/O 的 Iterator。 */
    public final class Cursor implements AutoCloseable {
        private List<SsTableRecord> records = List.of();
        private int nextBlock;
        private int recordIndex = -1;
        private boolean closed;

        private Cursor() {
        }

        /**
         * 推进到下一条；仅需要下一块时才读取并完整验证它。
         * 耗尽后重复返回 false，直到关闭；失败后 Reader 及游标都不可再读。
         *
         * @throws IOException 如果数据块读取或验证失败
         * @throws IllegalStateException 如果游标已关闭或 Reader 已失效
         */
        public boolean next() throws IOException {
            lifecycleLock.readLock().lock();
            try {
                requireUsable();
                if (recordIndex + 1 < records.size()) {
                    recordIndex++;
                    return true;
                }
                // 先清除旧位置和块引用，避免失败后暴露旧记录或在读下一块时持有两块。
                clearPosition();
                if (nextBlock == index.size()) {
                    return false;
                }
                try {
                    records = readBlock(nextBlock);
                    nextBlock++;
                    recordIndex = 0;
                    return true;
                } catch (IOException failure) {
                    state = State.FAILED;
                    throw failure;
                }
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        /** 返回最近一次成功推进的不可变记录；重复读取不推进游标。 */
        public SsTableRecord record() {
            lifecycleLock.readLock().lock();
            try {
                requireUsable();
                if (recordIndex < 0) {
                    throw new IllegalStateException("SSTable cursor has no current record");
                }
                return records.get(recordIndex);
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        /** 释放本 context 的当前位置和块缓冲；单线程调用，不操作共享资源，重复关闭安全。 */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                clearPosition();
            }
        }

        private void clearPosition() {
            records = List.of();
            recordIndex = -1;
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException("SSTable cursor is closed");
            }
            requireOpen();
        }
    }

    /**
     * 等待当前读取操作结束，再变为 CLOSED 并释放文件；不等待暂停游标的整个生命周期。
     * 已有 context 后续操作均拒绝，局部缓冲由各自 close 释放；文件关闭失败后也不能继续使用。
     * 重复关闭不重复操作资源。
     */
    @Override
    public void close() throws IOException {
        lifecycleLock.writeLock().lock();
        try {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                input.close();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
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

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("SSTable reader is " + state);
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
