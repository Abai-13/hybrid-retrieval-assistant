# 基于混合检索的多轮对话寻物系统

校园场景下的失物招领服务平台：注册登录、物品发布与认领、图片上传、站内通知，以及支持多轮对话和混合检索的 **AI 找物助手**。

**技术栈：** Java 17 · Spring Boot 3.3 · MyBatis-Plus 3.5 · MySQL 8 · Redis · Qdrant · JWT · 大模型 / Embedding API · Vue 3 · Element Plus · Vite

> 后端、前端、评测脚本、压测脚本均为独立完成。完整提交记录见本仓库 commit 历史。

---

## 功能

- 用户注册 / 登录（JWT 认证 + BCrypt 密码加密）
- 物品发布（**含图片上传**）、分页搜索（按类型 / 分类 / 关键词 / 时间排序）、详情查看
- **认领闭环**：失主提交认领申请并附物品特征证明 → 拾到者审核（通过 / 驳回，驳回需理由）→
  线下核对交接 → 失主确认收到，物品才转为「已认领」。全过程写入 `claim_log`，
  申请详情页可按时间线复盘每一步；归还完成后同一物品上其他申请自动关闭
- 个人中心：我的发布列表 / 我的认领申请（含「确认收到」入口）/ 我收到的申请（审核台）
- **统一找物助手**：用自然语言描述失物，支持补充条件、纠正地点、撤回条件、排除候选和解释匹配理由；会话与消息存入 MySQL，刷新后可恢复
- **混合检索**：2-gram 文字召回 + 云端 Embedding / 自托管 Qdrant 向量召回，按物品 ID 去重并用 RRF 融合，交给云端大模型精排，展示物品卡片与理由
- **发布后自动匹配与通知**：后台异步寻找潜在失主，沿用原 2-gram 召回与独立的分数门槛；不受聊天混合检索开关影响

## 找物助手的处理流程

```text
用户消息 + 已保存的条件摘要 + 最近几轮对话
  → 理解意图，合并补充 / 纠正 / 撤回与排除项
  → 2-gram 召回 + 查询向量化 / Qdrant 检索
  → 按 ID 去重、RRF 排序，向量候选回 MySQL 复核类型和状态
  → 云端 LLM 精排 → 匹配卡片与理由 → 保存本轮摘要与消息
```

发布招领物品后，后台只将标题、类别、地点和描述生成向量；联系方式与认领证明不进入向量文本。
Qdrant 保存物品 ID、向量及模型 / 文本指纹，MySQL 保存业务数据。索引异步更新，失败后可手动补齐；
向量路径不可用时回退到文字召回。当前使用云端对话模型与云端 Embedding，未部署本地模型。

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

以下是历史单轮「2-gram + LLM」链路的评测，改造前后使用同一套评测集和指标，脚本在 [`eval/`](./eval/)。
这些数字不代表本次多轮聊天或混合向量检索的效果；新链路尚未完成同口径的质量评测。

### 评测集怎么来的

从库里抽取物品 → 用大模型改写成**失主视角的模糊描述**（故意丢掉精确型号，例如把「iPhone 15 深空黑」写成「一个苹果手机」）→ 人工抽检。共 **146 条**，难度分三档：措辞差异 / 信息缺失 / 别名口语。标准答案是原物品 ID。

> 先有基线才能量化。**没有基线的优化等于没有优化。**

### 结果

| 指标 | 基线 | 2-gram + LLM（历史版本） |
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
- Node.js 与 npm（运行 Vue 前端）
- Redis（**可选**，不装也能跑 —— 连不上时自动降级直查数据库）
- Docker 与 Qdrant（**可选**，启用向量检索时使用）

### 2. 配置数据库

本地运行不会自动执行建表脚本。从仓库根目录打开 MySQL 客户端：

```bash
mysql -u root -p
```

新建数据库时，在 MySQL 客户端执行：

```sql
CREATE DATABASE IF NOT EXISTS lost_found CHARACTER SET utf8mb4;
USE lost_found;
SOURCE sql/schema.sql;
```

已有本项目数据库升级时，只需补建会话表，不用重建原表：

```sql
USE lost_found;
SOURCE sql/migrations/2026-10-14_ai_conversation.sql;
```

Docker Compose 的 MySQL 仅在数据卷首次初始化时运行 `sql/schema.sql`；已有数据卷同样需要执行上述增量迁移。

数据库账号密码可通过环境变量 `MYSQL_USERNAME` / `MYSQL_PASSWORD` 注入，或写入本地文件 `application-local.yml`（已 gitignore）。

### 3. 配置 JWT 与 AI

