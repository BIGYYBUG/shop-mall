# 订单模块设计稿（第一阶段）

> 状态：**待批准**。批准后按「代码落点」清单一次性落地。
> 配套脚本：`docs/sql/10_mall_order.sql`（可直接执行，索引与注释已按项目风格写死设计取向）。

---

## 0. 结论先行

订单模块是单体阶段**最后一块核心缺口**。本轮要做的：

| 做 | 不做（本轮明确排除） |
|---|---|
| 2 张表：`mall_order` + `mall_order_item` | ❌ 多卖家拆单（父订单/子订单） |
| 下单（购物车结算 + 直接购买两条入口） | ❌ 真实支付（只有模拟支付） |
| 库存扣减 + 取消/超时回补 | ❌ 退款 / 退货流程 |
| 状态机 5 态 + 条件更新幂等闸门 | ❌ 状态流水表（用 4 个时间戳字段替代） |
| 超时未支付自动关单（`@Scheduled`） | ❌ 地址簿表（收货信息由请求直传） |
| 买家侧 6 接口 + 平台侧 3 接口 | ❌ 卖家侧订单管理（依赖拆单，见 D10） |
| 新增 4 条权限码（32 → 36） | ❌ 优惠券 / 运费模板 |

---

## 1. 核心设计决策

### D1｜订单**不走 Redis**，DB 是唯一真源

这是与购物车**刻意相反**的选型，对照着记：

| | 购物车 | 订单 |
|---|---|---|
| 写频率 | 极高（每次加购/改数量） | 低（一次下单一条） |
| 丢了会怎样 | 用户重加一遍，可接受 | **交易凭证丢失，不可接受** |
| 能否重建 | 能从商品表 + 用户意图重建 | 不可重建 |
| 是否审计实体 | 否 | **是** |
| 结论 | Redis 主 + 异步落库 | **DB 唯一真源，不进 Redis** |

> 选型依据不是"哪个快"，而是**"数据丢了能不能找回来"**。这四条判据同时解释了购物车为什么物理删、订单为什么逻辑删。

### D2｜库存扣减：**下单即扣**，用条件 UPDATE 实现，不上 Redis 预扣

```sql
UPDATE mall_product
SET stock = stock - #{quantity}
WHERE id = #{productId} AND status = 1 AND deleted = 0 AND stock >= #{quantity}
```
**affected rows = 0 即库存不足**，直接抛业务异常。

- **为什么把判断条件写进 `WHERE`，而不是先 `SELECT` 再判断**：后者是超卖的经典成因。两个并发事务都读到 `stock = 1`、都判定"够"、都执行 `stock = stock - 1` → 卖出 2 件。写进 `WHERE` 之后，InnoDB 对 `id` 主键做**当前读**并加行锁，第二个事务阻塞到第一个提交，重新读取后 `stock >= 1` 不成立 → 扣减失败。**一条 SQL 同时完成"加锁 + 校验 + 更新"，这是最高效的乐观并发控制**。
- **为什么不用 Redis 预扣减**：引入 Redis 与 DB 的双写一致性问题，必须有补偿/对账/MQ 才能兜住。单体阶段是纯粹的复杂度支出，收益为零。留到阶段四（MQ）再评估。
- **为什么用条件 UPDATE 而不是 `@Version` 乐观锁字段**：`mall_product` 是商品域的表，为一个库存扣减给它加版本号，会把乐观锁语义污染到所有商品更新路径（改个标题也要带版本号）。条件 UPDATE 把并发控制**局部化**在这一条语句里。

### D3｜明细表必须存**价格与名称快照**

```sql
mall_order_item: product_id / product_name / product_cover_key / price / quantity / subtotal
```
**为什么冗余商品信息**：商品会改价、改名、换图、下架、被逻辑删除。如果订单明细只存 `product_id`、展示时 join 商品表，那么**昨天 199 元买的订单，今天会显示成 299 元**，对账和售后全部崩塌。

> 订单一旦生成，就与商品**解耦**。`product_id` 只是弱关联（不加外键），用于跳转和售后定位。

