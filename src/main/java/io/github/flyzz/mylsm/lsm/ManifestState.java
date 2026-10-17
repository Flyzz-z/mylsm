package io.github.flyzz.mylsm.lsm;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 一个完整文件集快照。SSTable 编号按从旧到新排列，不按编号排序。
 * 构造器验证单个状态；历史中的位置另由 requireInitial/requireSuccessor 验证。
 */
record ManifestState(long revision, long activeWalId, long frozenWalId,
                     long frozenWalLength, List<Long> sstIds) {
    private static final int WAL_HEADER_SIZE = 8;

    ManifestState {
        Objects.requireNonNull(sstIds, "sstIds");
        // 在复制列表或按数量分配校验数组之前限制容量。
        if (sstIds.size() > ManifestCodec.MAX_SSTABLE_COUNT) {
            throw new IllegalArgumentException("Manifest state exceeds payload limit");
        }
        sstIds = List.copyOf(sstIds);
        if (revision <= 0 || activeWalId <= 0 || frozenWalId < 0) {
            throw new IllegalArgumentException("Manifest revision and file IDs must be positive");
        }
        if (frozenWalId == 0 ? frozenWalLength != 0
                : frozenWalLength < WAL_HEADER_SIZE || frozenWalId == activeWalId) {
            throw new IllegalArgumentException("Invalid frozen WAL identity or sealed length");
        }

        // 原始长整数副本只用于查重，避免大清单另建装箱哈希集合；不改变版本顺序。
        long[] ids = new long[sstIds.size()];
        for (int i = 0; i < ids.length; i++) {
            long id = sstIds.get(i);
            if (id <= 0 || id == activeWalId || id == frozenWalId) {
                throw new IllegalArgumentException("Invalid or overlapping SSTable ID");
            }
            ids[i] = id;
        }
        Arrays.sort(ids);
        for (int i = 1; i < ids.length; i++) {
            if (ids[i - 1] == ids[i]) {
                throw new IllegalArgumentException("Duplicate SSTable ID");
            }
        }
    }

    /** 首条记录只能描述一个空数据库及其初始活跃 WAL。 */
    void requireInitial() {
        if (revision != 1 || frozenWalId != 0 || !sstIds.isEmpty()) {
            throw new IllegalArgumentException("Invalid initial Manifest state");
        }
    }

    /**
     * 只接受日志轮换、单个冻结代发布或全量 SSTable 替换，不修改任一状态。
     * 压缩保持日志状态，输出大于全部输入 SST 编号；允许小于期间新建的 WAL 编号。
     * 沿合法历史，被移除的引用编号均不超过 SST 上界，配合当前引用查重拒绝历史复用。
     */
    void requireSuccessor(ManifestState next) {
        Objects.requireNonNull(next, "next");
        if (revision == Long.MAX_VALUE || next.revision != revision + 1) {
            throw new IllegalArgumentException("Manifest revisions must be consecutive without overflow");
        }
        if (sstIds.size() >= 2 && next.sstIds.size() == 1) {
            if (next.activeWalId != activeWalId || next.frozenWalId != frozenWalId
                    || next.frozenWalLength != frozenWalLength || next.sstIds.getFirst() <= maxSstId()) {
                throw new IllegalArgumentException("Invalid Manifest full compaction replacement");
            }
            return;
        }
        long maxId = maxFileId();
        if (frozenWalId == 0) {
            if (next.frozenWalId != activeWalId || next.activeWalId <= maxId
                    || !next.sstIds.equals(sstIds)) {
                throw new IllegalArgumentException("Invalid Manifest WAL rotation");
            }
        } else {
            if (next.activeWalId != activeWalId || next.frozenWalId != 0
                    || next.sstIds.size() != sstIds.size() + 1
                    || !next.sstIds.subList(0, sstIds.size()).equals(sstIds)
                    || next.sstIds.getLast() <= maxId) {
                throw new IllegalArgumentException("Invalid Manifest SSTable publication");
            }
        }
    }

    /** 这是有效引用的上界；文件分配器以后还须考虑目录中的孤儿编号。 */
    long maxFileId() {
        return Math.max(Math.max(activeWalId, frozenWalId), maxSstId());
    }

    private long maxSstId() {
        long max = 0;
        for (long id : sstIds) {
            max = Math.max(max, id);
        }
        return max;
    }
}
