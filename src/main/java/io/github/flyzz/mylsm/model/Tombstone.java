package io.github.flyzz.mylsm.model;

/**
 * 无载荷的删除标记，所有键共享同一实例。
 * 标记不包含键；具体删除哪个键由存储表中的映射关系决定。
 */
public enum Tombstone implements Entry {
    INSTANCE
}
