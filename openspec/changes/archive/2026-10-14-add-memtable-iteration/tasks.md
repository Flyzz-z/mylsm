## 1. 设计

- [x] 1.1 [共同设计] 明确复制快照、共享不可变组件与延后冻结的选择

## 2. 实现

- [x] 2.1 [AI 实现] 添加 MemTableRecord 与 snapshot 接口，完成 TreeMap 有序复制实现
- [x] 2.2 [AI 实现] 补充契约测试与 null 边界测试，验证排序、完整性、不可修改和快照隔离

## 3. 验证与记录

- [x] 3.1 [助手支持] 运行针对性测试、完整构建及规范严格校验
- [x] 3.2 [助手支持] 记录学习要点、更新项目进度、归档并提交

## 验证记录

- `./mvnw -B -ntp -Dtest=TreeMapMemTableTest,MemTableRecordTest test`：19 个针对性测试通过。
- `./mvnw -B -ntp clean verify`：35 个测试全部通过，打包成功。
- `openspec validate --all --strict --no-interactive`：通过。
- 复制成本、不可变组件共享与阶段 1 复盘见 `docs/memtable-snapshot.md`。没有实现冻结或持久化。
