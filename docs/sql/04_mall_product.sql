-- ============================================================
-- 04 商品表（单表直球，不拆 SPU / SKU）
--
-- 【导入方式】必须带字符集参数，否则中文注释会变乱码：
--   mysql -u root -p --default-character-set=utf8mb4 < 04_mall_product.sql
--   （01_mall_user.sql 就是漏了这个参数，导致 mall_user 的注释全是乱码）
--
-- 【为什么单表】当前阶段商品没有多规格（颜色/尺码）需求，
--   上 SPU/SKU 双层会立刻带来「商品表 + 规格表 + 规格值表 + SKU 表」四张表
--   和一半的查询都要 join 的成本。等真实出现多规格需求再拆，属于 YAGNI。
-- ============================================================

USE mall;

CREATE TABLE mall_product (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name           VARCHAR(128)    NOT NULL                COMMENT '商品名称',
    subtitle       VARCHAR(255)    DEFAULT NULL            COMMENT '副标题/卖点',
    category_id    BIGINT UNSIGNED DEFAULT NULL            COMMENT '分类ID（分类表暂未建，先留字段）',
    price          DECIMAL(10, 2)  NOT NULL                COMMENT '售价',
    original_price DECIMAL(10, 2)  DEFAULT NULL            COMMENT '原价/划线价',
    stock          INT             NOT NULL DEFAULT 0      COMMENT '库存数量',
    sales          INT             NOT NULL DEFAULT 0      COMMENT '累计销量',

    -- 图片：只存 OSS 对象键（objectKey），不存完整 URL，更不存二进制。
    -- 存二进制(BLOB)：MySQL 体积爆炸、备份变慢、无法走 CDN，是明确的反模式。
    -- 存完整 URL：换 bucket / 换 CDN 域名时要全表 UPDATE，且测试环境与生产环境 URL 不同。
    -- 存 objectKey：口径唯一，URL 由 FileStorageService 在出参时拼装。
    cover_key      VARCHAR(512)    DEFAULT NULL            COMMENT '封面图 objectKey，并非完整URL',
    images         JSON            DEFAULT NULL            COMMENT '商品图集 objectKey 数组，如 ["product/a.jpg","product/b.jpg"]',

    description    TEXT            DEFAULT NULL            COMMENT '商品详情（富文本/Markdown）',
    status         TINYINT         NOT NULL DEFAULT 0      COMMENT '状态:0下架 1上架',
    sort           INT             NOT NULL DEFAULT 0      COMMENT '排序值，越大越靠前',
    create_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (id),

    -- 前台商品列表的固定查询形态是 WHERE status = 1 AND deleted = 0 ORDER BY sort DESC, id DESC。
    -- status 等值匹配在前、sort 排序在后，才能让索引同时吃掉「过滤」和「排序」两件事，
    -- 避免 filesort。索引列顺序写反（sort, status）就没这个效果。
    KEY idx_status_sort (status, sort),
    KEY idx_category (category_id),
    KEY idx_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='商品表(单表直球，不拆SPU/SKU)';


-- ============================================================
-- 权限点：商品管理
-- 只给管理端接口授权。前台商品浏览是公开接口，不需要权限 —— 这是有意的设计，
-- 「能看商品」是电商的默认能力，把它做成权限点会让每个未登录用户都被拦。
-- ============================================================
INSERT INTO mall_permission (code, name, type)
VALUES ('product:list', '查询商品列表', 3),
       ('product:detail', '查看商品详情', 3),
       ('product:create', '新增商品', 3),
       ('product:update', '修改商品', 3),
       ('product:status', '商品上下架', 3),
       ('product:delete', '删除商品', 3),

       -- 文件上传单独授权：对象存储是花钱的有限资源，任何登录用户都能上传
       -- 等于把存储账单交给所有人。所以上传必须有独立权限点，不能混进 product:create，
       -- 否则将来「只让运营改文案不给传图」这种需求就无法表达了。
       ('file:upload', '上传文件到对象存储', 3);


-- 授权：ADMIN 拿全部新增权限。用 SELECT 动态关联，脚本可重复执行而不会硬编码 ID。
INSERT IGNORE INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         CROSS JOIN mall_permission p
WHERE r.code = 'ADMIN'
  AND (p.code LIKE 'product:%' OR p.code = 'file:upload');


-- ============================================================
-- 种子商品：给前端一点真实数据可渲染
-- ============================================================
INSERT INTO mall_product (name, subtitle, price, original_price, stock, sales, description, status, sort)
VALUES ('AirPods Pro 2', '主动降噪，通透模式', 1899.00, 1999.00, 200, 358, '第二代主动降噪耳机，支持自适应通透模式。', 1, 100),
       ('小米 14 Ultra', '徕卡光学四摄', 6499.00, 6999.00, 120, 96, '徕卡光学镜头，可变光圈主摄。', 1, 90),
       ('机械键盘 Keychron K8', '87 键 / 热插拔 / 双模', 599.00, 699.00, 80, 142, '支持热插拔轴体，蓝牙 + 有线双模。', 1, 80),
       ('戴森 V12 吸尘器', '轻量无绳，激光探测', 3799.00, 4099.00, 40, 27, '激光探测微尘，整机过滤。', 1, 70),
       ('索尼 A7M4 微单', '全画幅 3300 万像素', 16999.00, 17999.00, 15, 8, '3300 万有效像素全画幅传感器。', 0, 60);
