package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Value;

import java.util.List;
import java.util.Optional;

/**
 * 仅供单线程访问的内存表，每个键保留最新的值或删除标记。
 * 不提供持久化保证；不同实现应遵守相同的键内容与查询语义。
 */
public interface MemTable {
    /**
     * 写入值，覆盖该键之前的值或删除标记。
     *
     * @throws NullPointerException 如果 key 或 value 为 null；失败时表状态不变
     */
    void put(ByteKey key, Value value);

    /**
     * 返回当前值或删除标记；empty 仅表示当前表没有该键的记录。
     * 查询不修改表，返回的记录不可变。
     *
     * @throws NullPointerException 如果 key 为 null
     */
    Optional<Entry> get(ByteKey key);

    /**
     * 保存删除标记，即使当前表没有该键。重复删除仍保留标记。
     *
     * @throws NullPointerException 如果 key 为 null；失败时表状态不变
     */
    void delete(ByteKey key);

    /**
     * 返回按键自然顺序升序排列的不可修改快照，每键仅有当前最新记录，包含删除标记。
     * 后续表修改不影响已返回的快照；此操作不修改或冻结表。
     * 空表返回空列表。调用仍须遵守本接口的单线程约束。
     */
    List<MemTableRecord> snapshot();
}
