package io.github.flyzz.mylsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** 不使用 TRUNCATE_EXISTING 或 APPEND，所有位置由调用方明确指定。 */
final class FileChannelWalIo implements WalIo {
    private final FileChannel channel;

    private FileChannelWalIo(FileChannel channel) {
        this.channel = channel;
    }

    static FileChannelWalIo open(Path path) throws IOException {
        return new FileChannelWalIo(FileChannel.open(path, CREATE, READ, WRITE));
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public int read(ByteBuffer target, long offset) throws IOException {
        return channel.read(target, offset);
    }

    @Override
    public int write(ByteBuffer source, long offset) throws IOException {
        return channel.write(source, offset);
    }

    @Override
    public void truncate(long size) throws IOException {
        channel.truncate(size);
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
