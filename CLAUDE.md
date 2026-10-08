# CLAUDE.md — 校园失物招领与 AI 智能匹配平台

> **本文件是协作规范**：定义项目全貌、技术选型理由，以及**改代码时不可破坏的约定**。
>
> - 📖 项目做了什么、跑出什么数字 → [README.md](./README.md)
> - 📐 模块级设计与取舍的完整记录 → [docs/technical-notes.md](./docs/technical-notes.md)
> - 🧪 可复现的评测 / 压测材料 → [eval/](./eval/) · [bench/REPORT.md](./bench/REPORT.md)

## 项目简介

校园失物招领平台：Spring Boot 3 单体应用 + Vue 3 前后端分离。
核心亮点是 **AI 失物匹配** —— 用户用自然语言描述丢失的东西（「我的黑色苹果手机不见了」），
系统从招领池里召回候选、交给大模型精排，返回结构化结果与匹配理由。

后端、前端、评测脚本、压测脚本均为独立完成。本仓库从骨架到压测、评测全程有 Claude Code 协作参与，
本文件即这套协作规范的一部分。

> ⚠️ **以下文件仅存在于本地，已在 `.gitignore`，不随仓库发布**：
> `docs/learning-log.md`（学习进度与练习记录）、`.run/`（本机 IDEA 运行配置）、
> `application-local.yml`（数据库凭据）、`bench/*.jtl`（压测原始日志，单轮 100MB+）。
> **提交前先扫一眼 `git status`。**

## 技术栈

| 组件 | 版本 | 为什么选它 |
|------|------|-----------|
| Java | 17 | LTS 版本，Spring Boot 3 的最低要求 |
| Spring Boot | 3.3.3 | 主流稳定 3.x，自动配置降低样板 |
| MyBatis-Plus | 3.5.7 | 通用 CRUD 不写 SQL，复杂查询仍可手写；`LambdaQueryWrapper` 有编译期字段检查 |
| MySQL | 8.x | 最通用的关系型数据库 |
| Redis + Spring Cache | — | 热点读缓存；注解式接入，带故障降级 |
| jjwt | 0.12.6 | JWT 新 API，修复了旧版安全漏洞 |
| BCrypt | spring-security-crypto | 只要密码单向加密，不引入完整的 Spring Security |
| Hutool | 5.8.32 | 常用工具类 |
| Lombok | — | 减少样板代码 |
| 大模型 API | OpenAI 兼容接口 | 供应商通过配置切换，不写死在代码里 |
| Vue 3 + Element Plus + Vite | — | 前端，组合式 API |
| JMeter | — | 压测（非 GUI 模式） |

**两个被明确拒绝的方案**：

- **不用 Spring Security** —— 项目只需要 JWT 登录认证，不需要角色权限 / OAuth2 / RememberMe。
  只引 `spring-security-crypto` 做 BCrypt，认证交给手写拦截器，代码量少、链路透明。
- **不上微服务** —— 校园场景用户量在千~万级，单体 + 水平扩展足够。过早微服务会引入分布式事务、
  服务发现、网络开销，ROI 不成立。

## 构建与运行

```bash
# 后端（需先启动 MySQL；Redis 可选 —— 连不上会自动降级直查数据库）
mvn clean compile                  # 编译
mvn test                           # 运行测试
mvn spring-boot:run                # 启动 → http://localhost:8080
mvn clean package -DskipTests      # 打包

# 前端
cd frontend && npm install && npm run dev    # → http://localhost:5173
```

数据库凭据通过环境变量 `MYSQL_USERNAME` / `MYSQL_PASSWORD`，或本地文件 `application-local.yml`（已 gitignore）注入。
大模型密钥走 `DEEPSEEK_API_KEY` 环境变量。**完整启动步骤见 [README.md](./README.md)。**

> ⚠️ **后端正在运行的时候不要 `mvn clean`** —— 会把 JVM 赖以加载的 `target/classes` 抽走。

## 项目结构

