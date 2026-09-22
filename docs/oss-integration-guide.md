# 阿里云 OSS 对象存储集成指南

> 面向 shopping-mall 项目。结论来自阿里云官方文档（OSS Java SDK V1 3.18.4 / 服务端签名直传 / STS SDK），
> 结合本项目已落地的代码。

---

## 一、结论先行

1. **图片绝不存数据库**。BLOB 会让 MySQL 体积爆炸、备份变慢、无法走 CDN，是明确的反模式。
   **数据库只存 objectKey（对象键）**，不存完整 URL。
2. **上传接口当前是「后端中转」，生产环境应改为「前端直传」**。直传让文件字节完全不经过应用服务器，
   省带宽、省内存、省成本。当前实现是教学与过渡形态。
3. **凭证只用 RAM 子账号 AK，且只授予目标 Bucket 的权限**。主账号 AK 泄露等于账号被接管。
4. **生产环境用 STS 临时凭证，不用永久 AK**。临时凭证有时效、可限目录、可限操作类型。

---

## 二、OSS 核心概念（四个词必须清楚）

| 概念 | 含义 | 项目中的对应 |
|---|---|---|
| **Bucket** | 存储空间，名字**全局唯一**，创建时绑定地域（Region），地域不可改 | 建议 `mall-images-{env}` |
| **Object** | Bucket 内的文件，用 **objectKey** 唯一标识 | `product/2026/09/19/a1b2....jpg` |
| **Endpoint** | 访问域名。公网 `oss-cn-shenzhen.aliyuncs.com`；内网 `oss-cn-shenzhen-internal.aliyuncs.com` | `mall.storage.oss.endpoint` |
| **Region** | 地域，如 `cn-shenzhen`。**Bucket 与 ECS 必须同地域**，否则走公网、慢且收流量费 | 与部署机器对齐 |

**两个容易踩的点**：

- OSS **没有真正的目录**。`product/2026/09/19/x.jpg` 里的斜杠只是 key 的一部分，
  控制台按斜杠渲染成树而已。所以「移动文件」= 复制 + 删除，不存在目录级原子操作。
- **Endpoint 不要写 bucket 名**。SDK 参数是分开的：`endpoint=oss-cn-shenzhen.aliyuncs.com`，
  `bucket=mall-images`。写错成 `mall-images.oss-cn-shenzhen.aliyuncs.com` 会得到
  莫名的 DNS 解析失败。

---

## 三、三种上传方案对比

| 方案 | 数据路径 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| **① 后端中转**<br>（本项目当前实现） | 浏览器 → 应用 → OSS | 实现简单；服务端能完整校验文件内容；不依赖前端 SDK | 文件字节占用应用带宽与堆内存；大文件易 OOM；应用成为瓶颈与单点 | 小规模、管理后台上传、文件需强校验 |
| **② 服务端签名直传**<br>PostObject + PostPolicy | 浏览器 → OSS | 字节不经过应用；Policy 可限制大小、类型、路径、过期时间；FB 端不依赖 SDK（表单提交） | **不支持分片上传与断点续传**；表单字段顺序与签名必须严格一致 | 普通图片上传（本项目商品图的推荐方案） |
| **③ STS 临时凭证直传** | 浏览器 → OSS（用临时凭证 + OSS SDK） | 支持分片、断点续传、大文件；临时凭证可精细限权、有时效 | 需引 STS SDK，服务端要缓存凭证（**频繁调 STS 会被限流**）；前端需引 OSS SDK | 大文件、视频、批量上传 |

**阿里云官方建议**：大部分上传场景优先 ③；文件属性需要严格限制、走 HTML 表单的场景用 ②。

### 方案 ③ 的服务端调用骨架

```java
// 依赖：com.aliyun:sts20150401:1.1.7 + com.aliyun:credentials-java
AssumeRoleRequest request = new AssumeRoleRequest()
        .setRoleArn("acs:ram::1234567890:role/oss-direct-upload")
        .setRoleSessionName("mall-web-" + userId)
        // 最小权限策略：只允许往 product/ 前缀写
        .setPolicy("""
                {"Version":"1","Statement":[{"Effect":"Allow",
                 "Action":["oss:PutObject"],
                 "Resource":["acs:oss:*:*:mall-images/product/*"]}]}
                """)
        .setDurationSeconds(1800L);   // 30 分钟
```