`subtotal = price × quantity` 与 `total_amount` **一律服务端计算**，前端传的任何金额字段都被忽略——否则等于把定价权交给客户端（1 分钱下单）。

### D4｜状态机：5 态，用**条件更新**当幂等闸门

```
0 待支付 ──支付──> 1 已支付 ──发货──> 2 已发货 ──确认收货──> 3 已完成
   │
   └──取消 / 超时关单──> 4 已取消（终态）
```

| 迁移 | 触发者 | 允许的前置状态 |
|---|---|---|
| 0 → 1 | 买家（模拟支付） | 0 |
| 0 → 4 | 买家取消 / 系统超时 / 平台强制关闭 | 0 |
| 1 → 2 | 平台发货 | 1 |
| 2 → 3 | 买家确认收货 | 2 |

**实现方式**（关键）：
```sql
UPDATE mall_order SET status = #{to}, ship_time = NOW()
WHERE id = #{id} AND status = #{from} AND deleted = 0
```
**affected rows = 1 才算成功**。这一条解决的三个问题：
1. **并发**：买家点"取消"的同时系统超时关单 → 只有一个能成功，不会出现"取消两次、回补两次库存"。
2. **幂等**：重复请求第二次 affected = 0，返回"订单状态已变更，请刷新"，而不是静默成功。
3. **非法状态**：已发货的订单点取消 → `status = 0` 条件不成立 → 天然被拒。**不需要写一堆 if-else 判断状态**。

> 这就是 CAS（compare-and-swap）在数据库里的样子。**"先查状态再判断再更新"是错的**，中间有窗口。

### D5｜不建状态流水表，用 4 个时间戳字段替代

`pay_time` / `ship_time` / `finish_time` / `cancel_time` + `cancel_reason`。

- 阶段一需要的历史信息（何时付的、何时发的、谁取消的）**这 5 个字段全拿到了**，零额外成本。
- 完整流水（谁操作的、备注、操作人 IP）是运营审计需求，当前不存在真实运营。
- ⚠️ 但要注意：一旦将来加流水表，**它是审计实体，必须跟主表一样逻辑删**——不能主表逻辑删、流水物理删，否则主表恢复后流水成了孤儿（项目已有先例：购物车/聊天表的取舍是"整体物理删"，绝不允许父子一张逻辑一张物理）。

### D6｜`close_deadline` 冗余列：为了让关单任务的索引可用

关单任务要扫"超时未支付"的订单。两种写法：

```sql
-- ❌ 函数作用在列上，索引失效，全表扫
WHERE status = 0 AND create_time < NOW() - INTERVAL 30 MINUTE
-- ✅ 常量比较，走 idx_status_deadline
WHERE status = 0 AND close_deadline <= NOW()
```
`close_deadline` = 下单时间 + 30 分钟（下单时写死）。**多存一列，换来一条能走索引的关单语句**，并让"支付窗口"变成可配置、可对不同订单设不同值的数据，而不是散落在代码里的常量。

### D7｜订单号对外，自增 id 对内

接口路径一律用 `orderNo`（如 `GET /order/{orderNo}`），不用自增 id。

- 自增 id 可枚举：`/order/1001`、`/order/1002` 能扫出全站订单规模，也能被拿来试探越权（虽然有归属校验兜底，但**不该把可枚举主键暴露给外部**）。
- `order_no` 生成规则：`yyyyMMdd`（8 位，便于人工排查）+ `UUID 去横线后前 24 位`（96 bit 随机）→ **共 32 位**。同一天内碰撞概率可忽略，DB 的 `uk_order_no` 做最终兜底。
- 与已有的 `mall_chat_conversation.conversation_no CHAR(36)` 同源思路，只是去掉横线省 4 字节。

### D8｜`sales`（销量）只在**支付成功**时累加

- 下单：只 `stock -`，**不动 sales**。
- 支付成功：`sales +`。
- 取消/超时：回补 `stock`，**不动 sales**。

