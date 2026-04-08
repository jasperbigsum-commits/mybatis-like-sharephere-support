# 独立表增量历史表设计

## 背景

当前独立表模式把独立加密表当作“当前态镜像表”使用：主表 `INSERT` / `UPDATE` 后，插件会删除旧行再插入新行；查询条件会直接匹配独立表中的现存记录；结果回填也默认读取当前唯一一行。这与新的业务要求不一致：独立表模式下不再用业务主键关联独立表，而是让主表加密字段保存独立表记录 id，并以该 id 取回独立表中的最新加密值。

## 目标

将独立表模式改为**独立记录引用**语义：

- 移除 `sourceIdColumn` 概念，不再使用业务 id 关联独立表
- `storageIdColumn` 改为描述独立表主键列，默认仍为 `id`
- 主表加密字段在写入时不再被直接丢弃，而是改写为独立表记录 id
- `INSERT`：先插入独立表记录，再把生成的独立表 id 写入主表加密字段对应列
- `UPDATE`：更新已引用的独立表记录中的加密值；若主表当前无独立表 id，则新建独立表记录并把新 id 回写到主表字段
- `SELECT` 结果回填：读取主表字段中的独立表 id，再到独立表按该 id 取密文并解密
- 基于独立表加密字段的条件查询：改写为通过独立表查询命中记录 id，再与主表字段保存的独立表 id 进行关联

## 独立表主键判定

采用独立表主键列作为引用键，默认列名为 `id`。

约束如下：

- `storageIdColumn` 表示独立表主键列，例如 `id`
- 主表原业务加密字段对应列改为保存独立表 id，而不是业务明文或业务主键
- 不再要求业务主键唯一，也不依赖业务主键参与独立表读写
- 独立表每条记录的唯一性仅由 `storageIdColumn` 保证

## 设计范围

### 1. 元数据模型扩展

扩展独立表规则，移除 `sourceIdColumn` / `sourceIdProperty` 的独立表语义，只保留独立表主键列配置，默认值为 `id`。

影响点：

- `DatabaseEncryptionProperties.FieldRuleProperties`
- `EncryptColumnRule`
- `EncryptMetadataRegistry`

设计要求：

- 仅对 `SEPARATE_TABLE` 生效
- 未配置时默认按独立表主键列名 `id` 处理
- 保持现有注解/配置兼容，但独立表模式下不再依赖业务 id 推断或校验

## 2. 写入链路调整

`SeparateTableEncryptionManager.synchronizeAfterWrite(...)` 需要从“按业务 id 覆盖同步”改为“按主表保存的独立表 id 建立引用并同步独立表记录”。

具体规则：

- `INSERT`：
  - 若主表传入明文，则先插入独立表记录
  - 获取生成的独立表 id
  - 将主表对应加密字段值改写为该独立表 id
- `UPDATE`：
  - 若主表当前字段已持有独立表 id，则更新该独立表 id 对应记录的密文/辅助列/LIKE 列
  - 若主表当前字段没有独立表 id，则先插入独立表记录，再把新独立表 id 写回主表字段
- `DELETE`：不额外删除独立表记录
- 当独立表字段明文值为 `null` 时，不新建独立表记录；更新时仅在明确有已引用独立表 id 时决定是否清空或跳过，具体实现保持最小变更并以测试固定

保留内容：

- 仍写入 `storageColumn`
- 仍写入 `assistedQueryColumn`
- 若存在 `likeQueryColumn`，仍写入该列

去除内容：

- 不再通过业务 id 删除再插入独立表记录
- `DELETE` 时不再删除独立表记录

## 3. 查询改写调整

`SqlRewriteEngine` 中独立表字段的 `=` / `LIKE` / `IS NULL` 相关查询，需要从“按业务 id 关联独立表”改为“按主表字段保存的独立表 id 关联独立表”。

### 等值 / LIKE 查询

现有逻辑会生成 `EXISTS` 子查询，后续需改为基于主表字段值与独立表主键列相等的关联。

目标语义：

```sql
exists (
  select 1
  from storage_table st
  where st.id = main.encrypted_field
    and st.query_column = ?
)
```

要求：

- 等值查询使用 `assistedQueryColumn`
- LIKE 查询使用 `likeQueryColumn`
- 关联条件基于主表逻辑字段改写后的“独立表 id 引用值”与 `storageIdColumn` 相等
- 继续保留现有 prepared parameter 改写逻辑

### `IS NULL` / `IS NOT NULL`

改造后语义变为基于主表字段中的独立表 id 是否存在：

- `IS NOT NULL`：主表字段保存了独立表 id，且该 id 在独立表存在记录
- `IS NULL`：主表字段没有独立表 id，或引用 id 在独立表中不存在记录

## 4. 结果回填调整

`SeparateTableEncryptionManager.hydrateResults(...)` 需要从“按业务 id 批量取最新记录”改为“读取主表字段上的独立表 id，再按该 id 查独立表记录”。

目标语义：

```sql
select st.id, st.storage_column
from storage_table st
where st.id in (?, ?, ...)
```

回填规则：

- 主表逻辑字段在数据库中实际保存独立表 id
- 结果回填时先读取该 id，再查询独立表密文列
- 解密后把明文写回实体属性
- 若引用 id 不存在独立表记录，则保持空值或按现有最小兼容行为处理，并用测试固定

## 5. 测试策略

### 集成测试

在 `MybatisEncryptionIntegrationTest` 中新增或调整独立表引用场景：

1. 插入主表时，独立表新增一条记录，主表字段保存该独立表 id
2. 更新同一主表记录时，若已存在独立表 id，则更新该独立表记录的密文值
3. 查询实体时，能够通过主表保存的独立表 id 回填并解密
4. 按独立表加密字段查询时，能够通过独立表条件命中主表记录
5. 删除主表记录后，独立表记录仍保留

测试表结构需改为：

- 主表加密字段列类型改为可保存独立表 id
- 独立表主键列使用 `id`
- 不再包含业务 id 关联列

### SQL 改写单元测试

在 `SqlRewriteEngineTest` 中补充断言：

- 独立表等值查询生成 `st.id = main.encrypted_field` 形式的 `EXISTS`
- 独立表 LIKE 查询生成相同引用关联形式的 `EXISTS`
- 必要时补充 `IS NULL` / `IS NOT NULL` 针对独立表引用 id 的行为验证

## 6. 文档更新

需要同步修正文档，避免继续把独立表描述为“业务 id 关联的外部表”：

- `docs/architecture.md`
- `docs/sql-support-matrix.md`
- `README.md`

更新重点：

- 独立表模式下移除 `sourceIdColumn` 语义
- `storageIdColumn` 表示独立表主键列
- 主表加密字段存储独立表 id
- 示例 DDL 调整为主表引用独立表 id

## 非目标

本次不做以下扩展：

- 不引入业务 id 到独立表的回链字段
- 不实现历史版本链路
- 不新增独立表清理策略
- 不改变同表加密字段的现有语义
- 不扩展到复合主键或多列引用，本次仅支持单列 `storageIdColumn`

## 验收标准

满足以下条件即可认为设计达成：

1. 独立表模式下不再依赖 `sourceIdColumn`
2. 主表写入后保存的是独立表 id
3. 更新时能够复用或创建独立表记录并同步密文值
4. 查询回填能通过独立表 id 返回明文值
5. 独立表条件查询能够正确命中主表记录
6. 单元测试与集成测试覆盖新增语义
7. 文档与示例 DDL 与实现保持一致
