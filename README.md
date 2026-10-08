# 校园失物招领与 AI 智能匹配平台

校园场景下的失物招领服务平台：注册登录、物品发布/搜索/认领、图片上传，并接入大模型实现 AI 智能问答与**物品自动匹配**。

**技术栈：** Java 17 · Spring Boot 3.3 · MyBatis-Plus 3.5 · MySQL 8 · Redis · JWT · 大模型 API · Vue 3 · Element Plus · Vite

> 后端、前端、评测脚本、压测脚本均为独立完成。完整提交记录见本仓库 commit 历史。

---

## 功能

- 用户注册 / 登录（JWT 认证 + BCrypt 密码加密）
- 物品发布（**含图片上传**）、分页搜索（按类型 / 分类 / 关键词 / 时间排序）、详情查看
- **认领闭环**：失主提交认领申请并附物品特征证明 → 拾到者审核（通过 / 驳回，驳回需理由）→
  线下核对交接 → 失主确认收到，物品才转为「已认领」。全过程写入 `claim_log`，
  申请详情页可按时间线复盘每一步；归还完成后同一物品上其他申请自动关闭
- 个人中心：我的发布列表 / 我的认领申请（含「确认收到」入口）/ 我收到的申请（审核台）
- AI 智能问答：自由提问
- **AI 物品匹配**：用自然语言描述丢失的东西（如「我的黑色苹果手机不见了」）→ 匹配库里的招领信息，返回结构化结果 + 匹配理由

---

## 性能（压测实测）

在 **12.8 万行**物品数据下对最核心的读接口 `GET /api/item` 做压测（JMeter 非 GUI 模式），完整报告见 [`bench/REPORT.md`](./bench/REPORT.md)。

### 深分页优化：延迟关联（Deferred Join）

**问题**：分页主查询平均扫描 **122,667 行**（全表才 128,064 行）—— 它在全表扫描。

```
-- 浅分页 LIMIT 0,10
EXPLAIN: type=index  key=idx_created_at  rows=10      Extra: Backward index scan  ✅

-- 深分页 LIMIT 10000,10
EXPLAIN: type=ALL    key=NULL            rows=125571  Extra: Using filesort       ❌
```

**根因是回表**：走 `idx_created_at` 需要回表 `offset + size` 次，offset 上万就是上万次随机 IO；而 `SELECT *` 必须回表才能拿到所有列。优化器算完成本，干脆改用全表扫描 + filesort。

**做法**：把一条 SQL 拆成两步 —— ① 只查 `id`（`idx_created_at` 的叶子节点里就存着 id，是**覆盖索引**，不回表），② 只对这 `size` 条 id 回表取整行。**回表次数从 `offset + size` 降到 `size`。**

| 指标 | 优化前 | 优化后 | 提升 |
|---|---:|---:|---:|
| 执行计划 | Table scan + Sort | Covering index scan | — |
| 扫描行数 | 128,064 | **10,010** | — |
| filesort | 125,571 行 | **无**（索引天然有序） | — |
| SQL 耗时 | 542 ms | **5.41 ms** | **100×** |
| 端到端平均响应 | 3,038 ms | **301 ms** | **10.1×** |
| **QPS** | 11.3 | **123.3** | **10.9×** |

> ⚠️ **SQL 层快了 100 倍，端到端只快 10 倍** —— 差额来自 `COUNT(*)`（41ms，占 SQL 总时间 54%）和序列化、连接池等固定开销。**只测一头会得出错误结论。**

### 并发梯度：系统的工作点

| 并发 | 响应时间 | QPS | 说明 |
|---:|---:|---:|---|
| 1 | 27 ms | 35.3 | 单请求基线 |
| 10 | 39 ms | 195.8 | 接近线性扩展 |
| **25** | **74 ms** | **252.2** | ⭐ **吞吐峰值** |
| 50 | 299 ms | 124.3 | ⚠️ 过载，吞吐腰斩 |

### 试过但**无效**的改动

连接池 `10 → 50`：QPS **124.5 → 124.3**，平均响应 **299ms → 299ms**，没有任何变化。

原因：`QPS × 单请求持有连接的时间 = 124 × 0.077s ≈ 9.5`，**10 个连接正好覆盖** —— 它一直在满负荷工作，而不是把请求堵在门外。

