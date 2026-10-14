package io.github.flyzz.mylsm.memtable;

class TreeMapMemTableTest extends MemTableContractTest {
    @Override
    protected MemTable createMemTable() {
        return new TreeMapMemTable();
    }
}