```
lost-found/
├── src/main/java/com/lostfound/
│   ├── common/                          # Result · ResultCode · BusinessException
│   │                                    # GlobalExceptionHandler · RetrySupport
│   ├── config/                          # JwtConfig / JwtUtil · WebConfig
│   │                                    # LlmConfig · EmbeddingConfig · RedisConfig
│   │                                    # MybatisPlusConfig · MyMetaObjectHandler
│   │                                    # GracefulCacheErrorHandler · RateLimitInterceptor
│   ├── controller/                      # UserController · ItemController · AiController
│   ├── service/                         # User · Item · Ai · File
│   │   ├── CandidateService             # 候选召回（2-gram 覆盖率打分）
│   │   ├── EmbeddingService             # 向量化抽象（已实现，未接入召回链路）
│   │   └── impl/                        # 各 Service 实现
│   ├── mapper/  entity/  dto/           # 数据访问 / 表映射 / 请求响应对象
│   └── interceptor/JwtInterceptor.java  # JWT 鉴权
├── src/test/                            # 24 个测试（10 个 MockMvc 接口测试 + 14 个单测）
├── frontend/                            # Vue 3：Login / ItemList / ItemDetail / Publish / AiChat / Profile
├── eval/                                # AI 匹配评测：146 条数据集 + 生成器 + 脚本 + 各阶段报告
├── bench/                               # JMeter 压测计划 + REPORT.md
├── docs/technical-notes.md              # 36 条项目技术笔记
└── sql/schema.sql                       # 建表脚本（含索引）
```

## 分层与调用链

```
浏览器（Vue 3 SPA）
  │  Axios 请求拦截器自动带 Authorization: Bearer <token>
  ▼
JwtInterceptor          ← 鉴权（规则见下节）
RateLimitInterceptor    ← 只拦 /api/ai/**，每用户每分钟默认 5 次
  ▼
Controller（接收请求、@Valid 参数校验）
  ▼
Service（业务逻辑、事务、缓存注解）
  ▼
Mapper（MyBatis-Plus BaseMapper / LambdaQueryWrapper）
  ▼
MySQL 8（Redis 作为缓存层旁挂在 Service 之上）
```

## API 接口

| 方法 | 路径 | 需登录 | 说明 |
|------|------|:---:|------|
| POST | `/api/user/register` | — | 注册 |
| POST | `/api/user/login` | — | 登录，返回 JWT token |
| GET | `/api/user/me` | ✅ | 当前用户信息 |
| POST | `/api/item` | ✅ | 发布物品（multipart：`data` + 可选 `image`） |
| GET | `/api/item` | — | 物品列表（分页 + 筛选 + 排序） |
| GET | `/api/item/{id}` | — | 物品详情 |
| GET | `/api/item/my` | ✅ | 我的发布 |
| PUT | `/api/item/{id}/status` | ✅ | 修改物品状态（仅发布者） |
| POST | `/api/ai/chat` | ✅ | AI 问答 |
| POST | `/api/ai/query` | ✅ | **AI 物品匹配**（2-gram 召回 + 大模型精排） |
| POST | `/api/claim` | ✅ | 提交认领申请（含特征证明） |
| GET | `/api/claim/my` | ✅ | 我发起的认领申请 |
| GET | `/api/claim/received` | ✅ | 我发布的物品收到的申请（审核台，**含他人提交的特征证明**） |
| GET | `/api/claim/{id}` | ✅ | 申请详情 + 完整流转时间线（**仅申请人与物品发布者**） |
| POST | `/api/claim/{id}/approve` | ✅ | 审核通过（物品发布者） |
| POST | `/api/claim/{id}/reject` | ✅ | 驳回（物品发布者，理由必填） |
| POST | `/api/claim/{id}/cancel` | ✅ | 撤回尚未审核的申请（申请人） |
| POST | `/api/claim/{id}/complete` | ✅ | **确认收到物品**（申请人）→ 物品转「已认领」 |
| GET | `/api/notification/my` | ✅ | 我的站内通知（分页，**内联匹配明细**） |
| GET | `/api/notification/unread-count` | ✅ | 未读条数（角标用） |
| PUT | `/api/notification/{id}/read` | ✅ | 标记单条已读（仅本人，幂等） |
| PUT | `/api/notification/read-all` | ✅ | 全部标记已读 |

> ⚠️ 认领的四个 GET 里，后两个**不是公开接口**：`/received` 带着别人提交的特征证明，
> `/{id}` 带着整条申请。它们同样走在「GET 放行」的规则下，所以**权限必须在 Service 层卡死**，
> 不能指望拦截器 —— 这是本项目里最容易写错的地方之一。

> 📌 表里「需登录」是**语义**上的要求。`GET /api/user/me` 标 ✅ 是因为没有 token 就取不到 `userId`；
> 但从拦截器角度看，它和所有 GET 一样会被放行，由 Service 层决定拿不到用户时怎么处理。

