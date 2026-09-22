-- ============================================================
-- 03 RBAC 权限模型：用户 - 角色 - 权限
-- 执行前备份：mysqldump -u root -p mall > mall_backup.sql
-- ============================================================

USE mall;

-- ① 移除旧的单角色字段
-- 原因：RBAC 下用户角色由 mall_user_role 关联表决定，且支持一人多角色，
--       保留 role 字段会形成两套真相来源，必然出现不一致。
--
-- 技巧：MySQL 不支持 ALTER TABLE ... DROP COLUMN IF EXISTS，
--       用 information_schema 判断后再动态执行，让脚本可以重复运行。
SET @col_exists := (SELECT COUNT(*)
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mall_user'
                      AND COLUMN_NAME = 'role');
SET @ddl := IF(@col_exists > 0,
               'ALTER TABLE mall_user DROP COLUMN role',
               'SELECT ''role 字段已不存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② 角色表
CREATE TABLE mall_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    code        VARCHAR(64)     NOT NULL                COMMENT '角色标识，如 ADMIN',
    name        VARCHAR(64)     NOT NULL                COMMENT '角色名称',
    description VARCHAR(255)    DEFAULT NULL            COMMENT '描述',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0禁用 1正常',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='角色表';

-- ③ 权限表
-- type 字段保留：后期要做菜单/按钮级权限时是硬需求，届时改表成本高于预留字段。
-- parent_id / sort 暂不加：只有做菜单树才需要，遵 YAGNI 原则，需要时再扩。
CREATE TABLE mall_permission (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    code        VARCHAR(128)    NOT NULL                COMMENT '权限标识，如 user:delete',
    name        VARCHAR(64)     NOT NULL                COMMENT '权限名称',
    type        TINYINT         NOT NULL DEFAULT 3      COMMENT '类型:1菜单 2按钮 3接口',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0禁用 1正常',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='权限表';

-- ④ 用户-角色关联表
-- 关联表不加 deleted：取消授权是物理删除这条关系，加逻辑删除会让"重新授权"撞唯一索引
CREATE TABLE mall_user_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
    role_id     BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户角色关联表';

-- ⑤ 角色-权限关联表
CREATE TABLE mall_role_permission (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id       BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
    permission_id BIGINT UNSIGNED NOT NULL                COMMENT '权限ID',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_permission_id (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='角色权限关联表';

-- ⑥ 种子数据：角色
INSERT INTO mall_role (code, name, description)
VALUES ('ADMIN', '超级管理员', '拥有系统全部权限'),
       ('USER', '普通用户', '仅基础权限');

-- ⑦ 种子数据：权限（接口级）
INSERT INTO mall_permission (code, name, type)
VALUES ('user:list', '查询用户列表', 3),
       ('user:detail', '查看用户详情', 3),
       ('user:update', '修改用户信息', 3),
       ('user:status', '启用禁用用户', 3),
       ('user:password', '重置用户密码', 3),
       ('user:delete', '删除用户', 3),
       ('role:list', '查询角色列表', 3),
       ('role:assign', '分配用户角色', 3);

-- ⑧ ADMIN 拥有全部权限（用 SELECT 动态关联，避免硬编码 ID）
INSERT INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         CROSS JOIN mall_permission p
WHERE r.code = 'ADMIN';

-- ⑨ USER 只有查看详情权限
INSERT INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         JOIN mall_permission p ON p.code = 'user:detail'
WHERE r.code = 'USER';

-- ⑩ 给已有账号 marin 赋予 ADMIN 角色
INSERT INTO mall_user_role (user_id, role_id)
SELECT u.id, r.id
FROM mall_user u
         CROSS JOIN mall_role r
WHERE u.username = 'marin'
  AND r.code = 'ADMIN';