**为什么不是下单就加**：销量语义上应当**单调递增**（它代表"卖出去多少"）。如果下单就加、取消就减，那 sales 就不是销量而是"当前有效订单件数"，语义混乱且会出现回退。而"支付成功"这个点之后不存在回退路径（本轮无退款），语义干净。

### D9｜禁止重复提交：Redis 用户级下单锁 + `uk_order_no` 兜底

```java
// SET mall:order:lock:{userId} 1 NX EX 5  —— Redis 3.0.504 支持 SET NX EX
Boolean locked = redis.opsForValue().setIfAbsent("mall:order:lock:" + userId, "1", 5, TimeUnit.SECONDS);
if (!Boolean.TRUE.equals(locked)) throw new BusinessException("请勿重复提交");
```
- **只锁用户维度、只锁 5 秒**：下单是用户自己的动作，不同用户之间无竞争（库存竞争已由 D2 的行锁处理），所以锁必须尽可能细、尽可能短。
- 真正的幂等（同一次请求重试返回同一订单）需要客户端传 `requestId` + 幂等表，**本轮不做**，记为 TODO。`uk_order_no` 是最后一道兜底。
- ⚠️ Redis 挂了不能导致不能下单：`setIfAbsent` 抛异常时应放行（降级为"无锁"），因为**锁是防重复提交的优化，不是正确性依赖**。

### D10｜本轮**不做多卖家拆单**（但预留字段）

一张订单可能包含多个卖家的商品。完整的做法是"父订单 + 按卖家拆分的子订单"，这样才能让每个卖家独立发货。这需要：父子订单层级、按卖家分组金额、多包裹/多物流单号、按卖家分账。**这是独立的一大块，不是订单模块的附属功能。**

本轮处理方式：
1. 订单行 `mall_order_item` **预留 `seller_id` 快照**（0 = 平台自营），发货权限暂时只给平台运营；
2. 卖家侧订单接口**本轮不做**——因为无拆单时卖家无权改整单状态，做了也是错的；
3. 将来做拆单时，`seller_id` 已在明细上，**不需要回填历史数据**，只需加父订单维度。

> 取舍原则：**先把能确定的字段留出来，再推迟不确定的结构**。留字段的成本≈0，改结构的成本极高。

### D11｜权限：新增 4 条，买家侧**一个权限码都不加**

| 权限码 | 用途 | sort |
|---|---|---|
| `order:list` | 平台查询订单列表 | 70 |
| `order:detail` | 平台查看订单详情 | 71 |
| `order:ship` | 平台订单发货 | 72 |
| `order:close` | 平台强制关闭订单 | 73 |

- **买家侧 `/order/**` 只要登录、无权限码**：与 `/cart/**` 完全同理——"只能操作自己那笔订单"不是权限码能表达的，它靠 `userId` 取自令牌 + 归属校验。USER 角色名下继续是 0 条权限，这是**有意的**。
- `sort` 用新的 **70** 分组（现有 10/20/30/40/50/60/90），保持"十位 = 资源分组"的约定。
- ADMIN 的授权用**动态关联**补齐（`INNER JOIN` 全量权限），不硬编码 id——这样将来继续加码不用改脚本。
- 归属不符**返 404 而非 403**：不暴露"这单存在但不属于你"。

### D12｜收货信息由请求直传（本轮），地址簿表留待后续

没有地址簿表，`receiver_name` / `receiver_phone` / `receiver_address` 三个字段直接存在订单上、由请求传入并校验。

- **为什么这样不算错**：订单**必须**存收货信息快照（用户改了地址簿，历史订单不能跟着变），所以这三个字段无论如何都要落在订单表上。地址簿只是"录入体验"的优化。
- 后续加 `mall_user_address` 表 + `addressId`，下单时把地址簿内容**复制**进订单快照即可，订单表结构不用动。

---

## 2. 表结构（详见 `docs/sql/10_mall_order.sql`）

### `mall_order`（主表，逻辑删）

