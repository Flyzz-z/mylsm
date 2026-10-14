package io.github.flyzz.mylsm.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** 包内文件 I/O 边界，便于在真实文件通道上注入短读写和故障。 */
interface WalIo extends Closeable {
    long size() throws IOException;

    int read(ByteBuffer target, long offset) throws IOException;

    int write(ByteBuffer source, long offset) throws IOException;

    /** 同步文件内容和相关元数据。 */
    void force() throws IOException;
}
