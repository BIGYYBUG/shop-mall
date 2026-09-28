-- ============================================================
-- 10 订单模块（主表 + 明细表 + 权限码）
--
-- 【本脚本做什么】
--   1. 新建 mall_order（订单主表）与 mall_order_item（订单明细）
--   2. 写入 4 条订单权限码（order:list / detail / ship / close）
--   3. 动态补齐 ADMIN 对新权限码的授权
--   4. 自检：表结构、索引、权限数量
--
-- 【为什么订单是逻辑删除，而购物车/聊天是物理删除】
--   判据沿用项目已定的三条：
--     ① 是否审计实体 —— 订单是「交易凭证」，必须可追溯       → 逻辑删
--     ② 产品语义是否「真删」—— 用户从不说「删掉我的订单」，
--        他只会想「从我的列表里藏起来」，那是另一个语义       → 逻辑删
--     ③ 增长是否最快 —— 订单量与购物车同级但远低于聊天        → 不是否决项
--
--   ⚠️ 主表逻辑删，明细表【必须同样逻辑删】。
--      绝不允许「一张逻辑删、一张物理删」—— 主表恢复后明细成了孤儿，
--      订单会显示成「有订单没内容」。项目已有此铁律（见 09 脚本说明）。
--
-- 【为什么明细要存商品名称/图片/单价快照】
--   商品会改价、改名、换图、下架、被逻辑删除。若明细只存 product_id、
--   展示时 join 商品表，那么「昨天 199 元买的订单，今天显示 299 元」，
--   对账与售后全线崩塌。订单一旦生成就与商品解耦，product_id 只是弱关联。
--
-- 【为什么明细不加外键】
--   外键会把「删除商品」变成「不能删，除非先删明细」，与逻辑删除语义冲突，
--   且微服务拆分后跨库外键根本不成立。参照 mall_chat_* 的做法：不加外键，
--   一致性由应用层事务保证。
--
-- 【索引设计：三个查询形态，三条索引】
--   买家「全部订单」标签页（最常用）：
--     WHERE user_id=? AND deleted=0 ORDER BY id DESC          → idx_user_all
--   买家按状态筛选标签页：
--     WHERE user_id=? AND status=? AND deleted=0 ORDER BY id DESC → idx_user_status
--   超时关单任务：
--     WHERE status=0 AND deleted=0 AND close_deadline<=NOW()  → idx_status_deadline
--   单号精确查 + 防重：
--     WHERE order_no=? AND deleted=0                          → uk_order_no
--
--   ⚠️ close_deadline 是一个【为索引而冗余】的列。
--      写成 create_time < NOW() - INTERVAL 30 MINUTE 时，函数作用在列上，
--      索引直接失效、退化成全表扫。改成与常量比较（close_deadline <= NOW()）
--      才能走索引。代价是多存一列，收益是关单语句可走索引 + 支付窗口变成
--      可配置的数据而不是散落在代码里的常量。
--
--   ⚠️ 不建 idx_create_time：自增 id 与创建时间单调同序，
--      按时间排序直接走主键即可，多一条索引只是拖慢写入。
--
-- 【执行方式】（必须带 --default-character-set=utf8mb4，否则中文注释乱码）
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 < 10_mall_order.sql
--
-- 【为什么可以安全重跑】
--   CREATE TABLE IF NOT EXISTS + 权限用 ON DUPLICATE KEY UPDATE
-- ============================================================

USE mall;


-- ============================================================
-- 第 1 步：订单主表
--
-- 【status 为什么用单列状态机，而不是 is_paid + is_shipped 多个布尔】
--   多个布尔会立刻产生非法组合（is_paid=0 却 is_shipped=1），
--   必须在应用层写一堆 if 去守。单列状态机把这些组合从结构上排除掉，
--   再配合「条件更新」(UPDATE ... WHERE status = 期望值) 做 CAS，
--   并发与幂等一并解决。
-- ============================================================