`JWT_SECRET` 是必填项，使用自行生成的随机密钥，至少 32 字节。AI 凭据按需配置：

```bash
# Bash
export JWT_SECRET="<替换为你生成的随机密钥，至少32字节>"
export LLM_API_KEY="<你的对话模型API密钥>"
export EMBEDDING_API_KEY="<你的向量模型API密钥>"
```

```powershell
# PowerShell
$env:JWT_SECRET = "<替换为你生成的随机密钥，至少32字节>"
$env:LLM_API_KEY = "<你的对话模型API密钥>"
$env:EMBEDDING_API_KEY = "<你的向量模型API密钥>"
```

对话接口默认使用 DeepSeek，兼容原 `DEEPSEEK_API_KEY` 变量；替换供应商时一起配置 `LLM_API_URL`、`LLM_API_KEY`、`LLM_MODEL`。
Embedding 独立配置，默认使用硅基流动的 `BAAI/bge-m3`（1024 维），支持 `EMBEDDING_API_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL`。
切换向量维度时还需修改 `embedding.dimension`。服务费用与额度按供应商规则计算。

不配置对话密钥时，应用仍可启动，但助手无法完成模型处理并会提示重试；不配置向量密钥时可设置 `AI_VECTOR_ENABLED=false`，使用原 2-gram 召回。
也可将上述值写入私有 `src/main/resources/application-local.yml`；不要把真实密钥提交到仓库。

### 4. 启动后端

```bash
mvn spring-boot:run        # http://localhost:8080
```

### 5. 启动前端

```bash
cd frontend && npm install && npm run dev    # http://localhost:5173
```

前端通过 Vite 代理转发 `/api` 到 8080，无需额外跨域配置。

### 6. 启动向量库 Qdrant（可选 —— 找物助手的语义召回）

```bash
# 本机 Docker（版本固定 v1.19.2，数据落持久化卷）
docker compose --profile vector up -d qdrant

# 或不用 compose：
docker run -d --name lost-found-qdrant -p 6333:6333 -v lost_found_qdrant_data:/qdrant/storage qdrant/qdrant:v1.19.2
```

- 连接配置在 `application.yml` 的 `qdrant.*` 段（地址 `http://127.0.0.1:6333`，可用环境变量 `QDRANT_URL` 覆盖）。
- **不起 Qdrant 也完全能跑**：`ai.vector.enabled=true` 时聊天找物走「2-gram + 向量」融合，
  向量腿连不上就自动回退到原 2-gram，行为与改造前一致；关掉 `AI_VECTOR_ENABLED=false` 则一条向量调用都不会发。
- 只有**聊天找物入口**用混合召回；发布后的自动匹配仍走原 2-gram 路径，不受影响。

**建索引 / 补齐**（发布成功后会自动异步写入，历史数据靠这条命令补；可重复执行）：

```bash
# 补齐：模型与文本都没变的点自动跳过，不重复调用 embedding
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dai.vector.backfill.enabled=true"

# 重建：忽略指纹全量重算（换 embedding 模型后必须执行，并同步换 qdrant.collection 名）
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dai.vector.backfill.enabled=true -Dai.vector.backfill.force=true"
```

补齐随这次后端启动执行；若已有后端占用 8080，先停止旧进程，再用上述命令启动。下次正常启动时去掉补齐参数。

> ⚠️ **换 embedding 模型必须换集合名重建**：不同模型的向量不能混在一个集合里比较。
> 集合里有一个「哨兵点」记录当前模型与维度，模型对不上时补齐会直接报错，不会静默混用。

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
| GET | `/api/ai/session` | ✅ | 读取当前找物会话 |
| POST | `/api/ai/session` | ✅ | 开始新会话 |
| GET | `/api/ai/session/{id}` | ✅ | 读取本人会话及消息 |
| POST | `/api/ai/session/{id}/messages` | ✅ | 发送消息，理解条件并检索 / 解释 |
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
> 所有 `POST / PUT / DELETE` 必须携带有效 token。AI 查询同样需要登录；私人会话的 GET 接口也在 Service 层校验身份和所有权。
> 这条规则写在 `JwtInterceptor` 里 —— 之所以不逐个接口配置，是为了避免新增接口时漏配导致越权。

---

## 项目结构