| 关键字段 | 说明 |
|---|---|
| `order_no CHAR(32)` | 对外单号，`uk_order_no(order_no, deleted)` |
| `user_id` | 买家，取令牌 |
| `total_amount` / `pay_amount` / `freight_amount` / `discount_amount` | 全部 `DECIMAL(10,2)`，服务端算 |
| `status TINYINT` | 0待支付 1已支付 2已发货 3已完成 4已取消 |
| `receiver_name` / `receiver_phone` / `receiver_address` | 收货信息快照 |
| `close_deadline DATETIME` | 支付截止时间，支撑关单索引 |
| `pay_time` / `ship_time` / `finish_time` / `cancel_time` / `cancel_reason` | 替代流水表 |
| `deleted BIGINT` | `deleted = 本行 id` 语义 |

### `mall_order_item`（明细表，逻辑删）

`order_id` / `product_id` / `seller_id` / `product_name` / `product_cover_key` / `price` / `quantity` / `subtotal` / `deleted`

### 索引设计（三个查询形态，三条索引）

| 索引 | 支撑的查询 | 为什么这么排 |
|---|---|---|
| `idx_user_all (user_id, deleted, id)` | 买家"全部订单"标签页（**最常用**） | `user_id` 等值 + `id` 有序 → 过滤和排序一个索引全吃 |
| `idx_user_status (user_id, status, deleted, id)` | 买家按状态筛选标签页 | 等值列全在前，`id` 排最后 |
| `idx_status_deadline (status, deleted, close_deadline)` | 关单任务扫描 | 常量比较走索引，不写函数 |
| `uk_order_no (order_no, deleted)` | 单号精确查 + 防重复单号 | 兼作唯一约束 |
| `idx_order_id (order_id)`（明细表） | 批量取明细，避免 N+1 | — |

> ⚠️ **手写 XML 必须自己写 `deleted = 0`**：MyBatis-Plus 的 `@TableLogic` 只会自动改写它自己生成的 SQL，手写 XML 里的每条 join 都要补上，漏写 = 越权。

---

## 3. 接口契约

### 买家侧 `/order/**`（仅登录，无权限码，归属由令牌钉死）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/order/checkout` | 购物车已勾选项结算下单 |
| POST | `/order/buy-now` | 直接购买（不经过购物车） |
| GET | `/order/list?status=&page=&size=` | 我的订单（分页） |
| GET | `/order/{orderNo}` | 订单详情 |
| POST | `/order/{orderNo}/pay` | 模拟支付 |
| POST | `/order/{orderNo}/cancel` | 取消（仅待支付） |
| POST | `/order/{orderNo}/confirm` | 确认收货（仅已发货） |

### 平台侧 `/admin/order/**`

| 方法 | 路径 | 权限码 |
|---|---|---|
| GET | `/admin/order/list?status=&orderNo=&userId=&page=&size=` | `order:list` |
| GET | `/admin/order/{orderNo}` | `order:detail` |
| POST | `/admin/order/{orderNo}/ship` | `order:ship` |
| POST | `/admin/order/{orderNo}/close` | `order:close` |

**写操作全部返回完整 `OrderVO`**（沿用购物车的做法：前端一轮拿到最新状态，不用再发 GET）。

---

## 4. 代码落点

```
mall-api
  dto/OrderCheckoutDTO.java      购物车结算入参（收货信息 + 备注）
  dto/OrderBuyNowDTO.java        直接购买入参（productId + quantity + 收货信息）
  dto/OrderQueryDTO.java         列表查询条件
  vo/OrderVO.java                重写（当前是占位）
  vo/OrderItemVO.java            新增
  OrderApi.java                  重写为契约层（去横线单号）

mall-service
  entity/OrderEntity.java             extends BaseEntity
  entity/OrderItemEntity.java         extends BaseEntity
  mapper/OrderMapper.java + OrderItemMapper.java
  resources/mapper/OrderMapper.xml + OrderItemMapper.xml
  convert/order/OrderVoFactory.java   一次查全部明细，避免 N+1
  service/order/OrderStatus.java      枚举 + 合法流转定义
  service/order/OrderService.java     接口（重写）
  service/order/OrderServiceImpl.java 编排（重写）
  service/order/OrderPersistenceService.java  独立 Bean 承载 @Transactional
  service/order/OrderTimeoutTask.java @Scheduled 关单

mall-web
  controller/OrderController.java        /order/**
  controller/AdminOrderController.java   /admin/order/**
  src/test/java/com/mall/OrderIntegrationTest.java
```