> ⚠️ **这句话只回答「50 并发下够用」，不回答「10 个永远够用」。**
> 25 并发那个更高吞吐的工作点（252 QPS）**没有单独复测连接池**，所以「10 个在那里够不够」是**未经验证的推断**。
> 配置保留 `maximum-pool-size: 50` 是给更高吞吐留余量，**不是优化成果**。

> 「先测量、再动手」比「照着经验帖调参」更值得写下来 —— 包括**写清楚哪一段是量出来的、哪一段只是推测**。

### 压测方法上的两个坑

1. **随机参数 ≠ 冷缓存** —— `page` 随机 1~2000 时，缓存 key 空间只有 2000 个，请求超过这个数后全部命中缓存。必须用 Redis 的 `keyspace_hits / keyspace_misses` 复核**到底压到了哪一层**（实测热缓存组 926,061 个请求，`keyspace_misses` 只增加 1）
2. **停掉 Redis 制造未命中是错的** —— Lettuce 会挂起等 `command timeout`（3s × 读+写 = 6s），测出来的是 **Redis 超时，不是数据库**

### 对照实验：10 倍到底是谁的功劳？

延迟关联和 `MAX_PAGE` 是同一次改动引入的。既然随机页码会被 `MAX_PAGE` 夹到 200 以内，那「10 倍」里有多少其实是夹页夹出来的？**拆开各跑一轮：**

| 方案 | 开了什么 | QPS | 平均响应 |
|:---:|---|---:|---:|
| B | 都没有（原始代码） | **11.5** | **3,486 ms** |
| A | **只有延迟关联** | **127.2** | **325 ms** |
| — | 两个都开 | 123.3 | 301 ms |

**延迟关联单独就有 11.1 倍 —— 页码上限没有偷功劳。** 加上 `MAX_PAGE` 后 QPS 反而略降（噪声内）。

> 📌 **`MAX_PAGE = 200` 是产品判断，不是性能优化。** 它对吞吐没有可测量的贡献；
> 它的价值是**从需求上砍掉一个本就不该存在的场景**（失物招领不可能有人翻到第 2000 页），
> 避免极端参数打穿数据库。**两笔账不能记在同一个账本上。**

> 📌 压测机为**本机**（JMeter / 后端 / MySQL / Redis 同一台），绝对数字受此限制；但**优化前后的对比是有效的**（两轮条件一致，并用缓存命中率复核过）。

---

## AI 物品匹配效果（可复现的评测）

改造前后用**同一套评测集、同一套指标**横向对比，脚本在 [`eval/`](./eval/) 目录，可完整复现。

### 评测集怎么来的

从库里抽取物品 → 用大模型改写成**失主视角的模糊描述**（故意丢掉精确型号，例如把「iPhone 15 深空黑」写成「一个苹果手机」）→ 人工抽检。共 **146 条**，难度分三档：措辞差异 / 信息缺失 / 别名口语。标准答案是原物品 ID。

> 先有基线才能量化。**没有基线的优化等于没有优化。**

### 结果

| 指标 | 基线 | 现在 |
|---|---:|---:|
| **Hit@5**（端到端命中率） | 19.2% | **93.2% ~ 94.5%** |
| 召回率（标准答案进了候选列表） | — | **95.2%** |
| 精排准确率 | — | 97.8% |
| 平均 prompt tokens / 次 | 3,653 | 2,370 |

**分阶段验证**（证明每一步各自的价值，一次只改一个变量）：

```
基线     Hit@5 19.2%   tokens 3653
P2a 后   Hit@5 19.2%   tokens 1569   ← prompt 瘦身：只省成本，命中率不变（预期内）
P2b 后   Hit@5 94.5%   tokens 2370   ← 召回重构：命中率的提升全部来自这一步
```

> ⚠️ **Hit@5 写成区间是有意的**：同一份代码跑两次分别是 94.5% 和 93.2%，**波动 ±1.3 个点**（`temperature=0` 并不保证输出确定）。小于这个幅度的改进一律不认，要重跑确认。

### 关键改动

- **候选召回**：从「取最近 30 条塞进 prompt」改为「全量召回 + 2-gram 打分」—— 原方案候选超过 30 条就漏召，且 token 成本随物品数线性上涨
- **结构化返回**：匹配接口从只返回一段文字，改为返回 `matches` 数组（匹配度 + 匹配理由）+ 可观测信息（候选数 / 耗时 / 降级原因），前端据此渲染

