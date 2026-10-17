package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * 单线程流式归并，输入 Reader 按从旧到新排列，同键仅保留最新记录（含 Tombstone）。
 * 借用 Reader 并独占其游标；调用方不得在归并期间操作或关闭输入 Reader。
 */
public final class SsTableMerger implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }

    private final Source[] sources;
    private final PriorityQueue<Source> heads;
    private State state = State.OPEN;
    private boolean initialized;
    private SsTableRecord current;

    private SsTableMerger(int sourceCount) {
        sources = new Source[sourceCount];
        heads = new PriorityQueue<>(Math.max(1, sourceCount),
                Comparator.comparing(source -> source.record.key()));
    }

    /**
     * 获取每个 Reader 的独占游标，但不读取数据块；输入可为空或包含空表。
     * 失败会释放本次已取得的游标，不关闭 Reader，也不释放调用方原有的游标。
     *
     * @throws NullPointerException 如果列表或其中的 Reader 为 null
     * @throws IllegalStateException 如果输入 Reader 不可用，或相同 Reader 被重复传入
     */
    public static SsTableMerger open(List<SsTableReader> oldestToNewest) {
        // 先完整检查并复制参数，不能因后面的 null 而取得前面的 Reader 占用。
        List<SsTableReader> readers = List.copyOf(Objects.requireNonNull(oldestToNewest, "oldestToNewest"));
        SsTableMerger merger = new SsTableMerger(readers.size());
        try {
            for (int i = 0; i < readers.size(); i++) {
                merger.sources[i] = new Source(i, readers.get(i).openCursor());
            }
            return merger;
        } catch (RuntimeException | Error failure) {
            merger.close();
            throw failure;
        }
    }

    /**
     * 推进到下一个唯一键；相同键的所有输入均成功推进后才暴露胜者。
     * 首次调用初始化各输入头，之后只推进刚消费的输入；正常耗尽重复返回 false。
     * 文件错误或输入生命周期错误会使归并器失效并释放所有自有游标。
     *
     * @throws IOException 如果任一输入数据块读取或验证失败
     * @throws IllegalStateException 如果归并器已关闭、已失败或输入被外部关闭
     */
    public boolean next() throws IOException {
        requireOpen();
        current = null;
        try {
            if (!initialized) {
                for (Source source : sources) {
                    advance(source);
                }
                initialized = true;
            }
            if (heads.isEmpty()) {
                return false;
            }
            ByteKey key = heads.peek().record.key();
            SsTableRecord winner = null;
            int newest = -1;
            do {
                Source source = heads.remove();
                if (source.generation > newest) {
                    newest = source.generation;
                    winner = source.record;
                }
                // 先出堆再改变头记录；堆内元素的排序键不能原地修改。
                advance(source);
            } while (!heads.isEmpty() && heads.peek().record.key().equals(key));
            current = winner;
            return true;
        } catch (IOException | RuntimeException | Error failure) {
            state = State.FAILED;
            releaseInputs();
            throw failure;
        }
    }

    /** 返回最近一次成功推进的不可变记录；推进前、耗尽后、失败或关闭后均拒绝。 */
    public SsTableRecord record() {
        requireOpen();
        if (current == null) {
            throw new IllegalStateException("SSTable merger has no current record");
        }
        return current;
    }

    /** 释放所有自有游标及头记录，不关闭输入 Reader；重复关闭安全。 */
    @Override
    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            releaseInputs();
        }
    }

    private void advance(Source source) throws IOException {
        if (source.cursor.next()) {
            source.record = source.cursor.record();
            heads.add(source);
        } else {
            source.record = null;
        }
    }

    private void releaseInputs() {
        current = null;
        heads.clear();
        for (int i = 0; i < sources.length; i++) {
            Source source = sources[i];
            if (source != null) {
                source.cursor.close();
                source.record = null;
                sources[i] = null;
            }
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("SSTable merger is " + state);
        }
    }

    private static final class Source {
        private final int generation;
        private final SsTableReader.Cursor cursor;
        private SsTableRecord record;

        private Source(int generation, SsTableReader.Cursor cursor) {
            this.generation = generation;
            this.cursor = cursor;
        }
    }
}