```
hybrid-retrieval-assistant/
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
│   │   ├── AsyncConfig.java                   # 自动匹配与向量写入各用独立有界线程池
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
│   │   ├── EmbeddingService.java        # 向量化抽象（当前实现：云端 bge-m3）
│   │   ├── ConversationService.java     # 找物会话：意图、条件摘要、消息持久化与重试
│   │   ├── HybridRecallService.java     # 主动聊天的 2-gram + 向量召回 / RRF 融合
│   │   ├── ItemVectorService.java       # 异步索引、向量检索与幂等补齐
│   │   └── …                            # Item / User / Ai / File
│   ├── mapper/  entity/  dto/           # 数据访问 / 表映射 / 请求响应对象
│   └── interceptor/JwtInterceptor.java  # JWT 鉴权
├── src/test/                            # 单元测试与 MockMvc 接口测试
├── frontend/                            # Vue 3 + Element Plus（含统一找物助手）
├── eval/                                # AI 匹配评测：数据集 / 生成器 / 评测脚本 / 各阶段报告
├── bench/                               # 压测：JMeter 计划 + REPORT.md
├── docs/technical-notes.md              # 36 条项目技术笔记
└── sql/                                # 完整建表脚本及 migrations 增量迁移
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
| user_id | BIGINT | 发布者 ID（逻辑关联） |
| title | VARCHAR(100) | 物品标题 |
| type | VARCHAR(10) | LOST（寻物）/ FOUND（招领） |
| category | VARCHAR(30) | 分类 |
| location | VARCHAR(200) | 地点 |
| description | TEXT | 详细描述 |
| image_url | VARCHAR(500) | 图片路径 |
| contact | VARCHAR(100) | 联系方式 |
| status | VARCHAR(20) | UNCLAIMED / CLAIMING / CLAIMED |
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

### ai_conversation / ai_message 表（找物会话）

`ai_conversation` 保存用户所有权、条件摘要、当前请求占用、版本和过期时间；
`ai_message` 保存用户消息、助手结果与卡片 ID，并用 `(conversation_id, request_id, role)` 唯一键处理重复请求。
历史卡片的标题、地点和状态读取当前 MySQL 数据；会话默认保留 7 天，模型上下文使用条件摘要与最近 4 轮对话。

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
| 10-16 | **统一找物助手**：会话持久化、条件补充 / 纠正 / 撤回、候选排除与解释、失败重试与刷新恢复 |
| 10-16 | **混合检索**：云端 Embedding + 自托管 Qdrant，与原 2-gram 按 ID 去重、RRF 融合；异步索引、幂等补齐与故障回退；自动匹配沿用原路径 |

---

## 本次验证范围

2026-10-16 对召回融合、去重、候选排除 / 状态过滤、回退、聊天完整条件传递、发布异常隔离和补齐幂等运行了 19 项定向测试，全部通过。
另用两句短测试文本完成一次真实 Embedding 批量请求（1024 维），在临时 Qdrant 集合写入后检索取回测试点，并删除临时集合。

这次 REST 冒烟证明云端 Embedding 与 Qdrant 基本连通；未验证完整 Spring / 页面业务链路，未进行混合召回质量评测，也未重跑全量、压力或并发测试。

## 已知问题 / 后续可做

- `COUNT(*)` 平均 41ms，占 SQL 总时间 54% —— 可缓存计数结果，或前端改成「加载更多」不做总数
- 限流器是内存实现：Map 的 key 从不清理（长时间运行会缓慢泄漏），且多实例部署时各算各的
- 混合检索已接入主动聊天；历史物品需补齐向量，索引与 MySQL 异步同步；新增链路尚无同口径的召回效果数据
- Docker Compose 的完整应用部署尚未验证；本机 Qdrant REST 冒烟不代表整套 Compose 验收
- **发布后自动匹配 + 站内通知**：物品发布成功后由后台**异步**跑一遍匹配，够格就给可能的主人发站内通知。
  两个方向都支持 —— 发布失物时匹配已有招领（通知发布者本人），发布招领时**反向**匹配已有失物
  （按失主分组，每位失主对同一次发布最多收到**一条汇总通知**，里面带上匹配到的全部条目：分数 + 理由 + 跳转）。
  - 与聊天助手共用原 2-gram 召回组件和 LLM 精排能力，自动场景不接向量召回；**筛选策略独立**：自动场景用分数门槛（`ai.match.auto.min-score`）
    压掉无关通知，主动场景不设门槛（用户主动来找，漏报的代价更大）
  - 有总开关 `ai.match.auto.enabled`（默认 true）：关掉后发布照常成功，但**不提交任务、不调模型、不发通知**
  - 类别守卫：失物与招领类别**明确不一致**时默认不通知；任一边是「其他」这类泛分类时交给大模型按文本判断
- **评测环境已隔离**：压测数据（12.6 万条）与评测种子（2000 条）分库存放，
  评测跑在 `lost_found_eval` 上，详见 `eval/setup-eval-db.sh` 与 `application-eval.yml`
