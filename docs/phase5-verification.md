# 阶段五：商品发布、图片、管理与收藏

日期：2026-10-19。工程：`D:\FQH_Programs\CampusMarket`。版本：0.5.0 / versionCode 4。

代码、真实接口测试、主机回归、APK 构建及 Lint 已完成；设备端 UI 验收待验证。
遵守用户限制，本阶段未启动 Android 模拟器、额外 Android Studio 实例，也未执行设备测试。
阶段六未开始，等待用户确认。

## 范围与实现

核对了现有 README、阶段四验收报告与会话中的原阶段计划。阶段四已提供分类、列表、搜索、
详情和真实账号链路；本阶段沿用原“发布与收藏”范围，并实现用户提出的删除管理。

- 发布：标题、价格、分类、成色、交接地点、描述，图片可选，最多五张。
- 图片：Android 系统文件选择器选择 JPEG/PNG，后台上传，预览并支持从表单移除。
- 管理：“我的”→“我的发布”，分页查看自己的在售/下架商品，点击管理后可编辑、下架、上架、删除。
- 删除：将商品状态改为 `DELETED`，禁止再次编辑或上架；保留数据库记录、收藏记录和图片文件。
- 收藏：详情收藏/取消收藏，“我的收藏”分页查看当前公开可见的收藏商品。
- 图片展示：首页卡片、详情图片横向列表、我的发布/收藏卡片；无照片时保留明确占位。
- 返回首页刷新列表，保留原账号、个人资料和数据库连接检查入口。

Android 继续使用 Java + XML、RecyclerView、ExecutorService、Gson 与主线程 Handler；
图片下载与解码也在后台执行，限制尺寸并缩采样，通过视图标记防止列表复用时显示错图。
没有新增第三方运行库，没有实现聊天、订单或支付。

## 数据与权限

增量迁移 `004_publishing.sql` 新增 `product_images` 与 `favorites`，结构版本升级为 4。
用户在本机隐藏提示中执行管理员迁移成功；随后使用原专用业务账号完成真实 JDBC 与接口验证。
迁移使用 `CREATE TABLE IF NOT EXISTS` 和版本号 `GREATEST`，可重复执行；不删除旧表、旧数据，
不重设账号或扩大业务账号权限，不保存管理员凭据。

业务身份从服务端会话解析，忽略请求传入的 sellerId/status。新增/编辑时验证分类和图片归属，
商品行与图片行在事务中加锁；只允许本人操作在售/下架商品。编辑、上下架、删除均检查商品归属，
他人修改返回 403；已删除或未来其他交易状态不可修改。过期、撤销或无效会话复用原鉴权逻辑。
图片不可跨用户使用，也不可重复绑定到其他商品；更新图片列表与商品字段同事务提交。

收藏主键为用户与商品组合，重复收藏/取消保持相同结果；取消只改 active 标志。
下架/删除商品不会出现在公共浏览或收藏列表中，收藏记录继续保留。

上传限制：单张最多 5MB、每请求一张、JPEG/PNG、单边最多4096像素且总像素最多1600万。
先读取实际格式和尺寸再解码，重新编码去除客户端元数据；随机生成服务端文件名，忽略原文件名。
文件位于 `.local/uploads/`，通过 `CAMPUS_UPLOAD_DIR` 注入，位于 WAR 之外。
公开图片要求商品在售、分类启用、卖家正常；未绑定或下架图片仅上传者可读，失效/删除图片不提供访问。
上传失败时只清理本次请求新建、尚未完成保存的文件，不清理已有上传文件。

## 接口

前缀：`/campus-market/api/v1`。JSON 沿用 `{code,message,data}`，业务响应禁止缓存。
除公开浏览与在售商品图片外，以下接口要求 Bearer 会话。

| 方法与路径 | 功能 |
| --- | --- |
| POST `/images` | multipart/form-data，唯一字段 file；返回图片编号、相对地址、尺寸 |
| GET `/images/{id}` | 读取真实图片字节，按商品状态验证访问权 |
| POST `/products` | 发布，成功 201 |
| PUT `/products/{id}` | 本人编辑，完整提交表单和 imageIds |
| PUT `/products/{id}/status` | status 为 ON_SALE 或 OFF_SHELF |
| DELETE `/products/{id}` | 本人软删除 |
| GET `/users/me/products` | 本人未删除发布，参数 page/pageSize |
| GET `/users/me/products/{id}` | 本人管理详情，包含下架商品 |
| GET `/favorites/{id}` | 本人的收藏状态 |
| PUT `/favorites/{id}` | 收藏当前可见商品 |
| DELETE `/favorites/{id}` | 取消收藏，保留记录 |
| GET `/users/me/favorites` | 本人当前可见的收藏，参数 page/pageSize |

发布/编辑字段：title、description、price（十进制字符串）、categoryId、conditionLabel、tradeLocation、
imageIds（数组，0–5个不重复编号）。价格范围 0–99999999.99，不接受负数、科学计数或超过两位小数。
分页默认20、最多50，page 为1–10000；商品只接受正确的正整数编号。
已有分类、浏览、搜索、详情接口保持可用，结果增加 status 与 images 字段。

## 实测结果与证据