**认证方式：** 登录后拿到 token，请求头加 `Authorization: Bearer <token>`。

## 鉴权规则（**按 HTTP 方法分，不按接口分**）

写在 `JwtInterceptor`：

- **GET 一律放行**，token 可选 —— 带了就解析出 `userId` 放进 request（供「我的发布」「当前用户」用），
  没带也放行：游客可以公开浏览。
- **POST / PUT / DELETE 必须有有效 token**，否则 401。
- 只有 `/api/user/register` 和 `/api/user/login` 被排除在拦截器之外。
- 副作用：`/api/ai/chat` 和 `/api/ai/query` 虽然是「查询语义」，但因为是 POST，**同样需要登录**。

**为什么这么设计**：「浏览公开、写操作必须认证」这条规则天然正确，且**新增接口时不会漏配导致越权** ——
逐个接口配置白名单，漏配一个就是一个越权漏洞。

**越权的第二层防护在 Service**：改物品状态时，查出物品的 `userId` 和当前登录用户比对，不一致抛 403。
`JwtInterceptor` 只回答「你是谁」，Service 才回答「这东西是不是你的」——
**只有拦截器就是任何人登录后都能改别人的物品**。

## 数据库

数据库名 `lost_found`（首次启动 `createDatabaseIfNotExist=true` 自动创建）。

### user 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| username | VARCHAR(50) UNIQUE | 登录用户名 |
| password | VARCHAR(255) | BCrypt 加密 |
| nickname | VARCHAR(50) | 显示昵称 |
| phone | VARCHAR(20) | 手机号 |
| avatar_url | VARCHAR(500) | 头像 |
| role | VARCHAR(20) | USER / ADMIN |
| created_at / updated_at | DATETIME | 自动填充 |

### item 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| user_id | BIGINT | 发布者（**无物理外键**，见下） |
| title | VARCHAR(100) | 物品标题 |
| type | VARCHAR(10) | LOST（寻物）/ FOUND（招领） |
| category | VARCHAR(30) | 电子产品 / 证件 / 衣物 / 书籍 / 其他 |
| location | VARCHAR(200) | 丢失或拾获地点 |
| description | TEXT | 详细描述 |
| image_url | VARCHAR(500) | 图片路径 |
| contact | VARCHAR(100) | 联系方式 |
| status | VARCHAR(20) | UNCLAIMED / CLAIMED |
| **version** | INT | **乐观锁字段**（并发认领） |
| created_at / updated_at | DATETIME | 自动填充 |

**索引**：`idx_created_at`（分页默认按时间倒序）、`idx_type`、`idx_category`、`idx_status`、`idx_user_id`。

- **不做物理外键**：高并发下外键约束要额外检查、拖慢写入，分库分表时也会成为阻碍；
  靠 `idx_user_id` 加速查询、靠 Service 层保证逻辑一致性。
- `created_at` / `updated_at` 由 MyBatis-Plus 的 `MyMetaObjectHandler` 自动填充。

### claim_application 表（认领申请）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 自增 |
| item_id | BIGINT | 被申请的物品 |
| applicant_id | BIGINT | 申请人（失主） |
| owner_id | BIGINT | 物品发布者，**冗余自 `item.user_id`** |
| proof_text | VARCHAR(500) | **物品特征证明**，审核依据，**永不公开** |
| proof_image | VARCHAR(500) | 证明图片 |
| contact | VARCHAR(100) | 申请人本次认领留的联系方式 |
| status | VARCHAR(20) | PENDING / APPROVED / REJECTED / CANCELLED / COMPLETED |
| reject_reason | VARCHAR(200) | 驳回理由，**系统自动关闭也写这里** |
| reviewed_at / completed_at | DATETIME | 审核 / 完成归还时间 |
| **active_flag** | TINYINT 生成列 | `status ∈ (PENDING, APPROVED)` 时为 1，否则 NULL |
| **approved_flag** | TINYINT 生成列 | `status = APPROVED` 时为 1，否则 NULL |
| version | INT | 乐观锁 |
| created_at / updated_at | DATETIME | 自动填充 |

**索引**：`uk_item_applicant_active(item_id, applicant_id, active_flag)`、`uk_item_approved(item_id, approved_flag)`、`idx_applicant_created`、`idx_owner_created`。

