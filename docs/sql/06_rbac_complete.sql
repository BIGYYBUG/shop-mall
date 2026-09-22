-- ============================================================
-- 06 RBAC 管理闭环 + 表结构加固
--
-- 【本脚本解决什么】
--   1. 逻辑删除唯一键陷阱：deleted 由「0/1」改为「删除时写入本行 id」
--   2. mall_user 中文注释乱码修复（吸收原 05 脚本，05 可不再单独执行）
--   3. mall_role 增加 built_in（内置角色保护）与 sort
--   4. mall_permission 增加 sort
--   5. 补齐「角色/权限自身管理」所需的权限码
--   6. 新增 SELLER 角色骨架（权限码由 07 脚本绑定）
--   7. 修正 USER 角色的越权授权（原绑定的 user:detail 是管理端能力）
--
-- 【为什么可以安全重跑】
--   所有 DDL 都先用 information_schema 判断；所有种子数据用
--   ON DUPLICATE KEY UPDATE，重复执行只会把值再写一遍。
--
-- 【执行方式】（必须带 --default-character-set=utf8mb4，否则注释又会乱码）
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
--         < 06_rbac_complete.sql
--
-- 【执行前请备份】
--   mysqldump -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 mall > mall_backup.sql
-- ============================================================

USE mall;


-- ============================================================
-- 第 1 步：逻辑删除语义改造（最重要的一步，先做）
--
-- 【要解决的坑】
--   原设计 deleted = 0/1，唯一键是 uk_username (username, deleted)。
--   于是「同一个用户名」在唯一索引里只能存在两行：(abc, 0) 和 (abc, 1)。
--   一旦发生「注销 → 重新注册 → 再注销」，第二次注销要把 (abc, 1)
--   变成 (abc, 1) —— 与第一个已删除的行完全撞车，直接报
--   Duplicate entry 'abc-1' for key 'uk_username'。
--
-- 【改法】deleted 未删除时为 0，删除时写「本行自己的 id」。
--   因为 id 是自增主键，必然互不相同，所以：
--     - 未删除：唯一索引里是 (abc, 0)，全局只能有一行     → 唯一性成立
--     - 已删除：是 (abc, 5)、(abc, 9) ... 天然不重复      → 可无限次注销重注
--
-- 【为什么列类型要同时改成 BIGINT】
--   原类型 TINYINT 上限 127，而 mall_user 的 id 已经到 103。
--   id 一旦超过 127，写进 TINYINT 会直接报 Out of range。
--
-- 【千万不要把 deleted 改成 NULL 表示未删除】
--   MySQL 唯一索引不约束 NULL —— 允许多行 (abc, NULL) 同时存在，
--   唯一约束会当场失效，比原来的坑更严重。
-- ============================================================

-- 1.1 把历史「已删除」行从 1 迁移为它自己的 id
--     必须先迁移再改类型：此语句对已经迁移过的行是幂等的
--     （id=1 的行 deleted 本来就是 1，再写一次仍是 1）
UPDATE mall_user       SET deleted = id WHERE deleted = 1;
UPDATE mall_role       SET deleted = id WHERE deleted = 1;
UPDATE mall_permission SET deleted = id WHERE deleted = 1;
UPDATE mall_product    SET deleted = id WHERE deleted = 1;

-- 1.2 放宽列类型并统一注释
ALTER TABLE mall_user
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)';
ALTER TABLE mall_role
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)';
ALTER TABLE mall_permission
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)';
ALTER TABLE mall_product
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)';


-- 第 2 步：修复 mall_user 中文注释乱码
--   成因：01_mall_user.sql 当初导入时漏了 --default-character-set=utf8mb4，
--         客户端按 latin1 解释 UTF-8 字节，「用户表」被存成「鐢ㄦ埛琛」。
--   影响：只有注释。表结构、索引、数据全部正常，属于「看着难受」级别。
--   本步同时也把 deleted 的注释统一过来。

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
    MODIFY COLUMN deleted     BIGINT          NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)',
    MODIFY COLUMN openid      VARCHAR(64)     DEFAULT NULL            COMMENT '微信openid(用户+应用维度)',
    MODIFY COLUMN unionid     VARCHAR(64)     DEFAULT NULL            COMMENT '微信unionid(用户+开放平台维度)';


-- 第 3 步：uk_openid 补上 deleted
--   原 uk_openid (openid) 不含 deleted，同样的问题：
--   用户注销后 openid 仍被占用，同一微信号再也注册不进来。

