package io.github.flyzz.mylsm.model;

/**
 * 一个键对应的记录：字节值或删除标记。
 * 未找到不是记录类型，由查询结果的 Optional.empty() 表示。
 */
public sealed interface Entry permits Value, Tombstone {
}
