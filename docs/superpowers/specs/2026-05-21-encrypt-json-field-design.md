# JSON 子路径加密设计

## 背景

当前仓库已经支持普通字段加密、独立表密文存储、查询条件改写、结果解密回填和迁移能力，但还不支持“主表字段是 JSON 字符串，且只对其中部分精确路径做加密”的场景。

这类场景有几个明确边界：

- 不能依赖对称可逆查询，因为用户可能使用非对称算法；
- 需要支持 `json_extract(json_col, '$.phone') = ?` 这类精确路径查询；
- 主表不能保留这些路径的明文；
- 路径与独立表绑定关系需要显式声明，不能靠 path 自动推断；
- 仓库整体仍保持 fail-fast，不对复杂 JSON 写法做“尽力支持”。

## 目标

- 新增 `@EncryptJsonField` / `@EncryptJsonPath` 注解模型。
- 支持一个实体存在多个 `@EncryptJsonField`。
- 支持一个 `@EncryptJsonField` 下存在多个 `@EncryptJsonPath`。
- 每个 `@EncryptJsonPath` 显式绑定独立表：
  - `storageTable`
  - `storageIdColumn`
  - `hashColumn`
  - `cipherColumn`
- 写入时把命中 path 的明文替换为 `hash`，并把密文写入对应独立表。
- 查询时支持精确路径 JSON 条件改写：
  - `json_extract(json_col, '$.path') = ?`
  - `json_extract(json_col, '$.path') != ?`
  - `json_extract(json_col, '$.path') in (...)`
- 读取结果时把主表 JSON 中的 hash 反查独立表密文并解密回填。
- 迁移模块支持把历史明文 JSON 转为“主表 hash JSON + 独立表密文”。

## 非目标

- 不支持 `JSON_SET`、`JSON_REPLACE`、`JSON_MERGE` 等局部 JSON 更新。
- 不支持动态 path、通配符、过滤表达式、递归 path、切片 path。
- 不支持 `LIKE`、范围比较、聚合、排序等 JSON 子路径加密条件的额外放宽。
- 不引入按“类型”归类的全局注册中心。

## 注解与元数据模型

### `@EncryptJsonField`

字段级注解，标在 `String` JSON 字段上，负责声明：

- `table`
- `column`
- `cipherAlgorithm`
- `assistedQueryAlgorithm`
- `paths`

其中：

- `table` / `column` 表示主表物理绑定；
- `cipherAlgorithm` / `assistedQueryAlgorithm` 是字段级默认算法；
- `paths` 为 `@EncryptJsonPath[]`。

### `@EncryptJsonPath`

子路径级注解，负责声明：

- `path`
- `storageTable`
- `storageIdColumn`
- `hashColumn`
- `cipherColumn`
- `cipherAlgorithm`
- `assistedQueryAlgorithm`

算法优先级：

1. `EncryptJsonPath` 显式配置
2. `EncryptJsonField` 字段默认
3. 全局默认

### 规则加载

`AnnotationEncryptMetadataLoader` 与 `EncryptMetadataRegistry` 需要新增 JSON 字段规则加载与校验能力：

- 校验 `@EncryptJsonField` 只能用于 `String` 字段；
- 校验 `path` 必须为精确路径；
- 校验 `storageTable/hashColumn/cipherColumn` 不能为空；
- 校验最终算法都可解析；
- 把普通字段规则与 JSON 子路径规则一起挂到实体级和表级元数据中。

## 运行时设计

### 写入路径

当 `INSERT` / `UPDATE` 命中 `@EncryptJsonField` 且该列按整列 JSON 字符串写入时：

1. 读取原始 JSON 字符串；
2. 解析为对象树；
3. 遍历已声明的精确 `path`；
4. 对每个命中 path：
   - 取明文值；
   - 计算 `hash`；
   - 加密得到密文；
   - 把主表 JSON 中该位置替换为 `hash`；
   - 按 `storageTable + hashColumn` 查重，不存在则插入一条 `storageIdColumn + hashColumn + cipherColumn`；
5. 将改写后的 JSON 字符串回写到主表列。

### 查询条件改写

仅支持安全且可静态识别的精确路径改写：

- `json_extract(json_col, '$.phone') = ?`
- `json_extract(json_col, '$.phone') != ?`
- `json_extract(json_col, '$.phone') in (...)`

改写原则：

- 左侧函数结构保持不变；
- 右侧参数或字面量按对应 `EncryptJsonPath` 的 `assistedQueryAlgorithm` 转为 `hash`；
- 改写后仍和主表 JSON 该位置上的 hash 比较；
- 如果 path 未注册、path 不是静态字面量、或条件形态不在支持矩阵内，直接 fail-fast。

### 结果回填与解密

查询结果返回后：

1. 命中 `@EncryptJsonField` 的属性先读取 JSON 字符串；
2. 解析 JSON；
3. 按 path 分组收集 hash 值；
4. 分别去各自 `storageTable` 按 `hashColumn -> cipherColumn` 批量回查；
5. 解密得到明文；
6. 用明文替换 JSON 中对应位置的 hash；
7. 序列化回 JSON 字符串并写回属性。

## 迁移设计

迁移时把 JSON 字段视为一类新的列迁移能力：

1. 读取主表原始明文 JSON；
2. 对每个声明的 path 提取明文值；
3. 计算 hash 与密文；
4. 主表 JSON 中对应位置替换为 hash；
5. 独立表按 `hashColumn` 去重写入 `cipherColumn`；
6. 把改写后的 JSON 写回主表。

边界：

- path 不存在时跳过；
- path 结构不匹配时 fail-fast；
- 如果主表当前 JSON 已经是 hash 且独立表已有对应密文，则视为已迁移，保证可重入。

## DDL 生成

主表不新增 shadow 列，因为仍使用原 JSON 列。

DDL 生成只负责 `@EncryptJsonPath` 绑定的独立表：

- 表不存在时生成 `create table`
- 表存在但列缺失时生成 `alter table add column`
- 需要的列为：
  - `storageIdColumn`
  - `hashColumn`
  - `cipherColumn`

若多个 `EncryptJsonPath` 指向同一独立表，DDL 生成需自动合并需求，不重复输出。

## 失败策略

以下情况直接拒绝：

- `@EncryptJsonField` 用在非 `String` 字段；
- path 不是精确路径；
- `JSON_SET` / `JSON_REPLACE` / `JSON_MERGE` 等局部更新；
- 动态 path 或参数化 path；
- 未注册 path 的 `json_extract(...)` 查询；
- 对 JSON 加密 path 的 `LIKE`、范围比较、排序、聚合、窗口函数等不安全操作。

## 影响范围

- `common`
  - annotation
  - metadata
  - rewrite
  - decrypt
  - separate-table support
- `migration`
  - migration plan
  - value derivation
  - schema SQL generation
- `docs`
  - architecture
  - sql support matrix
  - persistence / migration guides if public onboarding changes

## 测试策略

### 元数据与校验

- 注解加载 JSON 字段规则
- 非 `String` 字段拒绝
- 非精确 path 拒绝
- 缺少独立表绑定列拒绝

### 运行时

- `INSERT` / `UPDATE` 把 JSON 指定 path 替换为 hash
- 独立表去重写入密文
- `json_extract(...)=? / !=? / IN (...)` 改写
- 结果回填 JSON hash -> 明文
- `JSON_SET` 等局部更新拒绝

### 迁移

- 明文 JSON 迁移为主表 hash JSON + 独立表密文
- 已迁移数据重复执行保持幂等
- DDL 生成合并多 path 指向同一独立表的需求