SET @old_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_user'
                  AND INDEX_NAME = 'uk_openid' AND COLUMN_NAME = 'openid');
SET @new_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_user'
                  AND INDEX_NAME = 'uk_openid' AND COLUMN_NAME = 'deleted');
SET @ddl := IF(@old_uk > 0 AND @new_uk = 0,
               'ALTER TABLE mall_user DROP INDEX uk_openid, ADD UNIQUE KEY uk_openid (openid, deleted)',
               'SELECT ''uk_openid 已符合预期，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- 第 4 步：mall_role 增加 built_in 与 sort
--   built_in：内置角色禁止删除。没有这个标记，管理员在界面上把 ADMIN 角色
--             一删，role_permission 关系随之清空，系统将再没人能进管理后台 ——
--             这类「把自己锁在门外」的事故只能靠数据库层兜底。
--   sort：管理端下拉框需要一个稳定顺序，不能依赖自增 id 碰巧的先后。

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_role' AND COLUMN_NAME = 'built_in');
SET @ddl := IF(@has = 0,
               'ALTER TABLE mall_role ADD COLUMN built_in TINYINT NOT NULL DEFAULT 0 COMMENT ''是否内置:0自定义 1内置(禁止删除/禁止改code)'' AFTER description',
               'SELECT ''built_in 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_role' AND COLUMN_NAME = 'sort');
SET @ddl := IF(@has = 0,
               'ALTER TABLE mall_role ADD COLUMN sort INT NOT NULL DEFAULT 0 COMMENT ''排序值，越小越靠前'' AFTER built_in',
               'SELECT ''mall_role.sort 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- 第 5 步：mall_permission 增加 sort
--   注意这里只加 sort，不加 parent_id：本项目的权限粒度定在接口级，
--   不做菜单树。parent_id 只有真要渲染前端菜单才有意义，属于 YAGNI。

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_permission' AND COLUMN_NAME = 'sort');
SET @ddl := IF(@has = 0,
               'ALTER TABLE mall_permission ADD COLUMN sort INT NOT NULL DEFAULT 0 COMMENT ''排序值，按资源分组，越小越靠前'' AFTER type',
               'SELECT ''mall_permission.sort 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ============================================================
-- 第 6 步：权限字典全量写入
--
-- 【命名约定】资源:动作，全小写。
-- 【分组约定】sort 的十位即资源分组：
--   10 user / 20 role / 30 permission / 40 product / 50 shop
--   60 seller(卖家自有资源) / 90 基础设施
--
-- 【seller: 前缀为什么要单独一套码，而不是复用 product:*】
--   RBAC 只回答「能不能调这个接口」，回答不了「能操作谁的数据」。
--   如果卖家也持有 product:update，他就能调管理端接口改别人的商品。
--   把范围写进权限码本身（product:update = 全平台，seller:product:update
--   = 仅自己名下），切面逻辑一行都不用改就能区分这两档。
-- ============================================================

INSERT INTO mall_permission (code, name, type, sort) VALUES
    -- 用户管理
    ('user:list',              '查询用户列表',       3, 10),
    ('user:detail',            '查看用户详情',       3, 11),
    ('user:update',            '修改用户信息',       3, 13),
    ('user:status',            '启用禁用用户',       3, 14),
    ('user:password',          '重置用户密码',       3, 15),
    ('user:delete',            '删除用户',           3, 16),
    -- 角色管理
    ('role:list',              '查询角色列表',       3, 20),
    ('role:detail',            '查看角色详情',       3, 21),
    ('role:create',            '新增角色',           3, 22),
    ('role:update',            '修改角色',           3, 23),
    ('role:delete',            '删除角色',           3, 24),
    ('role:assign',            '分配用户角色',       3, 25),
    ('role:permission',        '给角色分配权限',     3, 26),
    -- 权限字典
    ('permission:list',        '查询权限字典',       3, 30),
    -- 商品管理（平台级，全量数据）
    ('product:list',           '查询商品列表',       3, 40),
    ('product:detail',         '查看商品详情',       3, 41),
    ('product:create',         '新增商品',           3, 42),
    ('product:update',         '修改商品',           3, 43),
    ('product:status',         '商品上下架',         3, 44),
    ('product:delete',         '删除商品',           3, 45),
    -- 店铺管理（平台级）
    ('shop:list',              '查询店铺列表',       3, 50),
    ('shop:detail',            '查看店铺详情',       3, 51),
    ('shop:update',            '修改店铺信息',       3, 52),
    ('shop:audit',             '审核店铺入驻',       3, 53),
    -- 卖家自有资源（范围被权限码限定为「自己名下」）
    ('seller:shop',            '维护自己的店铺',     3, 60),
    ('seller:product:list',    '查询自己的商品',     3, 61),
    ('seller:product:detail',  '查看自己的商品',     3, 62),
    ('seller:product:create',  '新增自己的商品',     3, 63),
    ('seller:product:update',  '修改自己的商品',     3, 64),
    ('seller:product:status',  '自己商品上下架',     3, 65),
    ('seller:product:delete',  '删除自己的商品',     3, 66),
    -- 基础设施
    ('file:upload',            '上传文件到对象存储', 3, 90)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    type = VALUES(type),
    sort = VALUES(sort),
    status = 1;


-- 第 7 步：角色字典
--   USER 仍然保留：新注册用户需要绑定一个「登录后即有」的基础角色。
--   它名下不挂任何权限码是刻意为之 —— 浏览商品走 /product/** 免登录，
--   查看自己走 /user/info 免权限，两者都不需要权限码。

INSERT INTO mall_role (code, name, description, built_in, sort) VALUES
    ('ADMIN',  '超级管理员', '拥有系统全部权限',                 1, 10),
    ('SELLER', '卖家',       '仅能维护自己店铺与自己名下的商品', 1, 20),
    ('USER',   '普通用户',   '注册即得的基础角色，不含任何管理端权限', 1, 30)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    built_in = VALUES(built_in),
    sort = VALUES(sort);


-- 第 8 步：ADMIN ↔ 全部权限（动态关联，不硬编码 id）
--   多出来的新权限码会在这一步自动被补上，无需改脚本。
INSERT IGNORE INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         CROSS JOIN mall_permission p
WHERE r.code = 'ADMIN'
  AND r.deleted = 0
  AND p.deleted = 0
  AND p.status = 1;


-- 第 9 步：SELLER ↔ 卖家权限码 + 文件上传
--   卖家要传商品图，所以必须带上 file:upload。
INSERT IGNORE INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         CROSS JOIN mall_permission p
WHERE r.code = 'SELLER'
  AND r.deleted = 0
  AND p.deleted = 0
  AND p.status = 1
  AND (p.code LIKE 'seller:%' OR p.code = 'file:upload');


-- 第 10 步：纠正 USER 角色的越权授权
--   03 脚本给 USER 绑了 user:detail，但 user:detail 对应的接口是
--   GET /admin/user/{id} —— 管理端接口，能查任意用户。普通买家持有它
--   意味着任何登录用户都能遍历 id 拿到全站用户的手机号/邮箱。
--   「查看自己」的正确入口是 GET /user/info，它只认令牌里的 userId，
--   不需要任何权限码。
DELETE rp FROM mall_role_permission rp
    JOIN mall_role r       ON r.id = rp.role_id
    JOIN mall_permission p ON p.id = rp.permission_id
WHERE r.code = 'USER'
  AND p.code = 'user:detail';


-- ============================================================
-- 第 11 步：自检
--   预期：
--     permission_count = 32
--     role_count       = 3
--     admin_perm       = 32
--     seller_perm      = 8   (seller:* 共 7 条 + file:upload)
--     user_perm        = 0
--     bad_deleted      = 0   (不允许存在 deleted 既非 0 又不等于 id 的行)
-- ============================================================

SELECT (SELECT COUNT(*) FROM mall_permission WHERE deleted = 0)                        AS permission_count,
       (SELECT COUNT(*) FROM mall_role       WHERE deleted = 0)                        AS role_count,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id WHERE r.code = 'ADMIN')                AS admin_perm,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id WHERE r.code = 'SELLER')               AS seller_perm,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id WHERE r.code = 'USER')                 AS user_perm;

SELECT 'mall_user.deleted 存在异常值'  AS warning, COUNT(*) AS rows_cnt
FROM mall_user       WHERE deleted <> 0 AND deleted <> id
UNION ALL
SELECT 'mall_role.deleted 存在异常值', COUNT(*) FROM mall_role       WHERE deleted <> 0 AND deleted <> id
UNION ALL
SELECT 'mall_permission.deleted 异常', COUNT(*) FROM mall_permission WHERE deleted <> 0 AND deleted <> id
UNION ALL
SELECT 'mall_product.deleted 异常',    COUNT(*) FROM mall_product    WHERE deleted <> 0 AND deleted <> id;

-- 中文注释是否恢复可读（应显示「用户表」「登录名」等）
SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'mall_user';