| 验证 | 结果 | 证据位置 |
| --- | --- | --- |
| Maven / JDK17 打包 | BUILD SUCCESS | `.local/logs/phase5-server-build.log` |
| Tomcat10.1.60 + JDBC + MySQL8.0.45 | 通过，结构版本4 | 健康检查、JDBC探针 |
| 发布/管理真实 HTTP + JDBC | 73项通过 | `.local/evidence/phase5-publishing.json` |
| 用户/会话接口回归 | 67项通过 | `.local/evidence/phase3-api.json`（本次重新执行） |
| 分类/浏览/搜索回归 | 40项通过，原公开商品仍8件 | `.local/evidence/phase4-catalog.json`（本次重新执行） |
| 健康接口回归 | 通过 | `.local/evidence/http-health.json` |
| 实际 Android 传输/Gson 主机测试 | 6项通过，0失败、0错误 | `android-client/app/build/test-results/testDebugUnitTest/` |
| Debug APK 与原设备测试 APK 编译 | 通过 | `.local/logs/phase5-android-final.log` |
| 最终布局 APK 构建与 Lint | 通过；0 errors、3 warnings | `.local/logs/phase5-android-artifacts.log` |
| WAR 与 Tomcat 部署副本SHA-256 | 一致 | `.local/evidence/phase5-artifacts.json` |
| 13个 PowerShell 脚本语法 | 通过 | 本次解析检查 |
| 115个源码/文本日志凭据检查 | 未发现业务密码或明文Bearer令牌 | `.local/evidence/phase5-secret-check.json` |
| 设备 UI | 待验证 | 未运行设备测试，不用主机测试代替UI验收 |

发布探针实际创建两名随机账号并上传PNG字节，验证 MySQL 商品/图片绑定、价格修改与收藏记录。
覆盖未登录访问、跨用户图片读取与使用、他人编辑/下架/删除、伪造 sellerId/status、错误价格、无效分类、
重复/超量/无效图片编号、伪装SVG、超大文件/尺寸、图片替换、上下架可见性、软删除后不可恢复、撤销会话。
原有记录不删除；验收账号明确标注为虚构，验收商品最终软删除，测试凭据与会话不输出。

客户端新增两项主机测试直接调用 UserApi/CatalogApi，完成注册登录、真实multipart上传、发布、编辑、
收藏、上下架、删除与退出后的401处理；测试使用固定小PNG作为上传输入，服务端和数据库结果均真实。
原四项浏览主机测试继续通过。没有使用 Mock 接口或内存数据库。

## 文件清单

新增：

- `database/migrations/004_publishing.sql`
- `server/src/main/java/com/campusmarket/PublishingServlet.java`
- `server/src/main/java/com/campusmarket/ImageServlet.java`
- `server/src/main/java/com/campusmarket/dao/PublishingDao.java`
- `server/src/test/java/com/campusmarket/PublishingIntegrationProbe.java`
- `scripts/Test-Publishing.ps1`
- `android-client/app/src/main/java/com/campusmarket/ProductEditActivity.java`
- `android-client/app/src/main/java/com/campusmarket/MyProductsActivity.java`
- `android-client/app/src/main/java/com/campusmarket/ImageLoader.java`
- `android-client/app/src/main/res/layout/activity_product_edit.xml`
- `android-client/app/src/main/res/layout/activity_my_products.xml`
- `android-client/app/src/main/res/values/publishing_strings.xml`
- `android-client/app/src/test/java/com/campusmarket/network/PublishingApiIntegrationTest.java`
- `docs/phase5-verification.md`

修改：

- `scripts/Configure-Database.ps1`：增量迁移入口和业务账号结构验证。
- `scripts/LocalEnvironment.ps1`：上传目录子进程配置。
- `server/src/main/java/com/campusmarket/CatalogServlet.java`：新增写请求分发。
- `server/src/main/java/com/campusmarket/dao/CatalogDao.java`：图片、状态、本人详情及受限分页。
- `server/src/main/java/com/campusmarket/UserServlet.java`：复用原有限制的JSON读取函数。
- `server/src/test/java/com/campusmarket/CatalogIntegrationProbe.java`：发布接口从未实现405改为未认证401。
- `server/pom.xml`、`android-client/app/build.gradle`：版本更新。
- `android-client/app/src/main/AndroidManifest.xml`：两个内部Activity。
- `android-client/app/src/main/java/com/campusmarket/MainActivity.java`：发布/我的发布/收藏入口及刷新。
- `android-client/app/src/main/java/com/campusmarket/CatalogController.java`：列表图片及返回刷新。
- `android-client/app/src/main/java/com/campusmarket/ProductDetailActivity.java`：图片、收藏和认证失败提示。
- `android-client/app/src/main/java/com/campusmarket/network/UserApi.java`：真实multipart上传。
- `android-client/app/src/main/java/com/campusmarket/network/CatalogApi.java`：发布管理/收藏DTO与请求。
- `android-client/app/src/main/res/layout/profile_panel.xml`、`item_product.xml`、`activity_product_detail.xml`：入口与图片。
- `README.md`：当前功能、迁移和验证说明。

构建输出与本地运行证据位于已忽略的 build、target、.local；不提交凭据或上传文件。

## 待验证与已知限制

- 设备端 UI 尚未运行：需后续授权使用设备后验证图片选择、表单、旋转恢复、列表管理和收藏交互。
- Lint剩余3条为原有 SharedPreferences.commit 提示、Gradle版本提示、Gson版本提示；未屏蔽检查。
- Gradle仍提示将来的Gradle10兼容性问题；当前固定版本构建通过，本阶段没有升级工具链。
- 调试包默认使用模拟器宿主机地址10.0.2.2；release地址未配置，本次不作为上线发布包验收。
- 未提交表单产生的上传图片继续私有保留；未增加自动清理、总存储配额或压缩重试，避免清理已有数据。
- 图片最多五张，原图超过限制会提示失败；界面图片下载失败保留占位，可刷新/重新进入页面。
- 这是课程设计的本机部署；未开发聊天、订单和支付。完成本阶段后暂停，等待确认。