> 💡 **两个生成列 + 唯一键，是用来表达"有条件的唯一"**：唯一索引里 NULL 不参与去重，于是
> ① 同一人对同一物品只能有一条**活跃**申请（被驳回/撤回后 `active_flag` 变 NULL，**可以重新申请**）；
> ② 同一物品最多只能有一条**已通过**的申请（东西只有一件）。
> MySQL 没有部分唯一索引，这是它的等价替代 —— 两条规则因此由数据库兜底，不靠应用层自觉。

> ⚠️ **这两个生成列绝不能声明进实体类**：MyBatis-Plus 插入时会带上实体里所有字段，往生成列写值直接报错。

### claim_log 表（状态流转日志）

字段：`id` / `claim_id` / `from_status`（首次提交为 NULL）/ `to_status` / `operator_id` / `operator_role`（APPLICANT / OWNER / SYSTEM）/ `remark` / `created_at`。

**只追加，永不 UPDATE、永不 DELETE** —— 一旦允许改历史，它就失去了作为证据的意义。
认领详情页的「流转时间线」就是把它按 `id` 顺序读出来，不另存一份数据。

> `operator_id` 是 `NOT NULL`。系统自动动作（如归还后自动关闭其他申请）**记触发这次流程的用户 id**，
> 用 `operator_role=SYSTEM` 区分"这是系统代为执行的，不是他手动做的" —— 保留业务触发来源，不引入 NULL 语义。

---

## 认领闭环

把「发布者手动标记已认领」升级成一条完整链路：**申请 → 特征证明 → 审核 → 线下交接 → 确认收到**，
全过程留痕，可复盘「谁在什么时候做了什么」。

### 状态机

```
申请：  PENDING ──通过──> APPROVED ──申请人确认收到──> COMPLETED
           │
           ├──驳回──> REJECTED          （终态）
           └──撤回──> CANCELLED         （终态）

物品：  UNCLAIMED ──有人申请──> CLAIMING ──确认收到──> CLAIMED
                              ↑                │
                              └──无活跃申请────┘（驳回/撤回后回退）
```

规则写在 `ClaimServiceImpl.ALLOWED_TRANSITIONS` 一张表里，**终态故意不在表内** ——
从终态出发的任何转换都会因查不到而失败，不需要额外维护一份"终态清单"。
任何状态变更都必须走 `assertTransition` 校验，并且**必须写一条 `claim_log`**。

### 三条关键设计

**1. 审核通过 ≠ 已经归还。** `approve` **完全不碰 item**，物品仍停在「认领中」。
因为"审核通过"和"东西真的交出去"之间隔着线下交接这段现实时间，中间可能联系不上、可能反悔。
把两步合并会造出一个假象：全站显示"已归还"，而双方根本还没见面。

**2. 唯一的状态入口。** 物品状态只能由认领流程推进（`changeStatusByClaim` / `revertToUnclaimedIfClaiming`）。
`PUT /api/item/{id}/status` **只保留「撤销」一个方向**（`CLAIMED → UNCLAIMED`）用于纠错 ——
允许直接 `UNCLAIMED → CLAIMED` 就是留着一条绕过整条流程的后门：
物品没有任何申请人、没有一条流转日志，却显示已经归还。

**3. 归还后自动关闭其他申请。** `complete` 在同一个事务里：申请 → COMPLETED、物品 → CLAIMED、
该物品下其他 `PENDING` 申请 → `REJECTED`（理由「该物品已完成归还，其他申请自动关闭」）+ 各写一条 SYSTEM 日志。
**触发点选在 COMPLETED 而不是 APPROVED**：通过审核时物品还没交出去，其他申请人仍然有机会。

### 权限（全部在 Service 层，拦截器只管"你是谁"）

| 操作 | 谁 |
|---|---|
| 提交申请 | 登录用户；物品须为 FOUND 且未 `CLAIMED`；**不能认领自己发布的** |
| 审核 / 驳回 | **物品发布者本人** |
| 撤回 | **申请人本人**，且仅 `PENDING` |
| 确认收到 | **申请人本人**，且仅 `APPROVED`（已 `COMPLETED` 则幂等返回成功） |
| 查看申请详情 | **仅申请人与物品发布者** |
| 联系方式可见 | **发布者本人** + **已通过/已完成审核的申请人**。其他人一律看不到 |

> 联系方式收敛不是"少给点信息"，而是**认领流程成立的前提** —— 谁都能直接看到电话的话，
> 失主根本不会去走申请流程。实现上返回的是**复制出来的 `ItemDetailView`，绝不原地改实体**，
> 因为 `itemDetail` 缓存里存的就是实体对象本身，改了它发布者自己也看不到了。

