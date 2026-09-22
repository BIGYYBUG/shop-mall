-- ============================================================
-- 09 购物车
--
-- 【本脚本做什么】
--   新建 mall_cart_item（购物车明细），并把它两条设计取向写死在结构里：
--     1. 物理删除（不建 deleted 列）
--     2. 唯一键 (user_id, product_id)，用于 UPSERT 原子累加
--
-- 【为什么物理删除，而不是像 mall_user / mall_product 那样 deleted = 本行 id】
--   判据沿用项目已定、且 mall_chat_* 已在用的那三条：
--     ① 非审计实体 —— 购物车不是账、不是凭证，不需要可追溯；
--     ② 产品语义即「真删」—— 用户点删除，就是要它消失；
--     ③ 增长最快 —— 加购/删除是最高频写操作，逻辑删除只会持续堆垃圾行。
--   代价：删了就没了。购物车本身可随时重建，风险可接受。
--
--   ⚠️ 因此实体【不要】继承 BaseEntity —— BaseEntity 带 @TableLogic，
--      MyBatis-Plus 会自动往 deleted 列写值，而本表没有这列，
--      直接报 Unknown column（同 mall_user_role / mall_role_permission 的教训）。
--
-- 【为什么唯一键是 (user_id, product_id)，不带 deleted】
--   物理删除下不存在"已删除行"，同一个 (user, product) 天然只能有一行，于是可以放心用
--
--       INSERT INTO mall_cart_item (...) VALUES (...) AS new
--       ON DUPLICATE KEY UPDATE quantity = quantity + new.quantity
--
--   一条 SQL 完成「原子累加」。若写成"先 SELECT 再 UPDATE"，两个并发加购请求
--   会都读到 1、都写回 2（正确结果应为 3）—— 典型的丢更新（lost update）。
--
-- 【索引说明】
--   uk_user_product (user_id, product_id) 的左前缀就是 user_id，
--   因此「查某人的购物车」已经走这条索引，不需要再单独建 idx_user。
--
-- 【执行方式】（必须带 --default-character-set=utf8mb4，否则中文注释乱码）
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 < 09_mall_cart.sql
--
-- 【为什么可以安全重跑】
--   CREATE TABLE IF NOT EXISTS。
-- ============================================================

USE mall;

CREATE TABLE IF NOT EXISTS mall_cart_item
(
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL COMMENT '用户ID（登录主体，取令牌上下文，不接受请求参数）',
    product_id  BIGINT   NOT NULL COMMENT '商品ID',
    quantity    INT      NOT NULL DEFAULT 1 COMMENT '数量',
    selected    TINYINT  NOT NULL DEFAULT 1 COMMENT '结算是否勾选：1是 0否',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 支撑 UPSERT 原子累加，同时兼作「按 user_id 查车」的索引（左前缀）
    UNIQUE KEY uk_user_product (user_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='购物车明细（物理删除，不建 deleted 列）';

-- ------------------------------------------------------------
-- 执行后回查：确认列结构与中文注释均正常
-- ------------------------------------------------------------
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall'
  AND TABLE_NAME = 'mall_cart_item';

SELECT ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall'
  AND TABLE_NAME = 'mall_cart_item'
ORDER BY ORDINAL_POSITION;

SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall'
  AND TABLE_NAME = 'mall_cart_item'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