CREATE TABLE IF NOT EXISTS mall_order
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_no         CHAR(32)        NOT NULL                COMMENT '订单号(对外唯一标识:yyyyMMdd+24位随机,替代可枚举的自增id)',
    user_id          BIGINT UNSIGNED NOT NULL                COMMENT '买家用户ID(取令牌上下文,不接受请求参数)',
    total_amount     DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '商品总额=Σ明细subtotal(服务端计算)',
    freight_amount   DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '运费(本轮恒为0,留字段)',
    discount_amount  DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '优惠金额(本轮恒为0,留字段)',
    pay_amount       DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '应付金额=total+freight-discount(服务端计算)',
    item_count       INT             NOT NULL DEFAULT 0      COMMENT '件数合计=Σquantity。明细创建后不可变,故该冗余安全,用于列表页免join',
    status           TINYINT         NOT NULL DEFAULT 0      COMMENT '状态:0待支付 1已支付 2已发货 3已完成 4已取消',
    pay_type         TINYINT         DEFAULT NULL            COMMENT '支付方式:1模拟支付(真实支付待接入)',
    receiver_name    VARCHAR(32)     NOT NULL                COMMENT '收货人姓名(下单时快照)',
    receiver_phone   CHAR(11)        NOT NULL                COMMENT '收货人手机号(下单时快照)',
    receiver_address VARCHAR(255)    NOT NULL                COMMENT '收货地址(下单时快照)',
    remark           VARCHAR(255)    DEFAULT NULL            COMMENT '买家备注',
    close_deadline   DATETIME        NOT NULL                COMMENT '支付截止时间=下单时间+支付窗口,关单任务按此列(常量比较)扫,保证走索引',
    pay_time         DATETIME        DEFAULT NULL            COMMENT '支付时间',
    ship_time        DATETIME        DEFAULT NULL            COMMENT '发货时间',
    finish_time      DATETIME        DEFAULT NULL            COMMENT '完成时间',
    cancel_time      DATETIME        DEFAULT NULL            COMMENT '取消/关闭时间',
    cancel_reason    VARCHAR(128)    DEFAULT NULL            COMMENT '取消原因:买家取消/超时未支付/平台关闭',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          BIGINT          NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)',
    PRIMARY KEY (id),

    -- 对外唯一标识 + 防止订单号碰撞。带 deleted 以配套「deleted = 本行 id」语义
    UNIQUE KEY uk_order_no (order_no, deleted),

    -- 买家「全部订单」：等值 user_id/deleted 之后 id 天然有序，过滤+排序一个索引全吃
    KEY idx_user_all (user_id, deleted, id),

    -- 买家按状态筛选：等值列全部前置，排序列 id 放最后
    KEY idx_user_status (user_id, status, deleted, id),

    -- 超时关单扫描：status 等值 + close_deadline 范围，两列都在索引里
    KEY idx_status_deadline (status, deleted, close_deadline)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='订单主表(逻辑删除,审计凭证)' ;


-- ============================================================
-- 第 2 步：订单明细表
--
-- 【seller_id 为什么现在就加，虽然本轮不做多卖家拆单】
--   一张订单可能包含多个卖家的商品，完整做法是「父订单 + 按卖家拆分的子订单」，
--   那样每个卖家才能独立发货 —— 那是独立的一大块，不在本轮范围。
--   但 seller_id 快照【现在留字段的成本≈0】，将来做拆单时可以直接用，
--   不需要回填历史数据。反之若现在不留，历史订单永远补不回来。
--   原则：先把能确定的字段留出来，再推迟不确定的结构。
--
-- 【本表不加 idx_product_id / idx_seller_id】
--   本轮不存在「按商品查订单」「按卖家查订单」的查询。
--   索引只为真实存在的查询形态服务，预留无查询支撑的索引只是拖慢写入。
--   将来做卖家侧订单时再加 idx_seller_id。
-- ============================================================

