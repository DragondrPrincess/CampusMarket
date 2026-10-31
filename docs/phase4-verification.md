# 阶段四：商品分类、列表、搜索与详情

开发位置：`D:\FQH_Programs\CampusMarket`；版本 0.4.0。
实现和命令行验收已完成，设备端界面运行尚未验收。阶段五未开始。
按用户限制，未启动 Android 模拟器或额外 Android Studio 实例，也未执行设备测试。

## 已实现

- 首页 RecyclerView 商品卡片：标题、价格、成色、交接地点和分类标识。
- 分类从 MySQL 加载；关键词搜索、最新/价格升降序、分页及加载更多。
- 加载、空结果、失败重试；新的筛选请求覆盖旧请求结果，分页沿用已提交搜索词。
- 商品详情：描述、成色、分类、发布时间、线下交接地点、卖家公开昵称/学校/校区。
- 仅展示在售、分类启用且卖家账号正常的商品；下架和不存在的商品返回 404。
- 首页“连接检查”保留原真实数据库健康检查，“我的”继续复用阶段三账号功能。
- Java + XML，ExecutorService 发起请求，Gson 解析，Handler 更新界面。

未开发商品发布、编辑、下架操作界面、图片上传、收藏、留言或订单。
商品卡片分类标识为界面装饰，不冒充实物照片；详情明确显示“暂无商品图片”。

## 数据库与演示数据

`003_catalog.sql` 新增 `categories`、`products`，包含用户/分类外键、价格约束及状态/分类/卖家索引。
迁移由用户在隐藏管理员提示下执行成功，业务账号复核结构版本 3；未更改业务账号权限。
迁移不删除表或已有数据，仅条件插入六个基础分类，并更新结构版本。

演示初始化使用原业务账号，生成一个明确标注的虚构卖家；随机密码不输出、不保存。
写入 8 件公开演示商品、1 件下架验证商品；商品标题和描述明确说明虚构课程演示用途。
重复执行初始化后公开商品仍为 8 件；已有记录不会被覆盖，不删除测试或用户数据。

## 接口

统一前缀 `/campus-market/api/v1`；本阶段浏览接口允许未登录访问。

| 方法与路径 | 说明 |
| --- | --- |
| GET `/categories` | 启用的分类，按排序字段和主键排序 |
| GET `/products` | 参数 page、pageSize、categoryId、keyword、sort |
| GET `/products/{id}` | 在售商品详情和卖家公开资料 |

page 默认 1，范围 1–10000；pageSize 默认 20，范围 1–50；客户端每页 6 件。
sort 仅允许 latest、price_asc、price_desc，均包含主键作为稳定排序次序。
关键词最多 100 个 Unicode 码点，按标题和描述查找；SQL 参数绑定，LIKE 通配符按字面转义。
金额以十进制字符串返回，避免浮点精度损失。响应保留 code/message/data，禁止缓存实时查询结果。
卖家登录用户名、密码哈希、令牌均不返回。非法参数 400、不可见详情 404、不支持的方法 405。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| Maven Wrapper / JDK 17 后端打包 | BUILD SUCCESS |
| Tomcat 10.1.60 部署及 JDBC | 通过，结构版本 3 |
| 分类/商品真实 HTTP + JDBC 探针 | 40 项检查通过 |
| 演示初始化重复执行 | 通过，公开商品仍为 8 件 |
| 阶段三用户 API 回归 | 67 项检查通过 |
| 原健康接口与 JDBC 探针 | 通过 |
| Android APK、界面测试 APK 编译 | 通过 |
| Android 实际 HTTP/Gson 代码的主机集成测试 | 4 项通过，0 失败、0 错误 |
| Lint | 0 errors、4 warnings |
| 最终 WAR 与部署副本 SHA-256 | 一致 |
| 脚本语法及源码/文本日志凭据检查 | 通过，未发现业务密码或明文 Bearer 令牌 |
| 设备端商品界面运行 | 未执行，遵守用户不启动模拟器的限制 |

40 项检查覆盖中文搜索、分类筛选、无重叠分页、SQL/API 数量一致、价格排序、空结果、
字面百分号、注入文本作为普通数据处理、错误分页/排序/ID、下架商品隐藏和禁止写接口。
四项主机测试直接调用客户端 CatalogApi/UserApi 与 Gson，连接本机 Tomcat；不是假接口或模拟数据库。
界面仪器测试 CatalogFlowTest 已编译，未运行，不据此宣称 RecyclerView 点击、布局或真机体验通过。

Lint 警告为原有同步清除会话及工具/Gson 更新提示，以及数据集整体刷新建议；
不会为消除提示关闭检查。后续如数据量增大，可改用 DiffUtil 优化筛选时的列表刷新。

## 证据

- `.local/logs/phase4-server-build.log`
- `.local/logs/phase4-android-build.log`、`phase4-android-final.log`
- `.local/evidence/phase4-catalog.json`
- `.local/evidence/phase4-artifacts.json`
- `.local/evidence/phase4-secret-check.json`
- `android-client/app/build/test-results/testDebugUnitTest/TEST-com.campusmarket.network.CatalogApiIntegrationTest.xml`
- `android-client/app/build/reports/tests/testDebugUnitTest/index.html`
- `android-client/app/build/reports/lint-results-debug.html`

## 修改文件清单

```text
database/migrations/003_catalog.sql
scripts/Configure-Database.ps1
scripts/Test-Catalog.ps1
scripts/Test-App.ps1
server/pom.xml
server/src/main/java/com/campusmarket/CatalogServlet.java
server/src/main/java/com/campusmarket/dao/CatalogDao.java
server/src/test/java/com/campusmarket/CatalogSeed.java
server/src/test/java/com/campusmarket/CatalogIntegrationProbe.java
android-client/app/build.gradle
android-client/app/src/main/AndroidManifest.xml
android-client/app/src/main/java/com/campusmarket/MainActivity.java
android-client/app/src/main/java/com/campusmarket/CatalogController.java
android-client/app/src/main/java/com/campusmarket/ProductDetailActivity.java
android-client/app/src/main/java/com/campusmarket/network/UserApi.java
android-client/app/src/main/java/com/campusmarket/network/CatalogApi.java
android-client/app/src/main/res/layout/activity_main.xml
android-client/app/src/main/res/layout/catalog_panel.xml
android-client/app/src/main/res/layout/item_product.xml
android-client/app/src/main/res/layout/activity_product_detail.xml
android-client/app/src/main/res/values/strings.xml
android-client/app/src/test/java/com/campusmarket/network/CatalogApiIntegrationTest.java
android-client/app/src/androidTest/java/com/campusmarket/CatalogFlowTest.java
README.md
docs/phase4-verification.md
```