> `OrderPersistenceService` 独立成 Bean 的理由与 `CartPersistenceService` 相同：**`@Transactional` 靠代理生效，同类的自调用不走代理**，事务会静默失效。
> 关单任务同样必须"一单一事务"——整批一个事务会产生长事务持锁，且一单失败拖垮全批。

---

## 5. 测试用例清单（`OrderIntegrationTest`，预计 14 例）

| # | 用例 | 断言要点 |
|---|---|---|
| 1 | 购物车结算成功 | 订单+明细落库、明细是**快照**（改名改价后仍显示旧值）、库存减少、购物车对应项被清空 |
| 2 | 直接购买成功 | 不经过购物车 |
| 3 | 库存不足 | 无订单、库存不变、购物车不变 |
| 4 | **并发下单不超卖** | 10 线程抢库存 3 → 成功 3 单、库存 0 |
| 5 | 未勾选任何商品结算 | 业务错误 |
| 6 | 支付成功 | 0→1、`pay_time` 非空、`sales` 增加 |
| 7 | 重复支付 | 第二次被拒 |
| 8 | 已支付订单取消 | 被拒 |
| 9 | 取消待支付订单 | 库存回补、`cancel_time` 非空 |
| 10 | **重复取消** | 第二次被拒且**库存不再回补**（幂等） |
| 11 | 超时关单 | 改 `close_deadline` 为过去 → 跑任务 → 状态 4 + 库存回补；**再跑一次库存不变** |
| 12 | IDOR | A 用户用 B 的 orderNo 查/取消 → **404** |
| 13 | 金额不可伪造 | 请求体塞 `totalAmount = 0.01` → 订单金额仍为服务端计算值 |
| 14 | 平台接口鉴权 | 无 `order:list` → 403；有 → 200 |

回归后总数：**80 → 约 94**。

---

## 6. 需要你确认的 4 个决策

| # | 决策 | 我的建议 | 若选另一个的代价 |
|---|---|---|---|
| 1 | 库存何时扣减 | **下单即扣**（超时回补） | 改为支付后扣 → 会超卖，需要额外的预占机制 |
| 2 | 是否现在做多卖家拆单 | **不做**，明细预留 `seller_id` | 现在做 → 工期翻倍，且卖家功能仍不完整 |
| 3 | 支付超时窗口 | **30 分钟**（写入 `close_deadline`） | 改值只需改配置，无结构影响 |
| 4 | 是否给买家"删除订单" | **不给**（订单是审计凭证） | 给了就要做"隐藏"语义，否则丢失交易记录 |

**默认按上表建议执行**——你只回复"开始"即可；有不同意见指出编号即可。

---

## 7. 本轮已知不做 / 风险

1. **退款退货**：`status` 预留 5 = 已退款但本轮不实现。
2. **真实支付**：`pay_type` 只有 1 = 模拟支付，接入真实支付需回调验签 + 幂等 + 对账。
3. **多实例下的关单任务**：单体阶段够用；多实例会重复扫描，靠 D4 的 CAS 保证不重复回补，但会浪费扫描。留阶段四。
4. **Redis 降级**：下单锁失效时放行，属于有意的可用性优先（D9）。
5. **`sales` 与真实销量的一致性**：本轮无退款，支付即销量；有退款后需要引入"净销量"口径。
6. **订单与购物车的清理时机**：下单成功**先提交 DB 事务，再删 Redis 中对应的购物车项**。Redis 不可回滚，若删失败只造成购物车残留（用户可手动再删），不影响订单正确性——**宁可残留，不可错乱**。
