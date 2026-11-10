# access-control Specification

## Purpose

定义可配置的访问控制契约：角色、权限、用户角色、数据源授权与数据范围策略由管理端维护，执行链按生效策略强制，而不是按写死在代码里的角色名分支；同时保证策略变更可审计、不可越权、不可通过 SQL 结构绕过。

## Requirements

### Requirement: 用户生命周期管理

系统 SHALL 为持有 `user.manage` 权限的管理员提供用户生命周期管理：创建用户（用户名、显示名、初始密码）、修改显示名、停用/启用账号、重置密码、分配/移除角色。用户名 MUST 唯一，密码 MUST 使用 BCrypt 哈希存储；用户列表与详情接口 MUST NOT 返回密码或哈希。系统 MUST NOT 提供硬删除用户的接口；停用是移除访问权的方式，并保留会话与审计历史。

#### Scenario: 创建用户

- **WHEN** 管理员创建用户 `teacher01`、设置初始密码并分配 `teacher` 角色
- **THEN** 用户出现在列表中，密码以哈希保存，且该用户可用新密码登录

#### Scenario: 停用用户

- **WHEN** 管理员停用某用户
- **THEN** 该用户无法再登录，已有令牌在下一次请求时被拒绝，其历史会话仍保留

#### Scenario: 重置密码

- **WHEN** 管理员为某用户设置新密码
- **THEN** 新密码立即生效，旧密码与旧令牌都不再可用，响应与审计均不包含密码明文

#### Scenario: 重复用户名

- **WHEN** 管理员创建的用户名已存在
- **THEN** 请求被拒绝并说明用户名冲突，不创建任何账号

#### Scenario: 不支持硬删除

- **WHEN** 管理员尝试删除用户
- **THEN** 没有可用接口，用户只能被停用

### Requirement: 账号状态与凭据变更即时生效

系统 SHALL 在每次受保护请求上校验账号状态与令牌版本。登录 MUST 拒绝已停用账号；JWT MUST 携带用户当前的令牌版本；停用账号与重置密码 MUST 递增该版本。访问解析 MUST 在账号停用或版本不匹配时失败关闭，不得仅依赖令牌有效期。

#### Scenario: 停用后旧令牌失效

- **WHEN** 用户已登录后被管理员停用，随后携带旧令牌发起请求
- **THEN** 请求被拒绝（401/403），即使令牌尚未到期

#### Scenario: 重置密码后旧令牌失效

- **WHEN** 管理员重置某用户密码，该用户继续使用旧令牌
- **THEN** 下一次请求被拒绝，必须使用新密码重新登录

#### Scenario: 重新启用需要重新登录

- **WHEN** 管理员重新启用一个先前被停用的账号
- **THEN** 停用前签发的令牌仍然无效，用户必须重新登录

### Requirement: 用户管理权限与防提权

权限目录 SHALL 包含 `user.read`（查看用户）与 `user.manage`（创建、停用、启用、重置密码、分配角色）。用户管理接口 MUST 校验对应权限。管理员在创建用户或调整角色时 MUST NOT 授予自己当前不持有的权限；内置管理员角色是唯一例外。系统 MUST 阻止停用最后一个持有 `access.manage` 的账号。

#### Scenario: 授予超出自身权限的角色

- **WHEN** 只有 `user.manage` 而没有 `access.manage` 的管理员尝试把内置管理员角色分配给新用户
- **THEN** 请求被拒绝，且审计记录失败结果

#### Scenario: 停用最后一个管理员

- **WHEN** 管理员尝试停用最后一个持有 `access.manage` 的账号
- **THEN** 请求被拒绝，系统保留至少一个管理入口

#### Scenario: 只读用户查看用户列表

- **WHEN** 只有 `user.read` 的用户请求用户列表
- **THEN** 接口返回列表但不包含密码字段；其调用任何写操作返回 403

### Requirement: 角色是数据，不是代码常量

系统 SHALL 将角色持久化为可管理记录，包含稳定编码、显示名称、描述、父角色、启用状态和内置标记。管理员 MUST 能创建、编辑、停用角色；内置管理员角色 MUST 不能被删除或停用，以避免系统失去管理入口。

#### Scenario: 创建自定义角色

- **WHEN** 管理员创建一个编码为 `teacher` 的角色并启用
- **THEN** 该角色出现在角色列表中，可被分配权限、数据范围和用户

#### Scenario: 停用角色

- **WHEN** 管理员停用一个仍被用户持有的角色
- **THEN** 该角色不再参与生效权限计算，相关用户的下一次请求按剩余角色处理

#### Scenario: 保护内置管理员