### 🔥 踩过的坑：降级吞掉了失败

大模型超时 → 接口返回 HTTP 200 + 「AI 服务繁忙」→ **评测脚本把「调用失败」当成「未命中」算进了命中率** → 连续得出两个方向完全相反的错误结论。

**修复**：响应体加 `degradeReason` 字段（`LLM_ERROR` / `NO_CANDIDATES`），评测脚本据此剔除失败样本，并把**降级率作为一等指标**输出。

```bash
node eval/run-eval.js <标签>      # 例：node eval/run-eval.js after-p2b
```

### 模型选型

对比过 DeepSeek 与 Qwen2.5-7B（免费档）。Qwen 真实水平 84.2%，与 DeepSeek 差约 8 个点；**但真正的代价是可用性** —— 免费档降级率在 1.4% ~ 32.2% 之间剧烈波动（失败原因 `Read timed out`）。加重试（指数退避，只对超时 / 5xx / 429 重试，401/400 不重试）后降到 **0.0%**。

供应商通过配置切换（`llm.api-url` / `api-key` / `model` 三项是一套），不写死在代码里。

---

## 快速启动

### 1. 环境要求

- JDK 17+
- MySQL 8.x
- Maven 3.6+
- Redis（**可选**，不装也能跑 —— 连不上时自动降级直查数据库）

### 2. 配置数据库

```bash
# 应用首次启动会自动建库建表，也可以手动执行 sql/schema.sql
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS lost_found CHARACTER SET utf8mb4"
```

数据库账号密码可通过环境变量 `MYSQL_USERNAME` / `MYSQL_PASSWORD` 注入，或写入本地文件 `application-local.yml`（已 gitignore）。

### 3. 配置 AI（可选）

```bash
# 不配也能启动，AI 接口会返回兜底话术并带上降级原因
export DEEPSEEK_API_KEY="your-api-key"
```

### 4. 启动后端

```bash
mvn spring-boot:run        # http://localhost:8080
```

### 5. 启动前端

```bash
cd frontend && npm install && npm run dev    # http://localhost:5173
```

前端通过 Vite 代理转发 `/api` 到 8080，无需额外跨域配置。

---

## 接口列表

| 方法 | 路径 | 需登录 | 说明 |
|------|------|:---:|------|
| POST | `/api/user/register` | — | 注册 |
| POST | `/api/user/login` | — | 登录，返回 JWT token |
| GET | `/api/user/me` | ✅ | 当前用户信息 |
| POST | `/api/item` | ✅ | 发布物品（multipart：`data` + 可选 `image`） |
| GET | `/api/item` | — | 物品列表（分页 + 筛选 + 排序） |
| GET | `/api/item/{id}` | — | 物品详情 |
| GET | `/api/item/my` | ✅ | 我的发布 |
| PUT | `/api/item/{id}/status` | ✅ | **撤销「已认领」**（仅发布者，只允许 `CLAIMED → UNCLAIMED`） |
| POST | `/api/ai/chat` | ✅ | AI 问答 |
| POST | `/api/ai/query` | ✅ | AI 物品匹配 |
| POST | `/api/claim` | ✅ | 提交认领申请（含特征证明） |
| GET | `/api/claim/my` | ✅ | 我发起的认领申请 |
| GET | `/api/claim/received` | ✅ | 我收到的认领申请（审核台） |
| GET | `/api/claim/{id}` | ✅ | 申请详情 + 流转时间线（仅当事人） |
| POST | `/api/claim/{id}/approve` | ✅ | 审核通过 |
| POST | `/api/claim/{id}/reject` | ✅ | 驳回（理由必填） |
| POST | `/api/claim/{id}/cancel` | ✅ | 撤回尚未审核的申请 |
| POST | `/api/claim/{id}/complete` | ✅ | 确认收到物品 → 物品转「已认领」 |
| GET | `/api/notification/my` | ✅ | 我的站内通知（分页，内联匹配明细） |
| GET | `/api/notification/unread-count` | ✅ | 未读条数（角标用） |
| PUT | `/api/notification/{id}/read` | ✅ | 标记单条已读（仅本人） |
| PUT | `/api/notification/read-all` | ✅ | 全部标记已读 |

