# mall-server · 商城后端

> 一个以**后端深度**为目标的电商后端：单体多模块起步，把 RBAC、卖家体系、商品域、文件存储、AI 对话全部打通，并在结构上预留 **Spring Cloud Alibaba** 微服务升级路径。

**当前进度**：阶段一（单体落地）主体完成 —— 认证授权、卖家体系、商品域、文件存储、AI 对话已可用；购物车与订单为接口占位，尚未实现。

前端项目为独立仓库：[`BIGYYBUG/shop-mall-frontedn`](https://github.com/BIGYYBUG/shop-mall-frontedn)（Vue 3 + Vite，含独立 README）。

---

## 一、技术栈

| 组件 | 版本 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | — |
| Spring Boot | 3.3.5 | `spring-boot-starter-parent` |
| Spring Cloud | 2023.0.3 | **已声明未启用**，仅父 POM 预留 BOM |
| Spring Cloud Alibaba | 2023.0.1.2 | 计划阶段二引入 |
| MyBatis-Plus | 3.5.7 | `mybatis-plus-spring-boot3-starter` |
| MySQL | 9.7.2（本机） | 驱动 `mysql-connector-j` |
| Redis | 3.0.504（本机） | 权限缓存 |
| JJWT | 0.12.6 | 令牌签发与校验 |
| 阿里云 OSS SDK | 3.18.4 | 对象存储（V1） |
| Maven | 3.9.16 | 多模块聚合构建 |

> ⚠️ Spring Cloud 2023.x **不兼容 Spring Boot 4.x**，升级时两者必须成对调整。
> ⚠️ JDK 9+ 使用 OSS SDK **必须显式补 JAXB 三件套**（`jaxb-api` + `activation` + `jaxb-runtime`），否则编译无碍、首次调用时抛 `NoClassDefFoundError`。

---

## 二、模块结构

**按技术职责分层的单体（Layered Monolith）**，依赖方向单向、不允许回环：

```
mall-web  ──►  mall-service  ──►  mall-api  ──►  mall-common
（启动层）      （业务实现）        （契约层）      （基础设施）
```

| 模块 | 职责 | 关键内容 |
| :--- | :--- | :--- |
| `mall-common` | 基础设施 | 统一返回 `Result`、全局异常、`BaseEntity`、`@RequiresPermission` 注解、`PermissionChecker` 契约接口 |
| `mall-api` | 服务契约 | DTO / VO、Feign 风格接口（无 `@FeignClient`，为拆分微服务预留） |
| `mall-service` | 业务实现 | `user` / `rbac` / `shop` / `product` / `ai` / `cart`；存储能力统一在 `com.mall.storage`（`local` / `oss` / `memory` / `redis`），Mapper XML 在 `resources/mapper/` |
| `mall-web` | Web 启动层 | 唯一可启动模块：启动类、Controller、拦截器、配置 |

**为什么接口要放 `mall-common`**：Maven 依赖只能 `service → common`。`PermissionChecker` 的实现方在 service 层，而调用它的是 common 层的切面——接口若定义在 service，common 就引用不到，会直接形成循环依赖。这是依赖倒置的落地方式。

---

## 三、已实现功能

| 域 | 能力 |
| :--- | :--- |
| **认证** | 账号密码登录 / 注册 / 当前用户，JWT 签发（`POST /user/login` → `token` + `roles[]`） |
| **第三方登录** | 微信开放平台 OAuth2 授权码模式（`/auth/{source}/**`），`openid`/`unionid` 直落 `mall_user` 主表 |
| **RBAC** | 5 张表 + 角色/权限管理闭环，`@RequiresPermission` + AOP 接口级鉴权，权限集缓存 Redis |
| **用户管理** | 分页 / 详情 / 改资料 / 改状态 / 重置密码 / 启禁用 / 分配角色 |
| **卖家体系** | 入驻申请 → 审核（通过 / 驳回 / 冻结 / 解冻），`mall_shop` 一人一店，与 `SELLER` 角色强一致 |
| **商品域** | 单表 `mall_product`；平台侧全量管理 + 卖家侧仅管自己（数据归属隔离）；前台免登录浏览 |
| **文件存储** | `FileStorageService` 抽象，`local` / `oss` 按配置切换（`@ConditionalOnProperty`） |
| **AI 对话** | `POST /ai/chat`，OpenAI 兼容协议（DeepSeek），多轮上下文服务端拼接 + 窗口裁剪 |
| **购物车** | `/cart/**`，**Redis 为唯一真源 + 定时异步落库 MySQL**；实时算价、失效标记、勾选/全选与合计 |
| **订单** | ⬜ `OrderService` 仅有接口占位 |

### 三层身份模型

买卖**不是** `user_type` 枚举，也**不只是**权限，而是三层叠加：

```
账号 mall_user       → 你是谁（登录主体）
身份 mall_shop + 角色 → 你扮演什么（可叠加、有生命周期）
权限 mall_role/perm  → 你能干什么
```

- RBAC 只回答「能不能调这个接口」，**不回答「能操作哪几行」**——后者靠 `mall_product.seller_id` 做数据归属。
- **核心不变量**：`SELLER 角色 ⟺ shop.status == 1`。审核通过授予角色；驳回/冻结回收角色，冻结时同时下架名下全部在售商品；解冻不自动恢复上架。
- **权限码分两套**：`product:update`（平台，全平台） vs `seller:product:update`（卖家，仅自己）——把数据范围写进权限码，切面零改动即可区分两档。

### 几条不能翻案的设计铁律

1. **逻辑删除 = `deleted` 写入本行 id**，不用 `0/1`。列为 `BIGINT`，唯一键形如 `uk_username (username, deleted)`。`0/1` 方案下同一用户名在唯一索引里只能存在两行（`(abc,0)`、`(abc,1)`），「注销 → 重新注册 → 再注销」第三次必然 `Duplicate entry`。
2. **绝不能用 NULL 表示未删除**——唯一索引不约束 NULL，约束当场失效。
3. **手写 XML 不自动追加逻辑删除条件**，每条 join 都要自己写 `deleted = 0 AND status = 1`，漏写即越权。
4. **缓存穿透防护**：无权限时写哨兵值 `__EMPTY__`（Redis 存不住空集合）。
5. **授权变更必须刷缓存，分两个维度**：改用户角色 → `refreshPermissions(userId)`；改**角色**权限 → `refreshByRoleId(roleId)`。漏掉后者，接口仍返 200，只有真调用才发现权限没生效。
6. **认证与授权分离**：`JwtInterceptor` 解析令牌写 `UserContext`；`PermissionAspect` 判能不能干。
7. **`sellerId` 一律从登录上下文取，绝不接受请求参数**（做成参数即 IDOR）；归属不符返 **404 而非 403**（403 等于承认 id 存在）。
8. **权限码字典只读**：权限码与代码强耦合，只能随 SQL 脚本发布，界面不提供增删改；角色是运维数据，可自由增删改。有测试用反射扫描全部 `@RequiresPermission`，断言每个权限码都在字典内。
9. **内置角色保护**：`mall_role.built_in = 1` 的 ADMIN / SELLER / USER 禁止删除、停用、改 code，防止「删掉 ADMIN 后无人能进后台」的不可自救事故。

### 购物车：为什么用 Redis 做真源

购物车是**高频写、低频读、可容忍极小概率丢失**的典型：改一次数量就写一次库，用 MySQL 直接扛会产生大量无意义的行更新。

```
写请求 ──► Redis Hash（真源，毫秒返回）
             │  同时 SADD 进 mall:cart:dirty 脏集合
             ▼
        CartFlushTask（每 30s）批量刷进 mall_cart_item
```

几个必须守住的点：

1. **「Redis 里没有」不等于「车是空的」**。Redis 会过期（30 天 TTL）、会重启、会被清库。查不到时必须回源 MySQL 重建，否则用户看到空车而数据其实完好——最容易被误判成「数据丢了」的假故障。刷库任务同理：Redis 里查不到该车时**直接跳过，绝不拿空车覆盖 MySQL**。
2. **累加用 UPSERT，不加锁**：`INSERT ... ON DUPLICATE KEY UPDATE quantity = mall_cart_item.quantity + new.quantity`（`AS new` 行别名需 MySQL 8.0.19+，替代已废弃的 `VALUES()`）。必须写全限定表名，否则 `new.quantity` 与目标列重名会报 `Column 'quantity' in field list is ambiguous`。
3. **刷库要防「旧快照覆盖新数据」**：Hash 里存一个 `__ver__` 版本号，刷库前后各读一次；不一致就保留脏标记，下一轮再刷。
4. **合计只在服务端算**，且**只统计「已勾选 且 可购买」**的条目。前端拿 `price` 自己乘会引入浮点误差；把失效商品算进金额，用户结算时金额会突变。
5. **失效商品打标记，不自动删除**（下架 / 零库存 / 商品被硬删）。静默删除会让用户觉得东西「莫名消失」，反而制造工单。
6. **`userId` 一律取自令牌**，DTO 里根本没有这个字段——契约里不存在，比「服务端记得忽略它」更可靠。
7. 该表**物理删除，不建 `deleted` 列**，实体因此**不继承 `BaseEntity`**（`@TableLogic` 会自动追加 `deleted = 0`，直接 `Unknown column` 报错）。

---

## 四、数据库

脚本位于 `docs/sql/`，**按序号执行**：

| 脚本 | 内容 |
| :--- | :--- |
| `01_mall_user.sql` | 用户主表 |
| `02_alter_mall_user.sql` | 用户表字段调整 |
| `03_rbac.sql` | RBAC 基础 4 表 |
| `04_mall_product.sql` | 商品表 |
| `05_fix_user_comments.sql` | 注释乱码修复（已被 06 吸收，可跳过） |
| `06_rbac_complete.sql` | RBAC 管理闭环 + 逻辑删除语义改造 + `built_in`/`sort` |
| `07_mall_shop.sql` | 卖家店铺表 + SELLER 权限码绑定 |
| `08_mall_chat.sql` | AI 对话会话 / 消息表 |
| `09_mall_cart.sql` | 购物车明细表（物理删除，无 `deleted` 列） |

共 10 张表：`mall_user`、`mall_role`、`mall_permission`、`mall_user_role`、`mall_role_permission`、`mall_product`、`mall_shop`、`mall_chat_conversation`、`mall_chat_message`、`mall_cart_item`。

> 两张关联表**刻意不加 `deleted`**：取消授权语义是物理删除；若加逻辑删除，重新授权会撞 `uk_user_role`。

---

## 五、接口总览

统一返回体 `Result{code, message, data}`；**业务失败仍返回 HTTP 200**，靠 `code` 区分。鉴权分三档：

| 前缀 | 要求 |
| :--- | :--- |
| `/user/**`、`/auth/**` | 免登录 / 仅登录 |
| `/product/**`、`/uploads/**` | **免登录**（前台浏览 + 静态图片，在放行清单中） |
| `/cart/**` | **仅登录**，且**没有一个 `@RequiresPermission`**——购物车是「我自己的东西」，靠 `userId` 取自令牌保证归属，不靠权限码 |
| `/seller/**` | 卖家权限 `seller:*`（申请入驻、查看自己店铺仅需登录） |
| `/admin/**` | `user:* role:* permission:* product:* shop:*` |
| `/file/upload` | `file:upload`（对象存储是花钱资源，独立授权） |
| `/ai/chat` | **必须登录**（按 token 计费，刻意不放行） |

```
POST   /user/login                    登录（返回 token + roles）
POST   /user/register                 注册
GET    /user/info                     当前登录用户
GET    /auth/sources                  可用第三方登录源
GET    /auth/{source}/authorize-url   取授权地址
POST   /auth/{source}/login           第三方登录换令牌

GET    /product/page | /product/{id}  前台商品（免登录）

GET    /cart                          购物车（含实时价格 / 失效标记 / 合计）
GET    /cart/count                    角标数量（种类数，轻接口）
POST   /cart/items                    加入购物车（同商品累加）
PUT    /cart/items/{productId}        设为指定数量
PUT    /cart/items/selected           批量勾选（productIds 为空 = 整车全选 / 全不选）
DELETE /cart/items/{productId}        移除单个
DELETE /cart/items                    清空

GET    /admin/user/page | /{id}       用户管理
PUT    /admin/user/{id}/roles         分配角色（全量覆盖）
PUT    /admin/user/{id}/status        启用/禁用
PUT    /admin/user/{id}/password      重置密码
GET    /admin/role/list | /{id}       角色管理
PUT    /admin/role/{id}/permissions   分配权限（全量覆盖）
GET    /admin/permission/list         权限字典（只读）
GET    /admin/shop/page | /{id}       店铺管理
PUT    /admin/shop/{id}/audit         店铺审核
GET    /admin/product/page | /{id}    商品管理
PUT    /admin/product/{id}/status     上下架

GET    /seller/shop/mine              我的店铺
POST   /seller/shop/apply             提交入驻申请
PUT    /seller/shop/mine              修改店铺
GET    /seller/product/page | /{id}   我的商品
POST   /seller/product                新增商品
PUT    /seller/product/{id}/status    上下架（仅自己）

POST   /file/upload                   图片上传
POST   /ai/chat                       AI 对话（多轮）
```

**放行清单（改 `WebMvcConfig` 时别删）**：`/user/login`、`/user/register`、`/auth/**`、`/product/**`、`/uploads/**`。少放行 `/uploads/**`，前台 `<img>` 会 401 变破图。

---

## 六、快速开始

### 1. 环境

JDK 21、Maven 3.8+、MySQL 8+、Redis

### 2. 初始化数据库

```bash
mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
      -e "CREATE DATABASE mall DEFAULT CHARACTER SET utf8mb4;"

cd docs/sql
for f in 0*.sql; do
  mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 mall < "$f"
done
```

> 必须带 `--default-character-set=utf8mb4`，否则中文注释乱码。

### 3. 配置

编辑 `mall-web/src/main/resources/application.yml`：MySQL / Redis 连接、`mall.storage.type`（`local` / `oss`）、`mall.storage.local.base-dir`（**写绝对路径**）。

**凭证一律走环境变量，不要写进 yml**：

```bash
export DEEPSEEK_API_KEY=sk-xxx      # AI 对话
export OSS_ENDPOINT=...             # OSS 模式才需要
export OSS_BUCKET=...
export OSS_ACCESS_KEY_ID=...
export OSS_ACCESS_KEY_SECRET=...
export JWT_SECRET=...               # 见「已知问题」
```

> 反面教材：`api-key: ${DEEPSEEK_API_KEY:sk-xxxx}` 冒号后的「兜底明文」会被原样提交进 Git。正确写法是结尾留空 `${DEEPSEEK_API_KEY:}`。

### 4. 构建与启动

```bash
mvn clean package -DskipTests
java -jar mall-web/target/mall-web-1.0.0-SNAPSHOT.jar
```

或在 IDE 中运行 `com.mall.MallApplication`（端口 **8080**）。

### 5. 回归测试

```bash
bash docs/mvnw.sh clean test                          # 全量
bash docs/mvnw.sh test -Dtest=RbacIntegrationTest     # RBAC
bash docs/mvnw.sh test -Dtest=ShopIntegrationTest     # 卖家体系
bash docs/mvnw.sh test -Dtest=ProductIntegrationTest  # 商品域
bash docs/mvnw.sh test -Dtest=CartIntegrationTest     # 购物车
bash docs/mvnw.sh test -Dtest=AiChatMemoryTest        # AI 多轮上下文
bash docs/mvnw.sh test -Dtest=LlmClientTest           # AI 纯单元
```

> 只跑单个测试类时，其它模块会因「没有匹配的测试」而失败退出。补一个开关即可：
> `-Dsurefire.failIfNoSpecifiedTests=false`。

> **改用 `docs/mvnw.sh` 而非裸 `mvn`**：某些受限环境中 `mvn` 启动脚本会因 `dirname` 不可用而拼不出 classpath，报 `ClassNotFoundException: plexus...Launcher`。
> **改了方法签名 / 类结构后必须 `clean`**：增量编译会留下按旧签名编译的字节码，编译期不报错、只在运行期炸 `NoSuchMethodError`。

---

## 七、配置速查（`application.yml`）

| 键 | 说明 |
| :--- | :--- |
| `server.port` | 8080 |
| `spring.datasource.*` | MySQL 连接（占位，按本机改） |
| `spring.data.redis.*` | Redis 连接 |
| `spring.servlet.multipart.max-file-size` | 5MB（**必须与 `FileController` 的 `MAX_IMAGE_BYTES` 一致**，否则会出现「应用层说 5MB、Tomcat 层先拒」的错位） |
| `mall.jwt.secret` / `expire-minutes` | 签名密钥（⚠️ 见已知问题）/ 有效期 120 分钟 |
| `mall.ai.enabled` | 总开关，关掉后 `/ai/**` 直接 503，不发起外部调用 |
| `mall.ai.base-url` / `model` / `temperature` | OpenAI 兼容端点 / 模型 / 温度 |
| `mall.storage.type` | `local` / `oss` |
| `mall.storage.local.base-dir` / `url-prefix` | 本地落盘根目录 / 对外 URL 前缀 |
| `mall.storage.oss.*` | endpoint / bucket / AK / SK（均从环境变量注入） |
| `mall.wechat.*` | 微信开放平台 AppID / AppSecret / 回调地址 |
| `mybatis-plus.configuration.log-impl` | `StdOutImpl`（学习/排错期打开，上线换 `NoLoggingImpl`） |

---

## 八、对象存储

- 图片列**只存 objectKey**（`cover_key` / `images` JSON），**绝不存 URL 或 BLOB**；URL 在出参时由 `toAccessUrl()` 拼。
- `local` 模式 `toAccessUrl()` 返回**相对路径** `/uploads/xxx.jpg`，需要前端代理；`oss` 模式返回绝对 URL，直连即可。
- `@TableName` 必须 `autoResultMap = true`，否则 `JacksonTypeHandler` 只在写方向生效，读返 `null` 且**不报错**。
- `OSSClient` 保持单例（`@PostConstruct` 建 / `@PreDestroy` 关），每次 new 会打满 socket。
- 上传当前为「后端中转」，生产建议改**前端直传**（签名后直传 OSS）。
- 详见 `docs/oss-integration-guide.md`。

---

## 九、路线图（五阶段）

| 阶段 | 内容 | 状态 |
| :--- | :--- | :--- |
| 一 | 单体落地：认证授权、卖家体系、商品域、文件存储、AI 对话 | 🟡 主体完成（缺购物车、订单） |
| 二 | 注册与发现：部署 Nacos，拆出 `mall-user` | ⬜ |
| 三 | 网关与远程调用：Gateway + OpenFeign（`mall-api` 已备契约层） | ⬜ |
| 四 | 配置与容错：Nacos Config、Sentinel | ⬜ |
| 五 | 分布式事务：Seata AT；另补登录态治理（令牌吊销 / 黑名单） | ⬜ |

> 升级改动量很小：为 `mall-api` 接口补 `@FeignClient`，并在父 POM 启用 `spring-cloud-dependencies` / `spring-cloud-starter-openfeign` 即可。

---

## 十、仓库与分支

| 项目 | 远程仓库 | 分支 |
| :--- | :--- | :--- |
| 后端（本仓库） | `https://github.com/BIGYYBUG/shop-mall.git` | `main`、`yqf_dev` |
| 前端 | `git@github.com:BIGYYBUG/shop-mall-frontedn.git` | `main`、`yqf_dev` |

> ⚠️ 前端远程地址疑似拼写错误（`frontedn` → `frontend`）。

---

## 十一、已知问题 / 待办

- [ ] **🔴 JWT 签名密钥硬编码**：`application.yml` 中 `mall.jwt.secret` 为明文常量。因仓库公开，任何人可用该密钥伪造令牌冒充 ADMIN。**上线前必须改为 `${JWT_SECRET:}` 环境变量注入**，并换成随机 32+ 字节密钥。
- [ ] **订单未实现**（`OrderService` 仅接口占位）。购物车已可用并返回可结算的合计金额。
- [ ] 购物车缺少**批量删除**接口，前端「删除选中」目前是按顺序逐个调用。购物车规模小（单用户几十行量级）时可接受；上百行后应补 `DELETE /cart/items/batch`。
- [ ] 购物车自动落库存在固有窗口（默认 30s）：应用被 `kill -9` 时，最后一次未刷的改动会丢。要彻底消除需引入 MQ 或同步双写，留待阶段四。
- [ ] 清理空占位文件 `mall-service/.../service/ai/InMemoryChatHistoryStore.java`。
- [ ] 前端三处「静默」缺陷（另一仓库）：登录 `redirect` 未生效、第三方登录按钮未渲染、`/register` 路由缺失。
