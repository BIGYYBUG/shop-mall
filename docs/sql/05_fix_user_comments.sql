-- ============================================================
-- 05 修复 mall_user 的中文注释乱码（可选脚本）
--
-- 【背景】01_mall_user.sql 当初是用 mysql CLI 导入的，当时没带
--   --default-character-set=utf8mb4，客户端按 latin1/GBK 解释 UTF-8 字节，
--   导致「用户表」被存成「鐢ㄦ埛琛」这类看似乱码、实为编码错位的结果。
--
-- 【影响范围】只有表注释和列注释。表结构、字段类型、索引、数据全部正常，
--   通过 JDBC 写入的中文也正常（JDBC 连接串里带了 characterEncoding=utf-8）。
--   所以这不是"必须修"，是"看着难受"。
--
-- 【为什么可以安全重跑】ALTER TABLE ... COMMENT 是幂等的：
--   重复执行只是把注释再写一遍，不改变任何数据结构与数据。
--
-- 【执行方式】
--   mysql -u root -p --default-character-set=utf8mb4 < 05_fix_user_comments.sql
-- ============================================================

USE mall;

ALTER TABLE mall_user COMMENT = '用户表';

ALTER TABLE mall_user
    MODIFY COLUMN id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    MODIFY COLUMN username    VARCHAR(64)     NOT NULL                COMMENT '登录名',
    MODIFY COLUMN password    VARCHAR(128)    NOT NULL                COMMENT '密码哈希(BCrypt，不存明文)',
    MODIFY COLUMN phone       CHAR(11)        DEFAULT NULL            COMMENT '手机号',
    MODIFY COLUMN email       VARCHAR(128)    DEFAULT NULL            COMMENT '邮箱',
    MODIFY COLUMN nickname    VARCHAR(64)     DEFAULT NULL            COMMENT '昵称',
    MODIFY COLUMN avatar      VARCHAR(256)    DEFAULT NULL            COMMENT '头像URL',
    MODIFY COLUMN status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0禁用 1正常',
    MODIFY COLUMN create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删 1已删',
    MODIFY COLUMN openid      VARCHAR(64)     DEFAULT NULL            COMMENT '微信openid(用户+应用维度)',
    MODIFY COLUMN unionid     VARCHAR(64)     DEFAULT NULL            COMMENT '微信unionid(用户+开放平台维度)';

-- 校验：执行后下面这条应该返回可读中文
--   SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
--   WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'mall_user';