### 并发控制

- **物品级**用 `item.version`，**申请级**用 `claim_application.version`，两把锁职责分开。
- 状态变更走「读最新 → 校验 → `updateById`（`@Version` 自动加 `WHERE version=?`）→ 影响行数 0 即冲突」，
  **不要用 `LambdaUpdateWrapper` 手写条件绕开 `@Version`**。
- 冲突返回 **409**，而不是 400：请求本身没问题，是**时机**不对，用户刷新后可能可以继续。
- **自动关闭其他申请时逐条用 `updateById` + `@Version`**，不能用批量 set：
  当前申请转 COMPLETED 后 `uk_item_approved` 就空出来了，别人可能恰好在那一刻通过了另一条，
  批量 set 会把刚通过的申请覆盖成 REJECTED。某条影响行数为 0 就**跳过并记 warn**，
  不让一条清理动作失败拖垮失主的确认收货。
- **数据库唯一键的异常必须翻译**：`DuplicateKeyException` 不接住就会漏成 500，
  而"这个物品已经有人通过了"明明是客户端该知道的事。见 `docs/technical-notes.md` Q41。

### 缓存一致性

`approve` / `reject` / `cancel` 只改 `claim_application`，**认领数据本身不做缓存**（见 Q38），
所以它们不需要清缓存。真正要清的是**物品状态跟着变**的那些路径 —— 全部走 `ItemService` 上带
`@CacheEvict` 的 public 方法，一次清掉 `itemPage` / `itemDetail` / `itemMypage` / `foundPool`：

| 路径 | 物品状态变化 | 清缓存 |
|---|---|---|
| 提交申请 | `UNCLAIMED → CLAIMING` | `changeStatusByClaim` |
| 驳回 / 撤回后无活跃申请 | `CLAIMING → UNCLAIMED` | `revertToUnclaimedIfClaiming` |
| 确认收到 | `CLAIMING → CLAIMED` | `changeStatusByClaim` |
| 发布者撤销 | `CLAIMED → UNCLAIMED` | `updateStatus` |

> ⚠️ **每次新增"会改物品状态"的写路径，都必须同步补这条 `@CacheEvict`**，
> 否则会出现「库里已是 CLAIMING，AI 仍按 UNCLAIMED 召回」。
> 另外这两类方法**必须是对外 public**（不能是私有方法或同类自调用），否则注解基于代理会**静默失效**。

> 📌 AI 候选池 `listUnclaimedFound` 含 `UNCLAIMED` 和 `CLAIMING`，**只排除 `CLAIMED`**：
> 有申请 ≠ 已归还，申请可能被驳回，第二个人仍应能搜到它。

---

## 发布后自动匹配与站内通知

物品**发布成功后**由后台异步跑一遍匹配，够格就给可能的主人发站内通知。两个方向都支持：

```
发布 LOST  → 候选池＝已有 FOUND → 收件人＝发布者本人
发布 FOUND → 候选池＝已有 LOST  → 收件人＝每条被匹配失物的主人（按人分组，每人一条）
```

> 💡 **两个方向的收件人恒等于「那条失物的主人」** —— 丢东西的人才需要被提醒。
> 招领发布者不会收到任何通知（那是他自己发的东西）。

### 与主动匹配的关系

**共用召回与精排，筛选策略独立**：

| | 主动匹配（AiChat） | 自动匹配（发布后） |
|---|---|---|
| 触发 | 用户主动输入描述 | 物品发布成功后系统自动跑 |
| 召回 + 精排 | **同一套**（`CandidateService.recall` + `AiService.matchAgainst`） | **同一套** |
| 筛选 | **不设门槛**，按分数降序取 Top-5 | **≥80 分**才进通知，最多 5 条 |
| 配置 | `ai.match.interactive.*` | `ai.match.auto.*` |
| 为什么不同 | 用户主动来找 → 漏报的代价大（他以为平台上没有就放弃了） | 系统推给用户 → 多推一条就是一次打扰 |

**门槛是「防打扰」的工具，不是「衡量匹配质量」的尺子** —— 模型的 `score` 是它自己写的 0~100 整数，没有被校准过。

### 数据结构：主表 + 明细表

- **`notification`（主表）**：`receiver_id` / `source_item_id` / `content` / `is_read` / `read_at`
  唯一键 **`uk_source_receiver(source_item_id, receiver_id)`** —— 「一次发布 + 一个收件人 = 至多一条」就是它
