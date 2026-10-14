## 1. 记录设计

- [x] 1.1 [共同设计] 确定 Entry 的两种记录状态与未来 Optional 查询三态，记录规范和取舍

## 2. 实现与测试

- [x] 2.1 [AI 实现] 实现 sealed Entry、不可变 Value 和单成员 Tombstone
- [x] 2.2 [AI 实现] 验证值的数组隔离、内容相等、空值和 null 边界，以及空值与删除标记的区分

## 3. 验证与复盘

- [x] 3.1 [助手支持] 运行针对性测试、完整构建与严格规范校验
- [x] 3.2 [助手支持] 更新学习笔记和进度，解释 sealed、单成员枚举与 Optional 的职责，归档并提交

## 验证记录

- `./mvnw -B -ntp -Dtest=ValueTest test`：6 个针对性测试通过。
- `./mvnw -B -ntp clean verify`：全部 16 个测试通过，构建成功。
- `openspec validate --all --strict --no-interactive`：通过。
- 学习记录见 `docs/entry-model.md`；本次未实现 MemTable 或查询方法。