**认证方式：** 登录后拿到 token，请求头加 `Authorization: Bearer <token>`。

> ⚠️ **鉴权规则是按 HTTP 方法分的，不是按接口分的**：所有 `GET` 请求允许无 token 访问（公开浏览），
> 所有 `POST / PUT / DELETE` 必须携带有效 token。所以 AI 的两个接口虽然是「查询语义」，但因为是 POST，**同样需要登录**。
> 这条规则写在 `JwtInterceptor` 里 —— 之所以不逐个接口配置，是为了避免新增接口时漏配导致越权。

---

## 项目结构

```
lost-found/
├── src/main/java/com/lostfound/
│   ├── common/                          # 公共组件
│   │   ├── Result.java                  # 统一返回体 {code, message, data, timestamp}
│   │   ├── ResultCode.java              # 状态码常量
│   │   ├── BusinessException.java       # 业务异常
│   │   ├── GlobalExceptionHandler.java  # 全局异常拦截（分类处理，不一律 500）
│   │   └── RetrySupport.java            # 通用重试（退避策略，大模型与向量共用）
│   ├── config/
│   │   ├── JwtConfig.java / JwtUtil.java      # JWT 配置与工具
│   │   ├── WebConfig.java                     # CORS + 拦截器 + 静态资源映射
│   │   ├── AsyncConfig.java                   # 自动匹配用的有界线程池（@EnableAsync）
│   │   ├── LlmConfig.java                     # 大模型配置（供应商可切换）
│   │   ├── EmbeddingConfig.java               # 向量服务配置
│   │   ├── RedisConfig.java                   # 缓存序列化（注册 JavaTimeModule）
│   │   ├── GracefulCacheErrorHandler.java     # 缓存异常降级，不让 Redis 拖垮服务
│   │   ├── RateLimitInterceptor.java          # 限流拦截器
│   │   ├── MybatisPlusConfig.java             # 分页插件
│   │   └── MyMetaObjectHandler.java           # 时间字段自动填充
│   ├── controller/                      # 接口层
│   ├── service/                         # 业务层
│   │   ├── CandidateService.java        # 候选召回（2-gram 打分，可指定招领/失物池）
│   │   ├── ClaimService.java            # 认领闭环：状态机 + 流转日志 + 审核/撤回/确认收到
│   │   ├── AutoMatchService.java        # 发布后自动匹配（开关门面）+ AutoMatchRunner（异步执行体）
│   │   ├── NotificationService.java     # 站内通知：生成 / 列表 / 已读
│   │   ├── EmbeddingService.java        # 向量化抽象（当前实现：云端 bge-m3，未接入召回）
│   │   └── …                            # Item / User / Ai / File
│   ├── mapper/  entity/  dto/           # 数据访问 / 表映射 / 请求响应对象
│   └── interceptor/JwtInterceptor.java  # JWT 鉴权
├── src/test/                            # 24 个测试（含 MockMvc 接口测试）
├── frontend/                            # Vue 3 + Element Plus（5 个页面）
├── eval/                                # AI 匹配评测：数据集 / 生成器 / 评测脚本 / 各阶段报告
├── bench/                               # 压测：JMeter 计划 + REPORT.md
├── docs/technical-notes.md              # 36 条项目技术笔记
└── sql/schema.sql                       # 建表脚本（含索引）
```

**调用链：** Controller → Service → Mapper → DB，`JwtInterceptor` 在 Controller 之前执行鉴权。

---

## 数据库

### user 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| username | VARCHAR(50) | 唯一，登录用 |
| password | VARCHAR(255) | BCrypt 加密存储 |
| nickname | VARCHAR(50) | 显示昵称 |
| phone | VARCHAR(20) | 手机号 |
| role | VARCHAR(20) | USER / ADMIN |

### item 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| user_id | BIGINT FK | 发布者 ID |
| title | VARCHAR(100) | 物品标题 |
| type | VARCHAR(10) | LOST（寻物）/ FOUND（招领） |
| category | VARCHAR(30) | 分类 |
| location | VARCHAR(200) | 地点 |
| description | TEXT | 详细描述 |
| image_url | VARCHAR(500) | 图片路径 |
| contact | VARCHAR(100) | 联系方式 |
| status | VARCHAR(20) | UNCLAIMED / CLAIMED |
| version | INT | 乐观锁字段 |
| created_at / updated_at | DATETIME | 自动填充 |