- **`notification_match`（明细表）**：`notification_id` / `lost_item_id` / `found_item_id` / `score` / `reason`

一条汇总通知带 N 条匹配明细，每行指向**一个确定的 itemId** 并带上分数与理由 —— 点开就能看到具体是哪些、像在哪，
不是只存一句文案。**两个 id 都存**，所以「你的哪件 ↔ 对方的哪件」永远是明确的。

> ⚠️ 这不是「同标题就算同一条」—— 每一行都是一个具体的物品 id。

### 异步执行

```
ItemController.publish()   ← 触发点在这里，不在 Service
  └─ 发布成功（insert 已提交）
       └─ AutoMatchServiceImpl.triggerAfterPublish()  ← 门面：判开关，非异步
            └─ AutoMatchRunner.match()  @Async("autoMatchExecutor")  ← 真正干活
```

三个必须记住的设计约束：

1. **触发点在 Controller，不在 `ItemServiceImpl.publish` 里** —— 因为
   `ItemServiceImpl → AutoMatchService → AiService → CandidateService → ItemService` 是个**环**，
   而这个项目用的是构造器注入（`@RequiredArgsConstructor`），环会让启动直接失败。
   放 Controller 既避开环，又天然是「insert 已提交之后」。
   **⚠️ 如果将来给 `publish` 加上 `@Transactional`，这个位置就不再是"提交后"了，必须改成 afterCommit 回调。**
2. **门面与执行体是两个 Bean** —— `@Async` 只能加在被代理的方法上。若把开关判断写进异步方法里，
   "关掉自动匹配"时任务照样会进线程池。拆开之后，**关掉就是一个任务都不提交**。
3. **线程池有界（核心 1 / 最大 2 / 队列 50），拒绝时记 ERROR** —— 语义等同 DiscardPolicy 但**不静默**。
   任务被丢 = 这次没提醒到；物品本身已经发布成功，用户随时能自己去 AI 页搜，能力一模一样。

**失败与重启**：整个异步方法兜住所有异常，只记 ERROR 不重试。
**重启会丢在途任务，第一版有意不持久化** —— 这是"尽力而为的提醒"，不是账目。

### 去重

靠 `uk_source_receiver` 唯一键，一条键覆盖三种重复来源：重试、重复触发、并发处理同一事件。

**跨事件不会重复通知同一对物品**：因为「只在发布时触发一次」+「候选池只含发布那一刻已存在的物品」，
同一对 (失物, 招领) 只可能在**两者中较晚发布的那一方**触发时被评估一次 —— 这是天然性质，不需要额外的配对唯一键。

### 开关与守卫

- **`ai.match.auto.enabled`**（默认 true）：关掉后发布照常成功，但不提交任务、不调模型、不发通知。
  ⚠️ 它**只影响自动匹配**，不影响 AiChat 的主动匹配。
- **类别守卫**：失物与招领类别**明确不一致**时默认不通知（实测里"蓝色的学生卡"曾被推成"蓝色的 U 盘"且拿了 80 分）；
  任一边是「其他」这类**泛分类**时放行，交给大模型按文本判断 —— 泛分类本身不表达"这是什么"。
- **跳过自己发的**：同一个人既发了失物又发了招领时跳过（自己提醒自己没意义）。
  另外两个候选池都按 type 筛，天然不含刚发布的那条自己。

### 缓存

`lostPool`（失物候选池）和 `foundPool` 一样带缓存，**所有会改物品状态的写路径都必须清它俩**：
`publish` / `updateStatus` / `changeStatusByClaim` / `revertToUnclaimedIfClaiming`。
少清一处就会出现「发布了新失物但反向匹配看不到它」。

---

## 🚫 不可破坏的约定（**改代码前必读**）

**架构**

1. 鉴权按 HTTP 方法分，**不按接口分** —— 不要改成「新增接口时逐个配白名单」，那是在制造越权漏洞。
2. 越权检查在 Service 层；拦截器只负责「你是谁」。
3. 所有接口返回统一 `Result`，前端只判 `code === 200`。不要某个接口直接返回裸对象。
4. 异常分类处理，**客户端错不能报成 500**。客户端数据非法（如坏 JSON）应返回 400 + 明确提示。

**缓存**

5. 写操作必须**成对清缓存**（`@Caching(evict=...)`）。尤其 `foundPool`：它是 AI 召回的候选全集，
   **不清就会「发布了新物品但 AI 永远召不到」**。
