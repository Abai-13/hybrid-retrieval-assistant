-- 校园失物招领平台 — 数据库初始化脚本
-- MySQL 8.0 容器首次启动时自动执行

CREATE TABLE IF NOT EXISTS `user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '用户ID',
    `username`   VARCHAR(50)  NOT NULL                 COMMENT '登录用户名',
    `password`   VARCHAR(255) NOT NULL                 COMMENT 'BCrypt 加密密码',
    `nickname`   VARCHAR(50)  DEFAULT NULL             COMMENT '显示昵称',
    `phone`      VARCHAR(20)  DEFAULT NULL             COMMENT '手机号',
    `avatar_url` VARCHAR(500) DEFAULT NULL             COMMENT '头像URL',
    `role`       VARCHAR(20)  NOT NULL DEFAULT 'USER'  COMMENT '角色: USER/ADMIN',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `item` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '物品ID',
    `user_id`     BIGINT       NOT NULL                 COMMENT '发布者ID',
    `title`       VARCHAR(100) NOT NULL                 COMMENT '物品标题',
    `type`        VARCHAR(10)  NOT NULL                 COMMENT 'LOST: 寻物 / FOUND: 招领',
    `category`    VARCHAR(30)  NOT NULL                 COMMENT '类别: 电子产品/证件/衣物/书籍/其他',
    `location`    VARCHAR(200) DEFAULT NULL             COMMENT '丢失/拾获地点',
    `description` TEXT         DEFAULT NULL             COMMENT '详细描述',
    `image_url`   VARCHAR(500) DEFAULT NULL             COMMENT '图片URL',
    `contact`     VARCHAR(100) DEFAULT NULL             COMMENT '联系方式',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'UNCLAIMED' COMMENT 'UNCLAIMED / CLAIMED',
    `version`     INT          NOT NULL DEFAULT 0       COMMENT '乐观锁版本号',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_type` (`type`),
    KEY `idx_category` (`category`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品表';

-- ============================================================
-- 认领闭环 —— 2026-10-08 新增
--
-- ⚠️ 本文件只在数据库**首次初始化**时被容器执行一次
--    （docker-entrypoint-initdb.d 的机制，且 CREATE TABLE IF NOT EXISTS 不会改动已有表）。
--    已有数据库必须手动执行本节的语句，否则新表不会出现。
-- ============================================================

-- ---- item：状态枚举扩展（只改注释，不改存储类型，MySQL 8 走 INSTANT 算法）----
ALTER TABLE `item`
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'UNCLAIMED'
    COMMENT 'UNCLAIMED: 待认领 / CLAIMING: 认领中 / CLAIMED: 已认领';

-- ---- 认领申请表 ----
CREATE TABLE IF NOT EXISTS `claim_application` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '申请ID',
    `item_id`       BIGINT       NOT NULL                 COMMENT '被申请的物品ID',
    `applicant_id`  BIGINT       NOT NULL                 COMMENT '申请人ID（失主）',
    `owner_id`      BIGINT       NOT NULL                 COMMENT '物品发布者ID（冗余自 item.user_id，免 JOIN 查审核台）',
    `proof_text`    VARCHAR(500) NOT NULL                 COMMENT '物品特征证明',
    `proof_image`   VARCHAR(500) DEFAULT NULL             COMMENT '证明图片URL',
    `contact`       VARCHAR(100) DEFAULT NULL             COMMENT '申请人本次认领的联系方式（为空取 user.phone）',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED/COMPLETED',
    `reject_reason` VARCHAR(200) DEFAULT NULL             COMMENT '驳回理由',
    `reviewed_at`   DATETIME     DEFAULT NULL             COMMENT '审核时间',
    `completed_at`  DATETIME     DEFAULT NULL             COMMENT '完成归还时间',
    `active_flag`   TINYINT      GENERATED ALWAYS AS (IF(`status` IN ('PENDING','APPROVED'), 1, NULL)) VIRTUAL COMMENT '活跃标记，配合唯一键约束"同一人对同一物品只能有一条活跃申请"',
    `approved_flag` TINYINT      GENERATED ALWAYS AS (IF(`status` = 'APPROVED', 1, NULL)) VIRTUAL COMMENT '通过标记，配合唯一键约束"同一物品最多一条已通过申请"',
    `version`       INT          NOT NULL DEFAULT 0       COMMENT '乐观锁版本号',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_applicant_active` (`item_id`, `applicant_id`, `active_flag`),
    UNIQUE KEY `uk_item_approved` (`item_id`, `approved_flag`),
    KEY `idx_applicant_created` (`applicant_id`, `created_at`),
    KEY `idx_owner_created` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认领申请表';

-- ---- 认领状态流转日志表（只追加，不更新不删除 —— 这是"过程可信"的前提）----
CREATE TABLE IF NOT EXISTS `claim_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '日志ID',
    `claim_id`      BIGINT       NOT NULL                 COMMENT '所属申请ID',
    `from_status`   VARCHAR(20)  DEFAULT NULL             COMMENT '变更前状态（首次提交为 NULL）',
    `to_status`     VARCHAR(20)  NOT NULL                 COMMENT '变更后状态',
    `operator_id`   BIGINT       NOT NULL                 COMMENT '操作人ID',
    `operator_role` VARCHAR(20)  NOT NULL                 COMMENT 'APPLICANT: 申请人 / OWNER: 物品发布者 / SYSTEM: 系统',
    `remark`        VARCHAR(200) DEFAULT NULL             COMMENT '备注（如驳回理由）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_claim_id` (`claim_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认领状态流转日志表';

-- ============================================================
-- 发布后自动匹配 + 站内通知 —— 2026-10-08 新增
-- 同样只在数据库首次初始化时执行；已有库要手动跑本节语句。
-- ============================================================

-- ---- 通知主表：一条「汇总通知」----
CREATE TABLE IF NOT EXISTS `notification` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '通知ID',
    `receiver_id`    BIGINT       NOT NULL                 COMMENT '收件人ID（失物主人）',
    `source_item_id` BIGINT       NOT NULL                 COMMENT '触发这次匹配的物品ID（本次发布的那条）',
    `content`        VARCHAR(200) NOT NULL                 COMMENT '汇总文案快照（生成时落库，读取时不再拼）',
    `is_read`        TINYINT      NOT NULL DEFAULT 0       COMMENT '0=未读 1=已读',
    `read_at`        DATETIME     DEFAULT NULL             COMMENT '已读时间',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_receiver` (`source_item_id`, `receiver_id`),
    KEY `idx_receiver_created` (`receiver_id`, `created_at`),
    KEY `idx_receiver_unread` (`receiver_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知表';

-- ---- 通知明细：这条通知里具体匹配到了哪几对物品 ----
--
-- 为什么必须单独一张表：一条汇总通知要带 N 个匹配，每个都要有
-- 「失物ID + 招领ID + 分数 + 匹配理由」。塞进主表只能靠 JSON 列或逗号拼接 ——
-- 前者项目里没有先例，后者没法校验也没法查询。1:N 就是这段关系本身的形状。
--
-- 两个 id 都存（而不是只存"对方那条"）：这样不管通知是
-- 「发 LOST 时匹配到 FOUND」还是「发 FOUND 时匹配到 LOST」产生的，
-- 「你的哪件东西 ↔ 对方的哪件东西」永远是明确的。
--
-- ⚠️ 注意这里**不是**「同标题就算同一条」——每一行都指向一个具体的 itemId。
CREATE TABLE IF NOT EXISTS `notification_match` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '明细ID',
    `notification_id` BIGINT       NOT NULL                 COMMENT '所属通知ID',
    `lost_item_id`    BIGINT       NOT NULL                 COMMENT '失物ID',
    `found_item_id`   BIGINT       NOT NULL                 COMMENT '招领物品ID',
    `score`           INT          NOT NULL                 COMMENT 'AI 匹配分数',
    `reason`          VARCHAR(255) DEFAULT NULL             COMMENT 'AI 给出的匹配理由',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notification_pair` (`notification_id`, `lost_item_id`, `found_item_id`),
    KEY `idx_notification` (`notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知匹配明细表';

-- ============================================================
-- AI 找物助手会话 —— 2026-10-14 新增（第一阶段）
--
-- 这里和 sql/migrations/2026-10-14_ai_conversation.sql 是**同一段 DDL**：
--   · 本文件只在数据库**首次初始化**时执行（容器 entrypoint），负责"空库也有这两张表"
--   · 已有库升级走那个迁移文件（不要求重建）
-- 两处都保留是因为这一个文件改不了已经跑过的库，而迁移文件管不了将来的空库。
-- ============================================================

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
