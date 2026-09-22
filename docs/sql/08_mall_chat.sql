-- ============================================================
-- 08 AI 对话记忆：会话 + 消息
--
-- 【为什么这两张表不继承 BaseEntity —— 踩了必报错】
--   BaseEntity 上标着 @TableLogic，MyBatis-Plus 会给每条 SQL 自动追加
--   `deleted = 0`。而本脚本的两张表【没有 deleted 列】（理由见下），
--   实体若继承 BaseEntity，任何一次查询都会直接抛
--   Unknown column 'deleted' in 'where clause'。
--   正确做法：实体自己声明 id / create_time，不继承 BaseEntity。
--   同类先例：mall_user_role / mall_role_permission。
--
-- 【为什么这两张表走物理删除 —— 对逻辑删除铁律的有意例外】
--   项目其他业务表一律 `deleted = 本行 id`。这里刻意不这么做，三个理由：
--     ① 对话是日志/流水性质，不是需要审计追溯的业务实体（不像订单、资金）
--     ② 产品语义上「删除对话」就是真删。用户要求删除而系统仍保留，
--        是隐私问题，不只是"多占点空间"
--     ③ 消息表是增长最快的一张表。逻辑删除会让体积翻倍，
--        且每条查询都要多带一个 deleted = 0 条件
--   代价是不可恢复 —— 所以前端删除必须给二次确认。
--
-- 【为什么不能一张逻辑删、一张物理删】
--   若会话逻辑删、消息物理删：删会话后消息就成孤儿（会话不可见、消息还在），
--   永久占空间且无从清理。所以两张表统一物理删除，
--   删会话必须在同一个事务里删掉它的全部消息。
--
-- 【为什么不用外键 ON DELETE CASCADE】
--   外键能自动级联删消息，看似更省心，但：
--     ① 本项目所有既有表都没有外键，加进来破坏一致性
--     ② 项目目标是拆微服务（mall-chat 很可能第一批拆出去），
--        跨库外键是硬障碍
--   因此删除顺序由应用层的 @Transactional 方法保证，并写测试覆盖。
--
-- 【可安全重跑】（CREATE TABLE IF NOT EXISTS）
--
-- 【执行方式】
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 < 08_mall_chat.sql
-- ============================================================

USE mall;


-- ============================================================
-- 第 1 步：会话表
--
-- 【为什么要 conversation_no（UUID）而不是直接拿自增 id 对外】
--   接口形态是 POST /ai/chat { conversationId }。
--   若 conversationId 就是自增 id，攻击者用 1,2,3… 遍历即可尝试读取他人对话 ——
--   只要归属校验有一处疏漏就是批量泄露。UUID 不可枚举，与归属校验形成两道独立防线。
--   内部主键仍用自增 BIGINT（聚簇索引短、页分裂少），对外只暴露 conversation_no。
--
-- 【user_id 是防 IDOR 的根本】
--   拿到 conversationId 之后必须校验它属于当前登录用户。
--   这条校验的落点在 service 层，不在 ChatMemoryStore（store 只是日志，不做鉴权）。
--
-- 【索引为什么是 idx_user_active (user_id, last_active_at)】
--   会话列表的固定查询形态：
--     WHERE user_id = ? ORDER BY last_active_at DESC
--   等值列在前、排序列在后，一个索引同时吃掉过滤与排序，
--   执行计划里不会出现 filesort。
-- ============================================================

CREATE TABLE IF NOT EXISTS mall_chat_conversation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID(内部主键,不对外暴露)',
    conversation_no CHAR(36)        NOT NULL                COMMENT '会话标识(UUID,对外暴露,不可枚举)',
    user_id         BIGINT UNSIGNED NOT NULL                COMMENT '归属用户ID(防IDOR的根本)',
    title           VARCHAR(100)    NOT NULL DEFAULT '新对话' COMMENT '会话标题(取首轮提问前30字)',
    message_count   INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '消息条数(含user与assistant)',
    last_active_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间(会话列表排序用)',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_no (conversation_no),
    KEY idx_user_active (user_id, last_active_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT ='AI对话会话表';


-- ============================================================
-- 第 2 步：消息表
--
-- 【为什么需要 seq，不靠 create_time 排序】
--   DATETIME 精度只到秒。一轮对话的 user 消息与 assistant 消息完全可能落在同一秒内，
--   此时 ORDER BY create_time 的顺序是不确定的 —— 可能把 assistant 排到 user 前面。
--   历史顺序错乱会让模型看到「自己先说话、用户后提问」，表现为答非所问，
--   而且是偶发的，极难排查。seq 是会话内从 1 开始的单调递增整数，排序绝对确定。
--
-- 【uk_conv_seq (conversation_id, seq) 为什么做成唯一键】
--   它同时承担两个职责：
--     ① 查询索引 —— 「取某会话全部历史」正是
--        WHERE conversation_id = ? ORDER BY seq，一个索引全覆盖
--     ② 并发兜底 —— 同一会话并发写入时若 seq 撞车，
--        唯一键会直接报错，而不是静默写坏顺序。
--        真正的串行化属第 3 课应用层，唯一键是最后一道防线。
--
-- 【为什么存 token 用量】
--   输入 token 成本随轮数 O(n²) 增长。把每轮用量存下来，
--   才能回答「某用户某月花了多少」这类运维问题。
--   只对 assistant 消息有意义（它是那次模型调用的产物）。
--
-- 【content 用 TEXT 而不是 VARCHAR】
--   单条长度上限已由 ChatDTO 的 @Size(max = 2000) 在入口约束。
--   VARCHAR(2000) 在多字节字符集下容易触到行长度上限，TEXT 更稳妥；
--   本项目量级下两者的性能差异可忽略。
-- ============================================================

CREATE TABLE IF NOT EXISTS mall_chat_message (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    conversation_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属会话ID(物理删除,无外键,级联由应用层事务保证)',
    seq               INT UNSIGNED    NOT NULL                COMMENT '会话内序号,从1开始,决定消息顺序',
    role              VARCHAR(16)     NOT NULL                COMMENT '角色:system/user/assistant',
    content           TEXT            NOT NULL                COMMENT '消息正文',
    prompt_tokens     INT UNSIGNED    DEFAULT NULL            COMMENT '输入token数(仅assistant消息有值)',
    completion_tokens INT UNSIGNED    DEFAULT NULL            COMMENT '输出token数(仅assistant消息有值)',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_seq (conversation_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT ='AI对话消息表(只追加,不修改)';


-- ============================================================
-- 第 3 步：自检
--   预期：
--     conv_table = 1
--     msg_table  = 1
--     索引：mall_chat_conversation → PRIMARY / uk_conversation_no / idx_user_active
--          mall_chat_message      → PRIMARY / uk_conv_seq
-- ============================================================

SELECT COUNT(*) AS conv_table FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_chat_conversation';

SELECT COUNT(*) AS msg_table FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_chat_message';

SELECT TABLE_NAME,
       INDEX_NAME,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols,
       IF(NON_UNIQUE = 0, 'UNIQUE', 'INDEX')           AS kind
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('mall_chat_conversation', 'mall_chat_message')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_NAME, INDEX_NAME;