6. Redis 挂了必须**静默降级直查数据库**（缓存是性能组件，不是正确性组件）。
7. **但静默 ≠ 不管**：被吞掉的异常必须可观测。加任何降级逻辑时，先想清楚「它坏了怎么知道」。

**AI 链路**

8. 召回用的文本字段必须 **= 喂给大模型的字段**，两边不一致等于白做。
9. 大模型返回值不可信：`itemId` 不在候选列表里的**直接丢弃**（幻觉防护）；
   `title / category / location` 一律用本地数据回填。**模型只负责判断「哪条最像」。**
10. **上游超时必须大于下游最坏耗时**（前端 30s / 后端 `read-timeout 30s × (1+2 次重试)` 就是反例）。
11. 失败必须可区分：降级响应带 `degradeReason`，**别让「调用失败」和「没匹配上」长得一样** ——
    混在一起会让基于这批数据的任何统计结论都失真。
12. 重试只对**超时 / 5xx / 429**；401（密钥错）、400（参数错）不重试。重试逻辑只有 `RetrySupport` 一个出处。

**数据库**

13. 深分页走**延迟关联**（先查 id 再回表）；`selectBatchIds` **不保证返回顺序，必须按原 id 顺序还原**。
14. `item.version` 是乐观锁字段，更新走 `@Version`，**别用 `updateById` 绕过去**。
15. 新增查询条件加进 `buildQueryWrapper`，**别在 page / myPage / list 三处各写一遍**。

**改动纪律**（最容易违反，也最贵）

16. **一次只改一个变量** —— 否则分不清是谁的功劳（prompt 瘦身和召回重构一起做，就永远说不清谁贡献了什么）。
17. **先量基线再优化**，没有基线的优化等于没有优化。
18. 改完用**同一套评测 / 压测复核，看命中率和降级率，不是看「接口没报错」**。
19. **换任何东西之前先验证** —— 连把一个「已弃用」的模型名换成新的，都能带出一个空结果的 bug。

**认领闭环**

20. 物品状态**只能由认领流程推进**。`PUT /api/item/{id}/status` 只允许 `CLAIMED → UNCLAIMED`（纠错撤销），
    **禁止直接标记「已认领」** —— 那会留下一条"已归还但没有任何申请记录"的物品。
21. `approve` **不动物品状态**。通过审核 ≠ 已经归还，物品仍是「CLAIMING」，
    只有申请人确认收到才转「CLAIMED」。
22. 任何申请状态变更都必须走 `assertTransition` 校验 + `recordLog` 写日志，**两者缺一不可**。
    `claim_log` 只追加，**永不 UPDATE / DELETE**。
23. `claim_application` 的两个生成列（`active_flag` / `approved_flag`）**不要声明进实体类**，
    它们是给唯一键用的，写进去会报错。
24. 新增任何"会改物品状态"的写路径，**必须同步补 `@CacheEvict`**（见认领闭环一节的缓存一致性表），
    且这类方法必须是对外 **public** —— 私有方法或同类自调用会让注解静默失效。

**自动匹配与通知**

25. **主动匹配与自动匹配的筛选策略必须独立**（`ai.match.interactive.*` / `ai.match.auto.*`）。
    混成一个键，早晚会用错方向 —— 两者的风险方向是相反的。
26. **自动匹配必须异步、且异常不得外溢**。任何失败只记日志，不能让已经成功的发布回滚。
    触发点在 `ItemController`（避开循环依赖），**给 `publish` 加事务时必须改成 afterCommit**。
27. **一条发布事件对一个收件人至多一条通知**（`uk_source_receiver`）。
    不要改成"每对物品一条"—— 那是另一种设计，会让一次发布弹出好几条看起来一样的通知。

---

## 设计决策

- **为什么统一返回体 `Result`？** 前端只需判断 `code === 200`；异常不靠 HTTP 500 返回给前端，
  由 `GlobalExceptionHandler` 统一拦截转换，前端处理一致。
- **为什么 API Key 放环境变量？** 防止密钥泄露进 Git；配置文件里只有占位符。
- **为什么 JWT 不用 Redis Session？** 前后端分离、不依赖 Redis 部署更简单、校园场景不需要实时踢人下线。
  需要主动失效时可在 Redis 维护黑名单补充。
- **为什么密码用 BCrypt 而不是 MD5？** 每次加密随机 22 位盐（同一密码两次密文不同，彩虹表作废）；
  算法故意设计得慢（单次约 0.1s），字典爆破代价高。盐嵌在密文里，不单独存。
