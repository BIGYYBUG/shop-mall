-- ============================================================
-- 07 卖家体系：店铺档案 + 商品归属
--
-- 【为什么需要单独一张 mall_shop，而不是给 mall_user 加个 type 字段】
--   一个账号可以同时是买家、卖家、平台运营 —— 身份是可叠加的，
--   而枚举只能选一个。更关键的是「卖家身份」自带生命周期
--   （待审核/正常/驳回/冻结）和一批只有卖家才有的数据
--   （店铺名、Logo、联系方式），那属于业务档案，不属于账号本身。
--
--   三层职责因此拆开：
--     账号  mall_user            你是谁（登录主体）
--     身份  mall_shop + 角色      你扮演什么
--     权限  mall_role/permission  你能干什么
--
-- 【权限码已在 06 脚本写入，本脚本只管业务结构】
--   seller:*（7 条）绑定在 SELLER 角色上，范围由码本身限定为「自己名下」。
--
-- 【可安全重跑】
--
-- 【执行方式】
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
--         < 07_mall_shop.sql
-- ============================================================

USE mall;


-- ============================================================
-- 第 1 步：店铺表
--
-- 【uk_user_id (user_id, deleted) 为什么带 deleted】
--   一个账号只能有「一个未删除的店铺」。带上 deleted 之后，
--   被逻辑删除的历史店铺不会挡住重新入驻 —— 与 06 脚本的
--   deleted = 本行 id 语义配套使用。
--
-- 【status 为什么是状态机而不是布尔上架标志】
--   0 待审核：入驻申请已提交，等待平台处理
--   1 正常：可正常经营
--   2 驳回：附 reject_reason，店主可修改后重新提交
--   3 冻结：违规被平台关停，可恢复
--   用一个 is_seller 布尔值表达不了这四种含义。
-- ============================================================

CREATE TABLE IF NOT EXISTS mall_shop (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '店铺ID',
    user_id       BIGINT UNSIGNED NOT NULL                COMMENT '店主用户ID（一个账号最多一个有效店铺）',
    name          VARCHAR(64)     NOT NULL                COMMENT '店铺名称',
    logo_key      VARCHAR(512)    DEFAULT NULL            COMMENT '店铺Logo objectKey（存 key 不存 URL，拼 URL 是出参时的事）',
    description   VARCHAR(512)    DEFAULT NULL            COMMENT '店铺简介',
    contact_phone CHAR(11)        DEFAULT NULL            COMMENT '联系电话',
    status        TINYINT         NOT NULL DEFAULT 0      COMMENT '状态:0待审核 1正常 2已驳回 3已冻结',
    reject_reason VARCHAR(255)    DEFAULT NULL            COMMENT '驳回原因（仅 status=2 时有意义）',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       BIGINT          NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id, deleted),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT ='店铺表(卖家身份档案)';


-- ============================================================
-- 第 2 步：mall_product 增加 seller_id
--
-- 【为什么是 seller_id（用户ID）而不是 shop_id（店铺ID）】
--   本项目 shop 与 user 是 1:1，两者信息等价，但 seller_id 有两个实际好处：
--     ① 权限校验零额外查询 —— 令牌里就有 userId，直接
--        WHERE seller_id = ? 即可，不必先查「我的店铺是哪一家」
--     ② 与现有 JWT/UserContext 的认证链路同源
--   将来若真出现「一人多店」，再加 shop_id 列并从 seller_id 回填即可，
--   迁移成本很低。当前选简单的那条路。
--
-- 【0 = 平台自营，绝不用 NULL】
--   「平台自营」是一个确定的归属，不是「未知」。
--   若存 NULL，所有卖家侧查询都要写成
--   (seller_id = ? ) —— 而 NULL 与任何值比较都是 NULL，永远不命中，
--   还得额外补 OR seller_id IS NULL，既容易漏写也让索引失效。
--   用 0 占位，语义清晰且索引可正常使用。
--
-- 【索引为什么是 (seller_id, status, sort)】
--   卖家侧固定查询形态：WHERE seller_id = 我 AND status IN (...) ORDER BY sort DESC
--   等值列（seller_id、status）在前、排序列（sort）在后，
--   一个索引就能同时吃掉过滤与排序（Extra 里不会出现 filesort）。
--   前台仍走既有的 idx_status_sort (status, sort)，不受影响。
-- ============================================================

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_product' AND COLUMN_NAME = 'seller_id');
SET @ddl := IF(@has = 0,
               'ALTER TABLE mall_product ADD COLUMN seller_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''卖家用户ID:0=平台自营'' AFTER category_id',
               'SELECT ''mall_product.seller_id 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_product'
               AND INDEX_NAME = 'idx_seller_status_sort');
SET @ddl := IF(@has = 0,
               'ALTER TABLE mall_product ADD KEY idx_seller_status_sort (seller_id, status, sort)',
               'SELECT ''idx_seller_status_sort 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史商品的归属一律置为平台自营（列默认值 0 已覆盖，这里显式写一次以免旧数据意外为 NULL）
UPDATE mall_product SET seller_id = 0 WHERE seller_id IS NULL;


-- ============================================================
-- 第 3 步：给 marin 备一份可手测的数据（可删）
--
--   造这一份是为了让你能立刻用现有账号验证「一个账号同时持有
--   ADMIN 与 SELLER 两种身份」—— 这正是卖家体系设计讨论的结论：
--   身份可叠加，不能压缩成一个 user_type 字段。
--
--   不需要的话，执行下面两行即可清掉：
--     DELETE ur FROM mall_user_role ur JOIN mall_role r ON r.id = ur.role_id
--       JOIN mall_user u ON u.id = ur.user_id WHERE u.username='marin' AND r.code='SELLER';
--     DELETE FROM mall_shop WHERE user_id = (SELECT id FROM mall_user WHERE username='marin');
-- ============================================================

INSERT INTO mall_shop (user_id, name, description, contact_phone, status)
SELECT u.id, 'Marin 的测试小店', '用于本地联调的示例店铺，可随时删除', '13800000000', 1
FROM mall_user u
WHERE u.username = 'marin'
  AND u.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM mall_shop s WHERE s.user_id = u.id AND s.deleted = 0);

INSERT IGNORE INTO mall_user_role (user_id, role_id)
SELECT u.id, r.id
FROM mall_user u
         CROSS JOIN mall_role r
WHERE u.username = 'marin'
  AND u.deleted = 0
  AND r.code = 'SELLER'
  AND r.deleted = 0;


-- ============================================================
-- 第 4 步：自检
--   预期：
--     shop_count     = 1
--     platform_prod  = 5     （现有 5 个商品全部归平台自营）
--     seller_prod    = 0
--     marin_roles    = ADMIN,SELLER
-- ============================================================

SELECT (SELECT COUNT(*) FROM mall_shop WHERE deleted = 0)                        AS shop_count,
       (SELECT COUNT(*) FROM mall_product WHERE seller_id = 0 AND deleted = 0)   AS platform_prod,
       (SELECT COUNT(*) FROM mall_product WHERE seller_id > 0 AND deleted = 0)   AS seller_prod;

SELECT u.username,
       GROUP_CONCAT(r.code ORDER BY r.sort) AS roles
FROM mall_user u
         JOIN mall_user_role ur ON ur.user_id = u.id
         JOIN mall_role r       ON r.id = ur.role_id
WHERE u.deleted = 0
GROUP BY u.id, u.username;
