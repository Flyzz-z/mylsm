## 1. 设计与组织

- [x] 1.1 [共同设计] 明确三个操作、单线程边界、查询三态及延后遍历的范围
- [x] 1.2 [助手支持] 按 model 与 memtable 划分包，迁移基础类型与原有测试

## 2. 实现与测试

- [x] 2.1 [AI 实现] 编写 MemTable 接口与 TreeMap 实现，拒绝 null 并保留 tombstone
- [x] 2.2 [AI 实现] 编写可复用契约测试，覆盖状态转换、键内容身份、参数错误和数据隔离

## 3. 验证与复盘

- [x] 3.1 [助手支持] 完成针对性测试、完整构建和规范校验
- [x] 3.2 [助手支持] 更新学习笔记及进度，归档并提交

## 验证记录

- `./mvnw -B -ntp -Dtest=TreeMapMemTableTest test`：12 个内存表契约测试通过。
- `./mvnw -B -ntp clean verify`：28 个测试全部通过，包迁移后的旧模型测试与新增测试均执行，打包成功。
- `openspec validate --all --strict --no-interactive`：通过。
- 学习笔记见 `docs/memtable.md`。阶段 1 的有序遍历尚未实现，不将本次完成等同于完整 LSM 或完整内存表阶段完成。