- **为什么登录失败提示统一为「用户名或密码错误」？** 分开提示会被用来撞库筛选真实用户名。
- **为什么并发认领用乐观锁不用悲观锁？** 认领是低冲突场景。乐观锁不加锁、不阻塞；
  悲观锁（`SELECT ... FOR UPDATE`）会持有行锁，在低冲突场景纯属浪费。冲突则重试最多 3 次。
- **为什么分页用延迟关联，而不是再加一个索引？** 索引早就有（`idx_created_at`），
  问题是**优化器主动放弃了它** —— 根因是回表成本（`offset + size` 次随机 IO），
  所以解法是降低回表次数，而不是再建一个索引。
- **为什么缓存挂了不报错，而是静默降级直查 DB？** 见上方约定 6、7 ——
  **降级保证可用性是对的，但把失败伪装成正常结果是错的。**
- **为什么匹配用 2-gram 不用分词 / 不用向量？** 分词要引 HanLP/jieba 依赖，且对错别字和新词很脆；
  2-gram 零依赖、对新词鲁棒。向量能力已实现但**未接入召回链路**（2-gram 召回率已达 95.2%，
  且向量探针实测正确答案与错误答案只差 0.0245 —— 分不出阈值，只能做召回不能拍板）。

- **为什么主动匹配不设最低分门槛，而自动匹配要设？** 两者的**风险方向是相反的**。
  主动匹配是用户自己来描述、自己来找东西 —— 他要的是"尽量别漏"，多给他看一条让他自己判断，
  成本几乎为零；漏掉才是贵的（他以为平台上没有，就此放弃）。
  自动匹配是系统推给用户，**用户没主动要**，多推一条就是一次打扰，所以要用门槛压掉无关结果。
  > ⚠️ **门槛是"防打扰"的工具，不是"衡量匹配质量"的尺子。** 模型的 `score` 是它自己写的
  > 0~100 整数，没有被校准过，拿它当质量闸门本身就没有依据。这也是为什么两个场景的筛选策略
  > **必须拆成两组独立配置**（`ai.match.interactive.*` / `ai.match.auto.*`），
  > 混成一个键，早晚会用错方向。

更完整的取舍记录见 [docs/technical-notes.md](./docs/technical-notes.md)。

---

## 已知边界

**以下这些是有意为之或已知局限，不要为了「看起来完整」而假装它们不存在。**

| # | 事实 |
|---|---|
| 1 | **Docker 配置已写好但未在真实环境验证过** —— `Dockerfile`（多阶段构建）+ `docker-compose.yml`（三服务 + healthcheck + 数据卷）确实存在且规范，但没有跑过容器，因此也就没有「镜像体积压到多少」这类数字 |
| 2 | **向量能力未接入召回链路** —— `EmbeddingService` 抽象 + 云端 `bge-m3` 实现已写好并有单测，但召回仍走 2-gram |
| 3 | **没有集成测试** —— 只有单测 + MockMvc（Mock 掉 Service/Mapper），没有真连数据库跑完整链路 |
| 4 | **限流器是单机内存实现** —— `ConcurrentHashMap` 的 key 从不清理，长时间运行会缓慢泄漏；多实例部署时各算各的 |
| 5 | **`COUNT(*)` 未解决** —— 平均 41ms、占 SQL 总时间 54%。可选方案：缓存计数结果，或前端改成「加载更多」不做总数 |
| 6 | **缓存 TTL 固定 5 分钟**，无随机抖动，理论上存在缓存雪崩风险（当前量级可接受） |
| 7 | **2-gram 在十万级会不够用** —— 现在每次请求对全量候选重切 2-gram（1000 条约几毫秒），量级上去需要预建索引或换检索引擎 |
| 8 | **本地文件存储不可水平扩展** —— 图片存本地磁盘，生产要换 OSS（代码里是 `FileService`，换实现即可） |
| 9 | **压测机是本机** —— JMeter / 后端 / MySQL / Redis 同一台笔记本，绝对数字受此限制；只有「优化前后对比」是有效的 |
| 10 | **评测集是合成的** —— 由大模型反向改写生成，有人工痕迹，能发现量级差异（19% vs 94%），不够区分 1~2 个点的小改动 |
| 11 | **没有微服务 / 分库分表 / 消息队列** —— 按当前量级有意不引入 |

**迭代时间线**（AI 匹配评测、压测、各阶段报告）见 [README.md](./README.md) 的「开发日志」。