索引：`idx_created_at`（分页默认按时间倒序）、`idx_type`、`idx_category`、`idx_status`、`idx_user_id`。

### claim_application 表（认领申请）

`id` / `item_id` / `applicant_id` / `owner_id`（冗余自 `item.user_id`）/ `proof_text`（**特征证明**）/
`proof_image` / `contact` / `status` / `reject_reason` / `reviewed_at` / `completed_at` /
`active_flag`、`approved_flag`（**生成列**）/ `version` / `created_at` / `updated_at`。

状态：`PENDING`（待审核）→ `APPROVED`（已通过）/ `REJECTED`（已驳回）/ `CANCELLED`（已撤回）；
`APPROVED` → `COMPLETED`（已完成归还）。

两个生成列配合唯一键表达「有条件的唯一」：同一人对同一物品只能有一条**活跃**申请
（被驳回后可以重新申请），同一物品最多只能有一条**已通过**的申请。

### claim_log 表（状态流转日志）

`id` / `claim_id` / `from_status` / `to_status` / `operator_id` / `operator_role`（APPLICANT / OWNER / SYSTEM）/
`remark` / `created_at`。**只追加，永不修改** —— 认领详情页的时间线就是它的顺序读出。

更完整的状态机、权限规则、并发与缓存一致性说明见 [`CLAUDE.md`](./CLAUDE.md) 的「认领闭环」一节。

### notification / notification_match 表（站内通知）

**`notification`（主表）**：`id` / `receiver_id`（收件人，恒为失物主人）/ `source_item_id`（触发这次匹配的物品，即本次发布的那条）/
`content`（汇总文案快照）/ `is_read` / `read_at` / `created_at`。
唯一键 **`uk_source_receiver(source_item_id, receiver_id)`** —— 「一次发布 + 一个收件人 = 至多一条」，去重靠它。

**`notification_match`（明细表）**：`id` / `notification_id` / `lost_item_id` / `found_item_id` / `score` / `reason` / `created_at`。
一条汇总通知可以带多条匹配明细，每行指向**一个确定的 itemId**，并带上分数与匹配理由，点开就能看到具体是哪些、像在哪。
唯一键 `uk_notification_pair(notification_id, lost_item_id, found_item_id)`。

> 两张表的关系是 1:N —— 这不是额外复杂度，而是"一条通知带 N 个匹配"这件事本身的形状：
> 塞进主表只能靠 JSON 列或逗号拼接，前者项目里没有先例，后者没法校验也没法查询。

发布后自动匹配的触发时机、异步执行、开关与类别守卫见 [`CLAUDE.md`](./CLAUDE.md) 的「发布后自动匹配与站内通知」一节。

---

## 设计决策

- **为什么不用 Spring Security？** 项目只需 JWT 登录认证，不需要角色权限、OAuth2 等。只引入 `spring-security-crypto` 做 BCrypt + 手写拦截器，代码量少、好理解
- **为什么用单体架构？** 校园场景用户量有限，单体 + 水平扩展足够。过早微服务会引入分布式事务、服务发现等额外复杂度，ROI 不高
- **为什么统一返回体？** 前端只需判断 `code === 200`；异常不靠 HTTP 500 返回，由全局异常处理器统一拦截转换
- **为什么 API Key 放环境变量？** 防止密钥泄露到 Git 仓库，生产环境可平滑迁移到配置中心
- **为什么分页用延迟关联而不是直接加索引？** 索引早就有了，问题是**优化器主动放弃了它**。根因是回表成本，所以解法是降低回表次数，而不是再建一个索引
- **为什么 Redis 挂了不报错，而是静默降级直查 DB？** 缓存是性能组件不是正确性组件，它挂了服务应该还能用。**但静默不等于不管** —— 被吞掉的异常必须可观测，否则你永远不知道缓存其实是坏的。这正是下面那个真实 bug 的教训
- **为什么接口失败要带上 `degradeReason`？** 降级返回 HTTP 200 是合理的，但**调用方必须能区分「真的没匹配到」和「服务调用失败」**。两者混在一起，会让基于这批数据的任何统计结论都失真

更完整的模块设计与取舍记录见 [`docs/technical-notes.md`](./docs/technical-notes.md)。

