## Why

用户已选择直接采用字节模型，避免未来支持二进制数据时更换存储接口。先明确键的内容相等、排序和不可变性，为可替换的 MemTable 实现建立共同基础。

## What Changes

- 计划引入不可变 ByteKey，按内容判断相等，采用无符号字节字典序。
- 输入和输出数组均防御性复制，避免外部修改破坏有序索引。
- 通过边界和顺序性质测试理解键比较的契约。

## Capabilities

### New Capabilities
- `byte-key`: 不可变字节键、内容相等及统一排序规则。

### Modified Capabilities

无。

## Impact

后续新增 Java 基础模型与 JUnit 测试，不新增外部依赖。本次先完成设计记录，尚未实现代码。

## Non-goals

不在本变更中实现 MemTable、值包装、查询三态、WAL 或 SSTable；不定义业务数字与字符串的编码方案。学习目标是理解存储键的内容身份、排序与数据所有权。