⚠️ **STS 凭证必须缓存**。服务端每次请求都去调 AssumeRole 会触发限流，
正确做法是缓存到过期前 5 分钟再刷新。

---

## 四、安全清单（每条都对应一个真实的失陷场景）

| 项 | 要求 | 不做的后果 |
|---|---|---|
| **AK 管理** | 用 RAM 子账号 AK；通过环境变量/密钥管理服务注入；**绝不提交进 Git** | 主账号 AK 泄露 = 整个阿里云账号被接管 |
| **最小权限** | 只授予目标 Bucket 的 `oss:PutObject` / `oss:GetObject`，**不要用 `AliyunOSSFullAccess`** | 一个上传接口能删掉全部 Bucket |
| **STS 限时** | `DurationSeconds` 不超过业务所需（图片上传 15–30 分钟足够） | 凭证长期有效，泄露后长期可利用 |
| **STS 限路径** | Policy 里把 `Resource` 限定到具体前缀 | 可往任意目录写任意文件 |
| **CORS** | 直传时必须配置，`来源` 填具体域名，不要长期用 `*` | 直传 100% 失败（浏览器拦），或任意站点可代为上传 |
| **文件校验** | 校验**文件头魔数**，而不是只看扩展名或 Content-Type | 改名 `shell.jpg` 即可上传任意文件，对象存储变免费图床/马场 |
| **Bucket 权限** | 设为**私有**，读取走签名 URL 或 CDN 回源鉴权 | 设为公共读 = 全站图片被任意爬取，流量费失控 |
| **防盗链 Referer** | 在 Bucket 上配置 Referer 白名单 | 图片被第三方站点盗链，流量费由你承担 |
| **传输加密** | 强制 HTTPS，开启服务端加密（SSE） | 明文传输、静态数据裸奔 |

---

## 五、图片处理：不预生成缩略图

OSS 内置图片处理，**原图只存一份，派生尺寸按需实时生成**并在 CDN 边缘缓存。

```text
# 参数式（灵活，适合动态尺寸）
https://img.example.com/product/x.jpg?x-oss-process=image/resize,m_pad,w_200,h_200/quality,q_80

# 样式式（推荐：在控制台定义命名样式，改参数不用改 URL）
https://img.example.com/product/x.jpg?x-oss-process=style/thumb_list
```

建议在 OSS 控制台预定义四个样式（**样式是命名约定，建议写进项目文档**）：

| 样式名 | 处理参数 | 用途 |
|---|---|---|
| `thumb_list` | `resize,m_pad,w_200,h_200/quality,q_80` | 商品列表页缩略图 |
| `thumb_detail` | `resize,m_pad,w_600,h_600/quality,q_90` | 商品详情页主图 |
| `thumb_grid` | `resize,w_300/quality,q_75` | 瀑布流 / 宫格 |
| `origin_hd` | `quality,q_95` | 高清原图查看 |

**为什么不做预生成**：2 万张图 × 5 种尺寸 = 10 万张派生文件，存储成本直接翻 5 倍，
而且每种新尺寸都要跑一次全量任务。实时处理 + CDN 缓存等效于预生成，成本更低。

**格式自适应**：同等画质下 WebP 比 JPG 小 25–35%，AVIF 比 WebP 再小 20–30%。
可用 `format,webp` / `format,avif`，或开启 OSS 的「自适应 WebP」，由 OSS 按
请求头 `Accept` 自动决定返回格式。

---

## 六、本项目已落地的实现

### 分层结构

```
mall-service/
├── storage/
│   ├── FileStorageService.java        接口：upload / delete / toAccessUrl
│   ├── StorageProperties.java         配置映射 mall.storage.*
│   ├── StoredFile.java                record：objectKey + url
│   ├── ObjectKeys.java                对象键生成（category/yyyy/MM/dd/uuid.ext + 扩展名白名单）
│   ├── local/
│   │   ├── LocalFileStorageService.java   本地磁盘实现（默认）
│   │   └── LocalStorageWebConfig.java     /uploads/** 静态资源映射
│   └── oss/
│       └── AliyunOssFileStorageService.java  阿里云实现（type=oss 时生效）
mall-web/
└── controller/FileController.java     POST /file/upload
```

