# 校园易物 · CampusMarket

基于 Android 的校园二手交易平台课程设计。目前实现至阶段六：用户体系、商品浏览搜索、
图片上传、发布与管理、收藏、订单及模拟交易。阶段四至六设备端 UI 验收待验证；留言尚未开发。

## 工程位置

- Android Studio 工程：`android-client/`（Java + XML，最低 Android 8.0 / API 26）。
- 后端：`server/`（Java 17、Jakarta Servlet 6、JDBC，打包 WAR）。
- 数据库迁移：`database/migrations/`。
- 本机脚本：`scripts/`。
- 私密配置和运行产物：`.local/`（Git 忽略，不分享或提交）。

客户端固定 AGP 9.1.1 / Gradle 9.3.1 / JDK 17 / compile SDK 36.1，使用 Gson 解析 JSON。
Gradle daemon JVM criteria 固定 Java 17，IDE 自身的 JBR 版本不等于项目构建 JDK。
后端固定 Maven 3.9.9，通过 Maven Wrapper 构建，部署至本机 Tomcat 10.1.60。

## 数据库配置

首次配置请参阅 [数据库初始化说明](docs/database-setup.md)。已成功配置后无需重新建库；
再次运行初始化脚本只验证业务账号与数据。禁止把密码加入源码、URL、命令行参数或日志。

## 编译

在普通 PowerShell 中设置本次会话的 JDK，然后分别运行两个工程的 Wrapper：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'
$env:ANDROID_HOME = 'D:\Android\SDK'
Set-Location D:\FQH_Programs\CampusMarket\android-client
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
Set-Location D:\FQH_Programs\CampusMarket\server
.\mvnw.cmd -B package
```

APK：`android-client/app/build/outputs/apk/debug/app-debug.apk`。
WAR：`server/target/campus-market.war`。

Gradle Wrapper 保留官方分发 URL、URL 验证和官方 SHA-256。首次联网下载可能需要本机已有代理；
不应关闭证书、校验和或 URL 验证。当前机器依赖已下载，可使用 `--offline` 重复构建 Android。
不要把本机代理地址或机器专属 JDK 路径写入公共 Gradle 配置。

## 启动与验收

在项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Jdbc.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Http.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-App.ps1
```

`Test-App.ps1` 要求已有设备 `emulator-5554` 连接且完成启动；也可用 `-Serial` 指定设备。
该阶段调试地址固定为模拟器宿主机 `10.0.2.2`，不能直接用于实体手机。
脚本安装 APK、点击连接、验证四个导航入口并检查两次查询的数据库时间发生变化。

本机接口：

- `GET http://127.0.0.1:8080/campus-market/api/v1/health`：Servlet 运行状态。
- `GET http://127.0.0.1:8080/campus-market/api/v1/health/db`：真实 JDBC 查询结果。

Tomcat 使用 `.local/tomcat/` 作为独立 `CATALINA_BASE`，不会修改原 Tomcat 安装配置。
只绑定本机 127.0.0.1:8080；模拟器通过 `10.0.2.2` 访问。
业务凭据仅在启动子进程时注入环境，密码不在 JDBC URL 中，脚本结束会恢复自身进程环境。
若服务已运行，启动脚本会拒绝重复启动；重新部署前显式停止本项目服务：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Backend.ps1
```

停止脚本校验记录的 PID、进程启动时间及 Java 进程类型，避免误停其他进程。
此停止方式会结束本项目 JVM；请在没有正在提交的业务请求时使用。

后端不可达的客户端错误路径可这样验证（随后必须重新启动后端）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Backend.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-App.ps1 -ExpectUnavailable
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

## 通信实现

`MainActivity` → `ExecutorService` → `HttpURLConnection` → Tomcat `HealthServlet` →
`Database.open()` / JDBC → MySQL `app_metadata` → JSON → Gson → 主线程 `Handler` → XML 界面。

数据库返回持久化项目记录、库名、版本和实时数据库时间；未使用模拟接口或静态数据库结果。
后台请求有连接/读取超时；界面在请求期间禁用重复点击，失败后可重试，Activity 销毁后停止回调更新。
只有 debug 构建为指定本机地址允许 HTTP。release 禁止明文通信，服务地址未配置，不作为发布包验收。

## Android Studio

请在现有 IDE 窗口内通过 File → Open 打开 `android-client`，需要选择时使用 **This Window**。
不要反复启动 `studio64.exe`，不要为解决 DirectoryLock 删除配置目录或强制关闭现有实例。
阶段二曾通过模拟器验收及 IDE 导入/Sync；阶段四至六仅完成命令行与主机验收，设备UI待验证。

测试证据、限制与文件清单见 [阶段二验收报告](docs/phase2-verification.md)。
用户功能、接口和新增验收见 [阶段三验收报告](docs/phase3-verification.md)。

## 用户功能与阶段三验收

在 App 的“我的”入口选择“登录 / 注册”。注册填写用户名、密码、确认密码和昵称，
成功后使用新账号登录；登录后可编辑昵称、学校、校区和个人简介，也可退出登录。

首次升级数据库需要在本机隐藏提示中输入管理员密码（不保存）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Configure-Database.ps1 -ApplyPhase3Migration
```

迁移新增用户与会话表，原库、元数据及已有账号均保留，结构版本升级为 2。后续业务访问仍使用
原专用读写账号，不授予其建表权限。数据库操作不要使用 root 运行后端。

