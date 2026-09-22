-- ============================================================
-- 商城用户表
-- 注意：字段名与 mall-common 的 BaseEntity 对齐
--   BaseEntity.createTime -> create_time
--   BaseEntity.updateTime -> update_time
-- ============================================================

USE mall;

CREATE TABLE mall_user (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)     NOT NULL                COMMENT '登录名',
    password    VARCHAR(128)    NOT NULL                COMMENT '密码哈希(BCrypt，不存明文)',
    phone       CHAR(11)        DEFAULT NULL            COMMENT '手机号',
    email       VARCHAR(128)    DEFAULT NULL            COMMENT '邮箱',
    nickname    VARCHAR(64)     DEFAULT NULL            COMMENT '昵称',
    avatar      VARCHAR(256)    DEFAULT NULL            COMMENT '头像URL',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0禁用 1正常',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';