### 两个实现的切换机制

```java
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService { ... }

@ConditionalOnProperty(name = "mall.storage.type", havingValue = "oss")
public class AliyunOssFileStorageService implements FileStorageService { ... }
```

同一时刻容器里只有一个 `FileStorageService` Bean，注入方**不需要写任何 if/else**。
这是「把会变的东西挡在接口后面」的直接应用。

### 从 local 切到 oss 的操作步骤

1. 阿里云控制台创建 Bucket（地域与部署机器一致，权限设为**私有**），配置 CORS 与防盗链。
2. RAM 控制台创建子账号，授予该 Bucket 的读写权限，拿到 AK。
3. 设置环境变量（**不要写进 yml**）：

   ```powershell
   [Environment]::SetEnvironmentVariable("OSS_ENDPOINT", "oss-cn-shenzhen.aliyuncs.com", "User")
   [Environment]::SetEnvironmentVariable("OSS_BUCKET", "mall-images-dev", "User")
   [Environment]::SetEnvironmentVariable("OSS_ACCESS_KEY_ID", "LTAI...", "User")
   [Environment]::SetEnvironmentVariable("OSS_ACCESS_KEY_SECRET", "***", "User")
   ```

4. 改 `application.yml`：`mall.storage.type: oss`
5. 重启应用。启动日志出现 `阿里云 OSS 客户端已初始化：bucket=...` 即成功。

**注意**：切换后，库里已有的 `local` 时代的 objectKey 仍然指向本地文件。
开发阶段一般直接清掉测试数据即可；有真实数据时需要写一个迁移任务把本地文件推上 OSS。
本项目开发阶段直接重跑 `04_mall_product.sql` 即可。

---

## 七、常见错误排查表

| 现象 | 根因 | 处理 |
|---|---|---|
| `NoClassDefFoundError: javax/xml/bind/DatatypeConverter` | JDK 11+ 移除了 JAXB，OSS SDK 内部依赖它 | 补 `jaxb-api` + `activation` + `jaxb-runtime`（**已在父 POM 配好，别删**） |
| `SignatureDoesNotMatch` | ① 服务端与前端签名版本不一致（V1/V4）；② 参与签名的字段前后不一致（如 Content-Type）；③ **本地时钟偏差超 15 分钟** | 前后端统一签名版本与 region；把参与签名的字段原样回传；开 NTP 校时 |
| 直传报 CORS 错误 | Bucket 未配置跨域规则，或 Allow Methods 里漏了 PUT | Bucket → 数据安全 → 跨域设置，来源填具体域名 |
| 403 AccessDenied | ① AK 权限不足；② **漏传 `security-token`（STS 场景）**；③ Bucket 策略拒绝 | 逐项核对，STS 场景三个字段缺一不可 |
| 上传成功但图片 404 | `dir` 前缀被重复拼接（`mall/mall/product/...`），或读的时候没带同样的前缀 | 本项目的 `withDir()` 已做幂等处理；换实现时注意保持 |
| 内网慢 / 有流量费 | ECS 与 Bucket 不同地域，或用了公网 endpoint | 改用 `oss-cn-{region}-internal.aliyuncs.com` |
| 上传大图 OOM | 后端中转模式下文件全读进堆内存 | 改用直传（方案 ②/③），或限制单文件大小 |

---

## 八、参考文档

- OSS Java SDK V1 快速接入：`help.aliyun.com/zh/oss/developer-reference/oss-java-sdk`
- 服务端签名直传（PostObject）：`help.aliyun.com/zh/oss/user-guide/client-direct-transmission`
- 授权访问 / STS：`help.aliyun.com/document_detail/120092.html`
- 图片处理指南：`help.aliyun.com/zh/oss/user-guide/img-guide`
- 上传实践（含分片、断点续传）：`help.aliyun.com/zh/oss/user-guide/upload-objects`