后端重新打包后，用项目停止/启动脚本部署，再执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Auth.ps1
```

此真实 HTTP/JDBC 测试会创建两个随机命名的验收账号，保留它们，不输出密码或令牌。
验证过期时，只修改本次新建账号的特定测试会话。

Android 界面测试可执行 `gradlew.bat :app:connectedDebugAndroidTest`。Gradle 的测试框架会在结束时
清理测试应用，因此验证进程重启时应先构建，再用直接仪器脚本保留状态：

```powershell
Set-Location D:\FQH_Programs\CampusMarket\android-client
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
Set-Location D:\FQH_Programs\CampusMarket
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-AndroidAuthTest.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-UiFixture.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-AccountState.ps1 -Mode Restore
```

直接仪器测试新增一个随机验收账号；证据位于 `.local/evidence/phase3-*`，不含凭据。
接口说明与安全实现见阶段三报告。不要重复启动 Android Studio 图形界面。

## 阶段四商品浏览

首页为 RecyclerView 商品列表，支持分类、关键词、最新/价格排序和“加载更多”；点击商品查看
详情及卖家公开资料。原数据库健康检查保留在首页“连接检查”中，“我的”用户功能保持可用。
商品没有照片时显示明确占位；阶段五已支持真实上传图片。

数据库增量迁移需要用户在本机隐藏提示中输入管理员密码：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Configure-Database.ps1 -ApplyPhase4Migration
```

编译并部署后，可使用原业务账号初始化少量虚构演示商品并验证实际接口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Catalog.ps1 -SeedDemo
```

初始化可重复执行，不覆盖已有商品；8 件公开演示商品与 1 件下架验证商品均明确标记为演示。
演示卖家密码随机生成，不输出或保存，不作为演示登录账号。

保持本机 Tomcat 运行后，可在不启动模拟器的情况下编译并运行真实客户端 HTTP/Gson 主机测试：

```powershell
Set-Location D:\FQH_Programs\CampusMarket\android-client
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
```

`testDebugUnitTest` 通过 package-private 构造函数指定本机 127.0.0.1，调用实际客户端传输与
Gson 代码连接 Tomcat；App 的模拟器默认服务地址仍为 10.0.2.2，没有替换真实接口。
阶段四按用户限制不启动模拟器或额外 Android Studio 实例，设备端 UI 测试仅编译、未执行。
本阶段文件清单、结果与限制见 [阶段四报告](docs/phase4-verification.md)。

## 阶段五发布与管理、收藏

登录后从“发布”填写商品信息，可选上传最多五张 JPEG/PNG 图片（每张不超过5MB、尺寸不超过4096像素）。
“我的”→“我的发布”可编辑、下架、重新上架或确认删除；删除为软删除，数据库记录和图片文件保留。
商品详情支持收藏/取消收藏，“我的收藏”仅展示当前可公开浏览的商品。

新增迁移由用户在本机执行成功，结构版本为4；首次升级其他环境时按顺序应用前面的迁移，再运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Configure-Database.ps1 -ApplyPhase5Migration
```

新增 `product_images`、`favorites` 表，不授予业务账号 DDL 权限。上传图片放在 `.local/uploads/`，
通过子进程环境变量 `CAMPUS_UPLOAD_DIR` 指定，位于 WAR 之外；部署不会清空该目录。
后端会检查真实图片内容并重新编码、去除元数据，随机生成文件名，不使用客户端提供的文件名。
备份应同时包含数据库与上传目录；不要手动清空上传文件或清理已有数据库。

后端部署并运行后，执行真实接口、回归验证：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Publishing.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Auth.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Catalog.ps1
```

测试会新增明确标注的虚构账号和商品；测试商品最终软删除，记录保留，不输出密码或令牌。
Android 构建及主机测试命令沿用上节，必须保持本机 Tomcat 可用。
按当前用户限制不启动模拟器或额外 Android Studio 实例，不执行设备 UI 测试。
范围、文件清单和完整验收结果见 [阶段五报告](docs/phase5-verification.md)。

## 阶段六订单与模拟交易

登录后从商品详情下单，“我的”可分别查看买入、卖出订单。卖家确认接单后买家才可模拟支付，
卖家确认已线下交付后，买家确认收货完成交易。界面明确提示模拟支付不扣款、不发生资金转移。

下单时商品变为交易中，不能继续购买、编辑、上下架或删除。交付前双方可以取消订单，
已模拟支付时同时记录模拟退款，并恢复商品可售；交付后不支持取消。收货后商品标记为已售。
目前没有自动超时取消，待确认/待付款订单需要买家或卖家主动取消。

数据库结构版本为5，升级时在隐藏管理员密码提示中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Configure-Database.ps1 -ApplyPhase6Migration
```

仅新增 orders、simulated_payments、order_events，保留已有数据及业务账号权限。
订单保存下单时的商品信息和价格快照；请求唯一编号防止重试重复下单，行锁与数据库唯一约束防止抢购超卖。
模拟支付及每种成功操作的流水各只记录一次。事务规则及状态机详见 [阶段六完整验收报告](docs/phase6-verification.md)。

构建部署后可执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Orders.ps1
```

此测试包含最多8个请求同时抢购、重复下单、重复支付及取消/交付竞争，使用真实HTTP与MySQL。
测试仅新增明确标注的虚构记录，取消未完成的验收交易并软删除未售测试商品，保留已完成订单及售出商品。
执行原 Test-Publishing、Test-Auth、Test-Catalog、Test-Http 和 Test-Jdbc 可进行回归。
Android testDebugUnitTest 包含9项真实传输/Gson主机测试；应保持本机Tomcat可用。
涉及全库计数的接口探针与客户端主机测试应依次执行，避免测试夹具的临时商品互相影响计数。

本阶段不启动模拟器或额外Android Studio实例，设备UI继续待验证。阶段六完成后暂停，阶段七等待确认。
