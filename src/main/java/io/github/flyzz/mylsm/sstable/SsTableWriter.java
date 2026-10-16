package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.Footer;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.IndexEntry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** 单线程、一次性构建新的 SSTable 文件；输入必须已按键严格递增。 */
public final class SsTableWriter {
    static final int TARGET_BLOCK_PAYLOAD_SIZE = 4 * 1024;

    private static final FileOps FILES = new FileOps() {
        @Override
        public WriteIo createNew(Path path) throws IOException {
            return new ChannelOutput(FileChannel.open(path, CREATE_NEW, WRITE));
        }

        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    };

    private SsTableWriter() {
    }

    /**
     * 写入新文件，完整内容写完、force(true) 和关闭全部成功后才返回。
     * 父目录须已存在，不能覆盖已有目标；调用方须独占该路径，不得由其他实例修改。
     * 创建后失败会尝试删除本次文件，清理错误以 suppressed 保留。
     * 进程崩溃仍可能遗留半成品；不提供文件集发布或父目录项断电持久性保证。
     *
     * @throws NullPointerException 如果 path、records 或输入中的记录为 null
     * @throws IllegalArgumentException 如果输入键重复、逆序或记录超过上限
     * @throws IOException 如果创建、写入、同步、关闭失败，或索引超过容量上限
     */
    public static void write(Path path, Iterable<SsTableRecord> records) throws IOException {
        write(path, records, FILES);
    }

    /** 包内故障注入入口；createNew 成功返回后，输出资源的所有权交给本方法。 */
    static void write(Path path, Iterable<SsTableRecord> records, FileOps files) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(files, "files");
        // 创建失败不能进入清理分支，尤其不能误删原本已存在的目标。
        WriteIo output = files.createNew(path);
        try (output) {
            new Builder(output).write(records);
            output.force();
        } catch (IOException | RuntimeException | Error failure) {
            // try-with-resources 已先尝试关闭；删除失败不能掩盖原始失败。
            try {
                files.delete(path);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /** 仅暴露写入所需操作，避免将本阶段扩展成通用文件抽象。 */
    interface WriteIo extends Closeable {
        int write(ByteBuffer source, long offset) throws IOException;

        void force() throws IOException;
    }

    /** createNew 失败时必须自行释放资源；返回成功才代表本次拥有新路径。 */
    interface FileOps {
        WriteIo createNew(Path path) throws IOException;

        void delete(Path path) throws IOException;
    }

    private record ChannelOutput(FileChannel channel) implements WriteIo {
        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            return channel.write(source, offset);
        }

        @Override
        public void force() throws IOException {
            channel.force(true);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static final class Builder {
        private final WriteIo output;
        private final List<SsTableRecord> block = new ArrayList<>();
        private final List<IndexEntry> index = new ArrayList<>();
        private long offset;
        private int blockPayloadSize = SsTableBlockCodec.COUNT_SIZE;
        private long indexPayloadSize = Integer.BYTES;
        private ByteKey previousKey;
        private int lastKeyLength;

        private Builder(WriteIo output) {
            this.output = output;
        }

        private void write(Iterable<SsTableRecord> records) throws IOException {
            append(SsTableMetadataCodec.encodeHeader());
            for (SsTableRecord record : records) {
                Objects.requireNonNull(record, "record");
                if (previousKey != null && previousKey.compareTo(record.key()) >= 0) {
                    throw new IllegalArgumentException("SSTable keys must be strictly increasing");
                }
                int keyLength = record.key().toByteArray().length;
                int valueLength = record.entry() instanceof Value value ? value.toByteArray().length : 0;
                long recordSize = (long) SsTableBlockCodec.RECORD_HEADER_SIZE + keyLength + valueLength;
                if (recordSize > SsTableBlockCodec.MAX_RECORD_SIZE) {
                    throw new IllegalArgumentException("SSTable record exceeds size limit");
                }
                if (!block.isEmpty() && blockPayloadSize + recordSize > TARGET_BLOCK_PAYLOAD_SIZE) {
                    flushBlock();
                }
                block.add(record);
                blockPayloadSize += (int) recordSize;
                previousKey = record.key(); // 不能在 flushBlock 时重置，否则会漏掉跨块逆序。
                lastKeyLength = keyLength;
            }
            flushBlock();
            long indexOffset = offset;
            byte[] indexFrame = SsTableMetadataCodec.encodeIndex(index, indexOffset);
            byte[] footer = SsTableMetadataCodec.encodeFooter(new Footer(indexOffset, indexFrame.length));
            append(indexFrame);
            append(footer);
        }

        private void flushBlock() throws IOException {
            if (block.isEmpty()) {
                return;
            }
            long nextIndexSize = indexPayloadSize + 16L + lastKeyLength;
            if (nextIndexSize > SsTableMetadataCodec.MAX_INDEX_PAYLOAD_SIZE) {
                throw new IOException("SSTable index exceeds payload limit");
            }
            byte[] frame = SsTableBlockCodec.encode(block);
            long blockOffset = offset;
            append(frame);
            index.add(new IndexEntry(block.getLast().key(), blockOffset, frame.length));
            indexPayloadSize = nextIndexSize;
            block.clear();
            blockPayloadSize = SsTableBlockCodec.COUNT_SIZE;
        }

        private void append(byte[] bytes) throws IOException {
            if (bytes.length > Long.MAX_VALUE - offset) {
                throw new IOException("SSTable file offset would overflow");
            }
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                int written = output.write(source, offset);
                if (written <= 0) {
                    throw new IOException("SSTable write made no progress");
                }
                offset += written;
            }
        }
    }
}