CREATE TABLE IF NOT EXISTS mall_order_item
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    order_id          BIGINT UNSIGNED NOT NULL                COMMENT '订单ID(弱关联,不加外键)',
    product_id        BIGINT UNSIGNED NOT NULL                COMMENT '商品ID(弱关联,不加外键)',
    seller_id         BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '卖家用户ID快照:0=平台自营(为将来按卖家拆单预留)',
    product_name      VARCHAR(128)    NOT NULL                COMMENT '商品名称快照(商品改名不影响历史订单)',
    product_cover_key VARCHAR(512)    DEFAULT NULL            COMMENT '封面图objectKey快照(存key不存URL,出参时拼)',
    price             DECIMAL(10, 2)  NOT NULL                COMMENT '成交单价快照(商品改价不影响历史订单)',
    quantity          INT             NOT NULL                COMMENT '购买数量',
    subtotal          DECIMAL(10, 2)  NOT NULL                COMMENT '小计=price*quantity(服务端计算,不接受前端传入)',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           BIGINT          NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删除,非0已删除(值为本行id)',
    PRIMARY KEY (id),

    -- 详情页与 VO 工厂都按 order_id 批量取明细（一次查完，避免 N+1）
    KEY idx_order_id (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='订单明细表(逻辑删除,存商品与价格快照)' ;


-- ============================================================
-- 第 3 步：权限字典新增 4 条订单权限码
--
-- 【sort 用新的 70 分组】约定「十位 = 资源分组」：
--   10 user / 20 role / 30 permission / 40 product / 50 shop / 60 seller / 70 order / 90 基础设施
--
-- 【为什么买家侧一条权限码都没有】
--   /order/** 是「我自己的订单」，只要登录即可，不需要权限码。
--   RBAC 只能回答「能不能调这个接口」，回答不了「能操作哪一笔订单」——
--   后者靠 userId 取自令牌 + 归属校验。这与 /cart/** 完全同理，
--   USER 角色名下继续是 0 条权限，这是有意的设计，不是漏配。
-- ============================================================

INSERT INTO mall_permission (code, name, type, sort) VALUES
    ('order:list',   '查询订单列表', 3, 70),
    ('order:detail', '查看订单详情', 3, 71),
    ('order:ship',   '订单发货',     3, 72),
    ('order:close',  '关闭订单',     3, 73)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    type = VALUES(type),
    sort = VALUES(sort),
    status = 1;


-- ============================================================
-- 第 4 步：ADMIN 补授新权限码
--   用动态关联而非硬编码 id —— 06 脚本第 8 步那次已经执行过，
--   新增的权限码不会被它自动补上，所以每个新脚本都要自己再来一次。
-- ============================================================

INSERT IGNORE INTO mall_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM mall_role r
         CROSS JOIN mall_permission p
WHERE r.code = 'ADMIN'
  AND r.deleted = 0
  AND p.deleted = 0
  AND p.status = 1;


-- ============================================================
-- 第 5 步：自检
--   预期：
--     order_tables    = 2
--     permission_count= 36   (原 32 + 订单 4)
--     admin_perm      = 36
--     seller_perm     = 8    (seller:* 7 条 + file:upload，不受影响)
--     user_perm       = 0    (买家侧刻意不挂权限码)
--     bad_deleted     = 0
-- ============================================================

SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall'
  AND TABLE_NAME IN ('mall_order', 'mall_order_item')
ORDER BY TABLE_NAME;

SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall'
  AND TABLE_NAME IN ('mall_order', 'mall_order_item')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT (SELECT COUNT(*) FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME IN ('mall_order', 'mall_order_item')) AS order_tables,
       (SELECT COUNT(*) FROM mall_permission WHERE deleted = 0)                            AS permission_count,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id
          JOIN mall_permission p ON p.id = rp.permission_id
        WHERE r.code = 'ADMIN' AND p.deleted = 0)                                          AS admin_perm,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id WHERE r.code = 'SELLER')                   AS seller_perm,
       (SELECT COUNT(*) FROM mall_role_permission rp
          JOIN mall_role r ON r.id = rp.role_id WHERE r.code = 'USER')                     AS user_perm;

SELECT 'mall_order.deleted 异常'      AS warning, COUNT(*) AS rows_cnt
FROM mall_order      WHERE deleted <> 0 AND deleted <> id
UNION ALL
SELECT 'mall_order_item.deleted 异常', COUNT(*) FROM mall_order_item WHERE deleted <> 0 AND deleted <> id;
