# 迁移游标设计指南

[中文](migration-cursor-design.zh-CN.md) | [English](migration-cursor-design.en.md)

## 这份文档适合什么时候看

只有当你已经准备执行迁移，并且游标不想直接使用 `id` 时，再看这份文档。

如果只是第一次接入，请先看：

- [快速使用指南](quick-start.zh-CN.md)
- [持久层加密指南](persistence-encryption-guide.zh-CN.md)
- [存量迁移指南](migration-guide.zh-CN.md)

## 目标

迁移模块使用基于游标的 seek 分页来分批扫描主表数据。游标配置是否合理，直接决定：

- 迁移是否会漏数
- 断点恢复后是否还能继续
- 写后校验是否能准确回查到主表记录
- 非 `id` 游标场景下 JDBC 绑定是否稳定

这份文档专门说明游标的设计原则、推荐方案、反例和排查方法。

## 游标必须满足的条件

游标列应同时满足以下要求：

- 稳定：迁移执行期间不会被业务更新、触发器更新或迁移本身改写
- 可排序：数据库能够稳定按该列或该列组合做升序 seek 分页
- 可回查：写后校验能用同一组游标值准确定位主表记录
- 尽量唯一：如果单列不唯一，应升级为复合游标

如果游标列命中了迁移时会写入的主表列，计划构建阶段会直接失败并抛出：

- `CURSOR_COLUMN_MUTABLE`

## 推荐优先顺序

推荐的游标优先顺序：

1. 主键 `id`
2. 不可变业务唯一键，例如 `record_no`
3. 复合游标，例如 `created_at + id`
4. 多租户复合游标，例如 `tenant_id + biz_no`

## 推荐示例

### 1. 单列主键表

最稳妥的场景，直接使用：

```yaml
mybatis:
  encrypt:
    migration:
      default-cursor-columns:
        - id
```

### 2. 单列业务唯一键表

如果主表没有技术主键，但有不可变且唯一的业务键：

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "user_account"
          cursor-columns:
            - record_no
```

适用前提：

- `record_no` 唯一
- `record_no` 不会被业务更新
- 迁移过程不会写这个列

### 3. 时间列不足以保证唯一

仅使用 `created_at` 往往不够安全，因为同一秒或同一毫秒可能有多条数据。推荐：

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "order_account"
          cursor-columns:
            - created_at
            - id
```

### 4. 多租户业务表

如果排序和定位依赖租户维度，推荐：

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "order_*"
          cursor-columns:
            - tenant_id
            - biz_no
```

如果 `biz_no` 仍不能保证唯一，再补一列：

```yaml
cursor-columns:
  - tenant_id
  - biz_no
  - id
```

## 不推荐作为游标的字段

以下字段不建议作为游标，很多场景下现在会直接 fail-fast：

- 会被迁移覆盖的源列，例如 `phone`、`id_card`
- 派生写入列，例如 `phone_hash`、`phone_like`、`storageColumn`
- 独立表引用写回列
- 可被业务更新的状态列、排序列、名称列
- 未补零但按数值语义递增的字符串编号，例如 `order_no`

## 为什么单列非唯一游标会漏数

迁移分页使用的是 seek 分页，不是 offset 分页。单列游标 `record_no` 的下一批查询语义通常是：

```sql
where record_no > ?
order by record_no asc
limit ?
```

如果表里有两条记录：

- `record_no = 100, id = 1`
- `record_no = 100, id = 2`

第一条处理完后，下一批条件会变成 `record_no > 100`，第二条就被跳过去了。

所以：

- 单列游标若不能保证唯一，必须升级为复合游标

## 非 `id` 游标的 JDBC 类型问题

即使数据库里“同样 SQL、同样值”手工能查到，程序里也可能因为 JDBC 参数绑定差异导致 `resultSet.next()` 为 `false`。

高风险类型包括：

- `timestamp` / `datetime`
- `decimal` / `number`
- `char` / 定长字符串
- 业务字符串编号

迁移模块现在已经改成类型感知绑定，不再单纯依赖 `setObject(...)`，但仍建议：

- 优先用 `id`
- 时间列必须尽量与唯一键组合
- 字符串业务号要确认数据库比较规则和业务排序语义一致

## Debug 排查

排查游标问题时，打开 `debug` 日志，重点关注：

- `migration-read-batch`
- `migration-load-current-row`
- `migration-update-main-row`
- `migration-verify-main-row`

这些日志会带上：

- 当前 SQL
- 当前游标值
- 每个游标值对应的 Java 类型

可用于确认：

- 是不是旧 checkpoint 已经把数据越过去了
- JDBC 绑定类型是否和数据库列类型一致
- verifier 回查时是否仍然使用了旧游标值

## 推荐配置模板

一套比较实用的模板如下：

```yaml
mybatis:
  encrypt:
    migration:
      default-cursor-columns:
        - id
      cursor-rules:
        - table-pattern: "user_account"
          cursor-columns:
            - record_no
        - table-pattern: "order_*"
          cursor-columns:
            - tenant_id
            - biz_no
            - id
      checkpoint-directory: migration-state
      batch-size: 500
      verify-after-write: true
```

## 结论

游标设计的核心原则很简单：

- 能用 `id` 就别换
- 单列不唯一就上复合游标
- 不要用会变的列
- 时间列几乎总要和唯一键组合
- 遇到问题优先看 checkpoint 和 debug 日志
