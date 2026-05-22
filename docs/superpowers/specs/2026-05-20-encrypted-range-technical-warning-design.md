# 加密字段单边范围比较技术值放行设计

## 背景

当前仓库对加密字段的范围比较采用 fail-fast 策略，`>`、`>=`、`<`、`<=` 与 `BETWEEN` 都会被拒绝。  
这对大多数业务是安全的，但有一类游标分页场景会显式接受“技术排序值”而不是“明文业务语义”：只要加密字段配置了稳定的 hash / reference 列，游标查询就可以继续按该技术值做单边比较，只是结果顺序不再代表源字段的明文排序规则。

## 目标

- 仅放宽单边范围比较：`>`、`>=`、`<`、`<=`
- 保留 `BETWEEN` 的拒绝策略
- same-table 加密字段继续按 `assistedQueryColumn` 改写
- separate-table 加密字段继续按主表 reference/hash 列改写
- 所有允许的范围比较都记录 warning，明确结果只代表技术值排序，不代表明文业务排序
- 如果字段没有可用的 assisted/reference 列，仍然 fail fast

## 非目标

- 不放宽 `BETWEEN`
- 不引入明文范围语义
- 不改变 `ORDER BY`、`GROUP BY`、窗口函数、聚合等其他边界
- 不新增新的配置项

## 设计

### 1. 校验层

`SqlRewriteValidator` 不再把单边范围比较当作必须拒绝的能力边界。  
`BETWEEN` 仍保留校验失败，因为它表达的是闭区间业务语义，不适合和单边游标比较等价处理。

### 2. 改写层

`SqlConditionRewriter` 继续识别 `GreaterThan`、`GreaterThanEquals`、`MinorThan`、`MinorThanEquals`，但不再直接抛出 `UNSUPPORTED_ENCRYPTED_RANGE`。  
改写规则保持和现有等值/IN 一致的技术列选择逻辑：

- same-table：比较列改写为 `assistedQueryColumn`
- separate-table：比较列改写为主表 reference/hash 列
- 右侧操作数继续走现有参数/字面量转换链路

### 3. warning 语义

`SqlRewriteEngine` 增加范围比较的 warning 输出，风格与 `ORDER BY` / 技术聚合一致：

- 说明这是技术值比较
- 说明 same-table 使用的是 hash/assisted 值
- 说明 separate-table 使用的是 reference 值
- 说明结果不代表明文业务排序

### 4. 错误边界

若字段没有可用的 assisted/reference 列，仍保持失败：

- same-table 没有 `assistedQueryColumn`：继续拒绝
- separate-table 缺少必要的 reference 语义：继续拒绝

## 影响文件

- `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriter.java`
- `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java`
- `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidator.java`
- `common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriterTest.java`
- `common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidatorTest.java`
- `docs/sql-support-matrix.md`
- `docs/architecture.md` 仅在实现链路描述需要补充时微调

## 测试策略

### 单元测试

- 验证 `phone > ?`、`phone >= ?`、`phone < ?`、`phone <= ?` 不再抛出 `UNSUPPORTED_ENCRYPTED_RANGE`
- 验证改写后的 SQL 使用技术列而不是逻辑加密列
- 验证参数仍按现有机制转换为 hash/reference 值
- 验证 `BETWEEN` 仍然失败

### 集成回归

- 保留已有 MyBatis 路径的基本 rewrite 回归
- 若当前测试基线覆盖到范围比较的端到端路径，则同步补充一条游标场景

## 文档更新

`docs/sql-support-matrix.md` 需要从“范围查询一律拒绝”更新为：

- 单边比较在可用技术列存在时允许，但只代表技术值比较
- `BETWEEN` 继续拒绝