- **WHEN** 管理员尝试删除或停用内置管理员角色
- **THEN** 请求被拒绝并说明该角色受系统保护

### Requirement: 权限目录与功能级授权

系统 SHALL 维护稳定的权限目录，权限按模块和操作分组（例如 `chat.use`、`datasource.read`、`datasource.write`、`model.manage`、`access.manage`、`audit.read`、`access.bypass_data_scope`）。权限 MUST 能分配给角色，并在服务端接口和前端入口同时生效。

#### Scenario: 授予模块权限

- **WHEN** 管理员给某角色授予 `datasource.read`
- **THEN** 该角色用户能看到数据源入口并调用只读数据源接口

#### Scenario: 未授权接口

- **WHEN** 没有 `access.manage` 权限的用户直接调用访问控制管理接口
- **THEN** 服务端返回 403，且不产生任何写入

### Requirement: 用户可持有多个角色

系统 SHALL 支持一个用户绑定多个角色。用户的有效权限 MUST 是其所有启用角色（含继承角色）权限的并集；授权判定 MUST NOT 依赖单一 `role` 字符串。

#### Scenario: 多角色并集

- **WHEN** 一个用户同时持有 `student` 与 `assistant` 两个角色
- **THEN** 该用户获得两个角色的权限并集，且数据范围按两个角色的策略合并

#### Scenario: 移除角色立即生效

- **WHEN** 管理员移除用户的某个角色
- **THEN** 该用户下一次请求不再拥有该角色带来的权限，无需重新登录

### Requirement: 角色继承

系统 SHALL 支持单父角色继承。子角色 MUST 继承父角色的权限与数据范围；角色继承关系 MUST 拒绝环，且父角色停用后其继承贡献 MUST 从生效结果中移除。

#### Scenario: 继承权限

- **WHEN** `assistant` 继承 `student`，且 `student` 拥有 `chat.use`
- **THEN** `assistant` 用户也拥有 `chat.use`

#### Scenario: 拒绝继承环

- **WHEN** 管理员把 A 的父角色设为 B，而 B 已直接或间接继承 A
- **THEN** 保存被拒绝并说明会形成循环继承

### Requirement: 数据范围策略模型

系统 SHALL 支持按「角色 + 数据源 + 表」配置数据范围策略，策略类型为 `ALL`（全部行）、`FILTER`（按主体属性过滤）或 `DENY`（拒绝该表）。`FILTER` MUST 只允许选择预定义主体属性（`userId`、`username`、`studentId`、`studentNo`、`classIds`）与经校验的列，不得接受自由文本 SQL 谓词。

#### Scenario: 配置本人过滤

- **WHEN** 管理员为 `student` 角色在 `score` 表上配置 `student_id = principal.studentId`
- **THEN** 该角色用户查询 `score` 时系统自动施加该过滤

#### Scenario: 拒绝自由 SQL

- **WHEN** 管理员在数据范围策略中提交一段原始 SQL 谓词
- **THEN** 保存被拒绝，接口说明只接受预定义策略类型

#### Scenario: 非法列名

- **WHEN** 管理员配置的列不存在于该数据源的表结构中
- **THEN** 保存被拒绝，且不会生成任何执行期 SQL

### Requirement: 有效数据范围合并

系统 SHALL 按用户的所有启用角色计算每张表的有效策略。任一角色对该表为 `DENY` 时，该表 MUST 对用户拒绝；否则任一角色为 `ALL` 时结果为 `ALL`；否则各 `FILTER` 策略按并集（OR）合并。未配置策略的表 MUST 默认拒绝；未知角色、未知表、未知列或未知主体属性 MUST 失败关闭。

#### Scenario: DENY 优先

- **WHEN** 用户的角色 A 对 `student` 表为 `DENY`，角色 B 为 `ALL`
- **THEN** 该用户不能查询 `student` 表

#### Scenario: 多角色过滤并集

- **WHEN** 角色 A 对 `score` 按本人过滤，角色 B 对 `score` 按所管班级过滤
- **THEN** 用户能看到「本人或所管班级」的成绩，取两个过滤条件的并集

#### Scenario: 未配置表

- **WHEN** 用户查询一张没有任何生效策略的表
- **THEN** 查询被拒绝，且不执行任何 SQL

### Requirement: 策略驱动的 SQL 强制

确定性流水线与 AgentScope 路径 SHALL 共用同一套角色、权限和数据范围策略。执行前 MUST 对语句中的每个表引用应用有效策略；模型未写过滤条件时必须自动补上，模型写了更宽条件时不得放宽。判定 MUST 基于权限与策略记录，不得基于角色名分支。

#### Scenario: 学生查询成绩

