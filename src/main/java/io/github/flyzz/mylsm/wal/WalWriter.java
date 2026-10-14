package io.github.flyzz.mylsm.wal;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 单线程 WAL 同步写入器；调用方必须独占该文件，不提供锁和并发安全。
 * 当前仅打开空日志，已有操作记录的日志须等待恢复扫描实现后再打开。
 */
public final class WalWriter implements AutoCloseable {
    static final int HEADER_SIZE = 8;
    private static final int MAGIC = 0x4d59574c; // ASCII MYWL
    private static final int VERSION = 1;

    private enum State { OPEN, FAILED, CLOSED }

    private final WalIo io;
    private State state = State.OPEN;
    private long appendOffset = HEADER_SIZE;

    private WalWriter(WalIo io) {
        this.io = io;
    }

    /**
     * 打开新建、零字节或仅有合法文件头的日志；父目录必须存在。
     * 新文件头在返回前同步；拒绝非法文件或含操作记录的日志，不修改其内容。
     *
     * @throws NullPointerException 如果 path 为 null
     * @throws IOException 如果初始化、校验或文件 I/O 失败，或文件需要恢复
     */
    public static WalWriter openEmpty(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return openEmpty(FileChannelWalIo.open(path));
    }

    /** 接管 io 的所有权，包括打开失败时的关闭责任。 */
    static WalWriter openEmpty(WalIo io) throws IOException {
        Objects.requireNonNull(io, "io");
        try {
            long size = io.size();
            if (size == 0) {
                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
                header.putInt(MAGIC).putInt(VERSION).flip();
                writeFully(io, header, 0);
                io.force();
            } else {
                if (size < HEADER_SIZE) {
                    throw new EOFException("Incomplete WAL file header");
                }
                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
                readFully(io, header, 0);
                header.flip();
                if (header.getInt() != MAGIC) {
                    throw new IOException("Invalid WAL file magic");
                }
                int version = header.getInt();
                if (version != VERSION) {
                    throw new IOException("Unsupported WAL version: " + version);
                }
                if (size > HEADER_SIZE) {
                    throw new IOException("WAL contains records; recovery required before appending");
                }
            }
            return new WalWriter(io);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                io.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 完整追加并同步一条记录，成功后才返回；编码错误不影响实例可用性。
     * I/O 失败不保证记录不存在，且当前实例之后仅允许关闭。
     *
     * @throws IllegalStateException 如果实例已失败或关闭
     * @throws NullPointerException 如果 record 为 null
     * @throws IllegalArgumentException 如果记录载荷超限
     * @throws IOException 如果追加或同步失败
     */
    public void append(WalRecord record) throws IOException {
        requireOpen();
        ByteBuffer frame = ByteBuffer.wrap(WalCodec.encode(record));
        try {
            writeFully(io, frame, appendOffset);
            io.force();
            appendOffset += frame.limit();
        } catch (IOException failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /** 重复关闭安全；即使底层关闭报错，此实例也不再接受追加。 */
    @Override
    public void close() throws IOException {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            io.close();
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("WAL writer is " + state);
        }
    }

    private static void writeFully(WalIo io, ByteBuffer source, long offset) throws IOException {
        while (source.hasRemaining()) {
            int written = io.write(source, offset);
            if (written <= 0) {
                throw new IOException("WAL write made no progress at offset " + offset);
            }
            offset += written;
        }
    }

    private static void readFully(WalIo io, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = io.read(target, offset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF in WAL file header at offset " + offset);
            }
            if (read == 0) {
                throw new IOException("WAL read made no progress at offset " + offset);
            }
            offset += read;
        }
    }
}
