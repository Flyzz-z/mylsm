package io.github.flyzz.mylsm.lsm;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Manifest 专用的包内 I/O 边界；可在真实文件上注入短读写、同步和清理失败。 */
interface ManifestIo extends Closeable {
    long size() throws IOException;

    int read(ByteBuffer target, long offset) throws IOException;

    int write(ByteBuffer source, long offset) throws IOException;

    void truncate(long size) throws IOException;

    void force() throws IOException;
}
