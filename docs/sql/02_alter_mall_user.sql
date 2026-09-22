-- ============================================================
-- 02 用户表扩展：支持微信登录 + 角色权限 + 逻辑删除
-- 执行前建议先备份：mysqldump -u root -p mall mall_user > mall_user_backup.sql
-- ============================================================

USE mall;

-- ① 新增第三方登录字段（微信）
-- openid  ：用户在同一应用内的唯一标识（网站与公众号的 openid 不同）
-- unionid ：用户在同一开放平台账号下的唯一标识（用于多端打通识别同一人）
ALTER TABLE mall_user
    ADD COLUMN openid  VARCHAR(64) DEFAULT NULL COMMENT '微信openid'  AFTER avatar,
    ADD COLUMN unionid VARCHAR(64) DEFAULT NULL COMMENT '微信unionid' AFTER openid;

-- ② 角色字段：用于区分管理员与普通用户
ALTER TABLE mall_user
    ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '角色:0普通 1管理员' AFTER status;

-- ③ 逻辑删除标记：0 未删除，1 已删除
-- MyBatis-Plus 的 @TableLogic 会自动在查询里追加 deleted = 0 条件
ALTER TABLE mall_user
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删' AFTER role;

-- ④ 关键改造：唯一索引必须加入 deleted
-- 原因：逻辑删除后 username 仍在表中，原来的 uk_username(username)
--       会导致「已删除的用户名无法再被注册」
ALTER TABLE mall_user DROP INDEX uk_username;
ALTER TABLE mall_user ADD UNIQUE KEY uk_username (username, deleted);

-- ⑤ 微信 openid 唯一索引：防止同一微信号重复绑定到多个本地账号
-- 说明：MySQL 唯一索引允许多个 NULL 值，因此未绑定微信的用户不受影响
ALTER TABLE mall_user ADD UNIQUE KEY uk_openid (openid);

-- ⑥ 初始化一个管理员账号（把已注册的用户提升为管理员）
-- 用法：先通过 /user/register 注册一个账号，再把用户名替换到下面执行
-- UPDATE mall_user SET role = 1 WHERE username = '改成你的用户名';
