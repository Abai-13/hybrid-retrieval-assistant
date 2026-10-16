-- ============================================================
-- 增量迁移：AI 找物助手会话（第一阶段）
-- 日期：2026-10-14
-- 适用：已有库直接执行，**不需要重建**，不触碰任何既有表。
--
-- 执行方式（本地 / Docker 都适用）：
--   mysql -u <user> -p lost_found < sql/migrations/2026-10-14_ai_conversation.sql
-- 新建的空库也会拿到这两张表 —— 同样的 DDL 已追加到 sql/schema.sql 末尾，
-- 由 MySQL 容器首次启动时自动执行（docker-entrypoint-initdb.d/01-schema.sql）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，重复执行安全。
-- ============================================================

-- ---- 会话表：一次「找物会话」的边界 ----
--
-- 为什么不放在页面变量或进程内存：刷新、切标签页、重启都会丢；
-- 更关键的是 —— 会话里存的是「用户说过什么」，它必须和登录账号绑定，
-- 放在前端就等于把这份数据交给了不可信的输入。
CREATE TABLE IF NOT EXISTS `ai_conversation` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id`           BIGINT      NOT NULL                COMMENT '所属用户ID（所有权校验的唯一依据）',
    `summary_json`      TEXT        NOT NULL                COMMENT '结构化找物摘要（JSON：条件 + 排除项）',
    `active_request_id` VARCHAR(64) DEFAULT NULL            COMMENT '正在处理的那一轮（会话级占用）；空闲时为 NULL',
    `active_since`      DATETIME    DEFAULT NULL            COMMENT '占用的开始时间 —— 超过一轮最大耗时即视为陈旧，允许后来者接管',
    `version`           INT         NOT NULL DEFAULT 0      COMMENT '乐观锁版本号 —— 同会话并发写摘要时防止覆盖新状态',
    `expires_at`        DATETIME    NOT NULL                COMMENT '过期时间＝最后一次活动 + 保留天数，过期即清理',
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活动时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_updated` (`user_id`, `updated_at`),
    KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 找物助手会话表';

-- ---- 消息表：每轮的用户原话与助手结果 ----
--
-- uk_conv_request_role 是**幂等的实现方式**，不是普通的唯一约束：
--   · 前端每条消息带一个 requestId，网络重试/用户点重试都复用它
--   · 命中这条键就说明「这一轮已经处理过」→ 直接重放上次结果，不重复调模型
--   · USER 与 ASSISTANT 各占一行（role 参与唯一键），所以一轮恰好两行
--
-- payload_json 存助手那轮的**结构化载荷**（物品卡片 + 诊断 + 当时的条件快照）：
--   · 卡片只存 itemId/score/reason，标题/地点/状态在读的时候从库里回填 ——
--     物品改了标题、被认领走，历史卡片必须显示最新状态，而不是当年的快照
--   · 「第二个」指代靠这行里的卡片顺序解析，新排序不会错位
CREATE TABLE IF NOT EXISTS `ai_message` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT      NOT NULL                COMMENT '所属会话ID',
    `request_id`      VARCHAR(64) NOT NULL                COMMENT '客户端请求标识（重试/重放时复用同一个）',
    `role`            VARCHAR(16) NOT NULL                COMMENT 'USER / ASSISTANT',
    `content`         TEXT                                COMMENT '消息正文',
    `status`          VARCHAR(16) NOT NULL DEFAULT 'OK'   COMMENT 'OK / FAILED / IN_PROGRESS（失败和进行中都落库，刷新后仍能看到并重试）',
    `intent`          VARCHAR(24) DEFAULT NULL            COMMENT '本轮判定的意图（仅助手消息）',
    `payload_json`    TEXT                                COMMENT '助手消息的结构化载荷：卡片、诊断、条件快照',
    `version`         INT         NOT NULL DEFAULT 0      COMMENT '乐观锁：让「处理中 → 终态」这一步成为 CAS，晚到的失败写不进已完成的结果',
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（判定「处理中」占位是否已陈旧）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_request_role` (`conversation_id`, `request_id`, `role`),
    KEY `idx_conv_id` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 找物助手消息表';