---

## 开发日志

| 日期 | 内容 |
|------|------|
| 08-07 | 项目骨架 + JWT 认证 + 用户注册/登录 + CRUD 接口 |
| 08-08 | 接入 DeepSeek 大模型 + 限流拦截器 + 超时降级 |
| 08-10 | 图片上传（MultipartFile + UUID 重命名 + 扩展名白名单 + 防路径穿越） |
| 08-11 | 个人中心接口 + 抽取 `buildQueryWrapper` 消除三段重复的条件拼接 |
| 08-12 | 乐观锁防止并发认领冲突（version 字段 + 重试）+ Redis 缓存 |
| 08-31 | 缓存穿透修复（恢复空值缓存 + 详情判空抛异常）+ JUnit 单元测试 |
| 09-05 | Vue 3 前端 5 个页面 + 缓存降级组件 + MockMvc 接口测试 |
| 09-11 | 数据库凭据改走本地文件，不再明文入库；清理仓库中的个人信息 |
| 09-15 | **AI 匹配迭代**：建评测集与基线 → prompt 瘦身 → 召回重构 → 加召回层指标 → 模型选型 → 重试与降级可观测 |
| 09-15 | 修 Redis 序列化 bug（`LocalDateTime` 未注册 `JavaTimeModule`，缓存写入一直失败却被降级吞掉）；补 `created_at` / `category` 索引 |
| 09-15 | 前端整体美化（卡片列表 / 聊天气泡 / 统一主题）+ 个人中心页面 |
| 09-16 | **压测**：12.8 万行数据下定位深分页瓶颈，延迟关联优化（SQL 100×、端到端 10×）+ 并发梯度测试 |
| 10-08 | **认领闭环**：申请 / 特征证明 / 审核 / 驳回 / 撤回 / 确认收到全链路；状态机 + 流转日志表；生成列唯一键兜底并发；归还后自动关闭其他申请；联系方式按身份收敛；审核台与「我的申请」两个新页面 |
| 10-08 | **AI 匹配筛选策略拆分**：主动匹配恢复「Top5、不设最低分」；80 分门槛下移到自动匹配（`ai.match.interactive.*` / `ai.match.auto.*` 两套独立配置）；评测环境隔离到 `lost_found_eval` |
| 10-08 | **发布后自动匹配 + 站内通知**：两个方向（失物→招领、招领→失物）；异步执行不阻塞发布、异常隔离；汇总通知（一条通知带 N 条匹配明细）；总开关 `ai.match.auto.enabled`；类别守卫 |

---

## 已知问题 / 后续可做

- `COUNT(*)` 平均 41ms，占 SQL 总时间 54% —— 可缓存计数结果，或前端改成「加载更多」不做总数
- 限流器是内存实现：Map 的 key 从不清理（长时间运行会缓慢泄漏），且多实例部署时各算各的
- 向量化能力已实现但**尚未接入召回链路**（当前召回用 2-gram 打分，效果已达 95.2% 召回率）
- Docker 配置已写好（多阶段构建 + compose 三服务），但**尚未在真实环境验证过**
- **发布后自动匹配 + 站内通知**：物品发布成功后由后台**异步**跑一遍匹配，够格就给可能的主人发站内通知。
  两个方向都支持 —— 发布失物时匹配已有招领（通知发布者本人），发布招领时**反向**匹配已有失物
  （按失主分组，每位失主对同一次发布最多收到**一条汇总通知**，里面带上匹配到的全部条目：分数 + 理由 + 跳转）。
  - 与主动匹配**共用召回与精排**，但**筛选策略独立**：自动场景用分数门槛（`ai.match.auto.min-score`）
    压掉无关通知，主动场景不设门槛（用户主动来找，漏报的代价更大）
  - 有总开关 `ai.match.auto.enabled`（默认 true）：关掉后发布照常成功，但**不提交任务、不调模型、不发通知**
  - 类别守卫：失物与招领类别**明确不一致**时默认不通知；任一边是「其他」这类泛分类时交给大模型按文本判断
- **评测环境已隔离**：压测数据（12.6 万条）与评测种子（2000 条）分库存放，
  评测跑在 `lost_found_eval` 上，详见 `eval/setup-eval-db.sh` 与 `application-eval.yml`