- **WHEN** 学生问“我的各科成绩”，模型生成的 SQL 没有过滤条件
- **THEN** 执行前自动补上该学生对应的过滤条件，只返回本人数据

#### Scenario: 未知角色

- **WHEN** 用户持有的全部角色都被停用或没有可识别策略
- **THEN** 请求被拒绝并说明没有数据权限，不降级为任何可访问角色

### Requirement: CTE 不得绕过表策略

SQL 改写 MUST 保证 CTE 引用不会成为表策略的豁免。CTE 体中引用的物理表 MUST 按同一策略检查与过滤；与受保护表同名的 CTE MUST 被拒绝；递归 CTE MUST 在无法证明安全时被拒绝。

#### Scenario: CTE 同名遮蔽

- **WHEN** 受限用户生成的 SQL 使用与受保护表同名的 CTE 包住该表
- **THEN** 查询被拒绝，且不返回任何未过滤数据

#### Scenario: CTE 体内引用

- **WHEN** 受限用户在一个合法 CTE 体内引用受保护表
- **THEN** 该表仍被施加表级与行级策略

### Requirement: 管理端访问控制界面

系统 SHALL 为具备 `access.manage` 权限的管理员提供访问控制界面，至少包含角色、权限、数据范围、用户（创建、停用/启用、重置密码、角色分配）、数据源授权和审计六个部分。界面上的保存操作 MUST 经过服务端校验；前端隐藏入口 MUST NOT 被视为安全边界。

#### Scenario: 配置角色权限

- **WHEN** 管理员在权限矩阵中勾选或取消某角色的权限
- **THEN** 保存成功后该角色的生效权限在下一次请求中更新

#### Scenario: 创建用户

- **WHEN** 管理员在用户页填写用户名、初始密码并选择角色后保存
- **THEN** 列表出现新用户，角色分配立即生效，响应不回显密码

#### Scenario: 停用与重置

- **WHEN** 管理员在用户页停用账号或重置密码
- **THEN** 操作成功并提示该用户需要重新登录，列表状态同步更新

#### Scenario: 直接访问管理 URL

- **WHEN** 没有 `access.manage` 权限的用户直接打开访问控制页面或接口
- **THEN** 页面不渲染管理内容，接口返回 403

### Requirement: 最小特权与防自提权

系统 SHALL 遵循最小特权：只有持有 `access.manage` 的用户能修改访问控制；用户 MUST NOT 能给自己授予自己当前不持有的权限；系统 MUST 阻止移除最后一个持有 `access.manage` 的主体；内置管理员角色 MUST 保留管理能力。

#### Scenario: 自我提权

- **WHEN** 一个只有 `chat.use` 的用户尝试给自己授予 `access.manage`
- **THEN** 请求被拒绝，且审计记录失败结果

#### Scenario: 移除最后一个管理员

- **WHEN** 管理员尝试移除最后一个持有 `access.manage` 的用户或角色
- **THEN** 请求被拒绝，系统保留至少一个管理入口

### Requirement: 访问控制审计

系统 SHALL 对角色、权限、用户角色、用户生命周期（创建、停用、启用、重置密码）、数据源授权和数据范围策略的每次变更记录审计条目，包含操作者、时间、对象、变更前后内容与结果。审计条目 MUST 不可由普通接口修改；读取审计 MUST 需要 `audit.read` 权限。审计内容 MUST NOT 包含密码、密码哈希或完整令牌。

#### Scenario: 角色变更留痕

- **WHEN** 管理员修改某角色的数据范围策略
- **THEN** 审计列表出现一条包含操作者、对象和前后值的记录

#### Scenario: 用户变更留痕

- **WHEN** 管理员创建用户、停用账号或重置密码
- **THEN** 审计列表出现对应记录，但看不到密码或哈希

#### Scenario: 无审计权限

- **WHEN** 没有 `audit.read` 的用户请求审计列表
- **THEN** 接口返回 403

### Requirement: 迁移与向后兼容

系统 SHALL 将现有 `admin` / `student` / `counselor`、`user_scope` 和 `datasource_access` 迁移为新的角色、用户角色、主体属性和数据范围策略记录，使现有账号的有效数据范围保持不变。迁移 MUST 幂等；无法映射的旧角色 MUST 失败关闭。

#### Scenario: 迁移现有学生

- **WHEN** 迁移完成后 `student01` 登录
- **THEN** 其可见数据与迁移前一致，仍只能看到本人记录

#### Scenario: 旧角色残留

- **WHEN** 数据库中存在旧的、无法映射的角色值
- **THEN** 该账号被拒绝访问数据，而不是获得默认范围
