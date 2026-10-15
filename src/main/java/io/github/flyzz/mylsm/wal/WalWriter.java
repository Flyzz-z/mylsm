package io.github.flyzz.mylsm.wal;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单线程 WAL 同步写入器；调用方必须独占该文件，不提供锁和并发安全。
 * 支持打开空日志，或恢复有效记录、修复不完整尾部后继续追加。
 */
public final class WalWriter implements AutoCloseable {
    static final int HEADER_SIZE = 8;
    private static final int MAGIC = 0x4d59574c; // ASCII MYWL
    private static final int VERSION = 1;

    private enum State { OPEN, FAILED, CLOSED }

    private final WalIo io;
    private State state = State.OPEN;
    private long appendOffset;

    private WalWriter(WalIo io, long appendOffset) {
        this.io = io;
        this.appendOffset = appendOffset;
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
        return open(io, false, null);
    }

    /**
     * 按文件顺序将完整有效记录交给 replay，必要时截断不完整尾部并同步，然后允许追加。
     * 新文件或零字节文件先初始化文件头；完整损坏记录导致失败且不截尾。
     * replay 应仅修改未对外暴露的临时状态：打开失败前可能已调用过部分回调，无法回滚。
     * 回调不得修改或重新打开同一日志；回调异常导致打开失败并关闭资源。
     *
     * @throws NullPointerException 如果 path 或 replay 为 null
     * @throws IOException 如果文件格式、记录校验、读取或尾部修复失败
     */
    public static WalWriter openRecovering(Path path, Consumer<WalRecord> replay) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(replay, "replay");
        return openRecovering(FileChannelWalIo.open(path), replay);
    }

    /** 接管 io 所有权；回调、扫描或修复失败时也负责关闭。 */
    static WalWriter openRecovering(WalIo io, Consumer<WalRecord> replay) throws IOException {
        return open(io, true, replay);
    }

    private static WalWriter open(WalIo io, boolean recovering, Consumer<WalRecord> replay)
            throws IOException {
        Objects.requireNonNull(io, "io");
        try {
            if (recovering) {
                Objects.requireNonNull(replay, "replay");
            }
            long size = initializeOrValidateHeader(io);
            if (!recovering && size > HEADER_SIZE) {
                throw new IOException("WAL contains records; recovery required before appending");
            }
            long appendOffset = recovering ? scanAndRepair(io, size, replay) : HEADER_SIZE;
            return new WalWriter(io, appendOffset);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                io.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static long initializeOrValidateHeader(WalIo io) throws IOException {
        long size = io.size();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        if (size == 0) {
            header.putInt(MAGIC).putInt(VERSION).flip();
            writeFully(io, header, 0);
            io.force();
            return HEADER_SIZE;
        }
        if (size < HEADER_SIZE) {
            throw new EOFException("Incomplete WAL file header");
        }
        readFully(io, header, 0);
        header.flip();
        if (header.getInt() != MAGIC) {
            throw new IOException("Invalid WAL file magic");
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported WAL version: " + version);
        }
        return size;
    }

    private static long scanAndRepair(WalIo io, long size, Consumer<WalRecord> replay)
            throws IOException {
        long validEnd = HEADER_SIZE;
        ByteBuffer prefix = ByteBuffer.allocate(WalCodec.PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN);
        while (validEnd < size) {
            long remaining = size - validEnd;
            if (remaining < WalCodec.PREFIX_SIZE) {
                break; // 不完整的最后一个前缀。
            }
            prefix.clear();
            readFully(io, prefix, validEnd);
            int payloadLength = prefix.getInt(0);
            if (payloadLength < WalCodec.MIN_PAYLOAD_SIZE || payloadLength > WalCodec.MAX_PAYLOAD_SIZE) {
                throw new IOException("Invalid WAL payload length at offset " + validEnd + ": " + payloadLength);
            }
            if (payloadLength > remaining - WalCodec.PREFIX_SIZE) {
                break; // 合法长度，但最后一条记录的载荷未写完整。
            }
            // 检查长度上限及剩余字节后才分配；扫描内存不随记录总数增长。
            byte[] frame = new byte[WalCodec.PREFIX_SIZE + payloadLength];
            System.arraycopy(prefix.array(), 0, frame, 0, WalCodec.PREFIX_SIZE);
            readFully(io, ByteBuffer.wrap(frame, WalCodec.PREFIX_SIZE, payloadLength),
                    validEnd + WalCodec.PREFIX_SIZE);
            WalRecord record;
            try {
                record = WalCodec.decode(frame);
            } catch (IOException failure) {
                throw new IOException("Invalid WAL record at offset " + validEnd + ": " + failure.getMessage(),
                        failure);
            }
            replay.accept(record);
            validEnd += frame.length; // 完整读取、校验并交给回调后，才确认新的有效边界。
        }
        if (validEnd < size) {
            io.truncate(validEnd);
            io.force(); // 截断成功且同步成功后，才能返回可追加的 writer。
        }
        return validEnd;
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
                throw new EOFException("Unexpected EOF in WAL at offset " + offset);
            }
            if (read == 0) {
                throw new IOException("WAL read made no progress at offset " + offset);
            }
            offset += read;
        }
    }
}
