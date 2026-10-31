# 阶段三：用户注册、登录与个人资料

项目根目录保持 `D:\FQH_Programs\CampusMarket`。阶段四未开始。
阶段三验收要求均已通过，等待用户确认。未启动新的 Android Studio 实例，未结束 IDE 进程。

## 实现范围

- “我的”入口接入个人中心；登录/注册页包含用户名、密码、确认密码和昵称。
- 注册成功后明确提示登录；登录后查看和修改昵称、学校、校区、简介。
- 退出登录先撤销服务端会话，成功后清除本地加密状态；网络失败时保留登录，允许重试。
- App 重开时读取加密会话，再向后端校验；过期/撤销返回 401 并清除本地状态。
- 所有请求通过 ExecutorService 发起，Gson 解析，Handler 切回主线程，Activity 销毁后不更新旧界面。
- 保留首页真实数据库检查，以及发布、消息两个占位入口；不开发商品或聊天业务。

## 数据库迁移

`002_users_and_sessions.sql` 新增：

- `users`：唯一用户名、密码哈希、昵称、学校、校区、简介、状态、失败计数及临时锁定时间。
- `auth_sessions`：令牌摘要主键、用户外键、过期时间、撤销时间和索引。

迁移通过本机隐藏管理员提示执行，管理员凭据不持久化；后端继续使用阶段二的专用业务账号。
不执行 DROP、TRUNCATE 或数据删除，不重置账号密码。结构版本由 1 升级至 2；原健康检查支持
升级后的结构版本，继续真实查询数据库。

## 接口

统一前缀 `/campus-market/api/v1`，JSON 响应为 `code/message/data`，认证使用 Bearer Token。
响应 `Cache-Control: no-store`；请求体限制大小，SQL 使用参数绑定，日志不记录认证头或请求体。

| 方法与路径 | 输入/行为 |
| --- | --- |
| POST `/auth/register` | username、password、confirmPassword、nickname；创建成功返回 201，不自动登录 |
| POST `/auth/login` | username、password；返回 token、expiresAt（毫秒时间戳）、user |
| POST `/auth/logout` | 认证头；撤销当前令牌，返回 200 |
| GET `/users/me` | 认证头；仅返回当前用户公开资料 |
| PUT `/users/me` | 认证头；nickname、school、campus、bio；用户身份由令牌确定，忽略客户端提交的 id |

用户名为 3–32 位英文字母、数字或下划线，统一转为小写；密码 8–128 个字符；昵称必填、最多
40 个 Unicode 码点，学校/校区最多 80，简介最多 200。资料文本不接受控制字符。
重复用户名返回 409，错误密码/无效令牌返回 401，过期令牌返回 401/code=40102，
连续五次错误登录后锁定五分钟，期间返回 429。JSON 格式、类型、大小和缺少字段均校验。

## 密码与会话安全

- JDK 自带 PBKDF2-HMAC-SHA256，600,000 次迭代、每用户独立 16 字节随机盐、256 位结果；
  记录算法和参数，常量时间比较派生结果。未使用普通 SHA-256 存储密码。
- 令牌使用 32 字节安全随机数；原文仅在登录响应中交给客户端，数据库只保存 SHA-256 摘要。
- 会话保存在 MySQL，有效期七天，退出立即撤销，支持服务重启后继续校验。
- Android 使用 Keystore AES-GCM 加密令牌，SharedPreferences 仅保存密文、IV 和过期时间；
  不保存密码。密码输入框不保存 View 状态，登录页设置 FLAG_SECURE。
- 云备份和设备迁移显式排除会话 SharedPreferences；资料页不包含密码哈希或令牌。

实现参考：[OWASP 密码存储说明](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)、
[Android Keystore 官方说明](https://developer.android.com/privacy-and-security/keystore)。

## 验证结果

- 数据库迁移由用户在本机执行成功；业务账号只读复核 `users`、`auth_sessions` 和版本 2 通过。
- 后端 Maven Wrapper 构建成功；真实 API/JDBC 集成探针通过 67 项检查。
- Android `assembleDebug` 和 `lintDebug` 成功；Gradle connectedDebugAndroidTest 1 项测试通过，0 失败。
- 仪器测试验证注册、确认密码不匹配、重复用户名、错误密码、资料提交、退出撤销与加密存储。
- 直接 ADB 仪器测试通过，并保留测试状态；强制停止 App 后重开，登录与资料恢复通过。
- 停止项目 Tomcat 后，App 显示网络异常并保留会话；启动新的 Tomcat 进程后，原会话仍可查询资料。
- 仅将当前 UI 验收账号的一个活动会话设为过期后，App 重开显示重新登录提示并清除失效状态。
- 使用业务账号独立 JDBC 查询确认 Android 提交的昵称、学校、校区、简介和密码哈希格式。
- 源码及选定文本日志/证据共 95 个文件的凭据检查通过：未发现业务数据库密码或明文 Bearer 令牌。
- 最终 Android 离线构建与 Lint 通过；Lint 为 0 errors、3 warnings（Gradle/Gson 更新提示和同步提交本地退出状态的建议）。
  同步清除会话是为了在进程立即结束前完成持久化；并非网络在主线程执行。
- 最后一次回归发现模拟器与项目 Tomcat 进程已退出；只恢复原 AVD 和项目后端，不清除数据。
  恢复后最终 APK 安装、四个导航、首页实时查询、JDBC 和 HTTP 健康检查全部通过。
- 最终 WAR 与部署 WAR 的 SHA-256 一致，数据库结构版本 2、健康检查正常；APK/WAR 校验信息已记录。

## 本机证据

- `.local/logs/phase3-server-build.log`、`phase3-android-build.log`：后端和客户端构建。
- `.local/logs/phase3-android-ui-test.log`：Gradle 设备测试结果。
- `.local/logs/phase3-instrumentation.log`：直接仪器测试结果（用于保留状态）。
- `.local/evidence/phase3-api.json`：67 项 API/JDBC 检查清单，无令牌或密码。
- `.local/evidence/phase3-ui-result.json`、`phase3-profile.png`：界面验收摘要及资料页截图。
- `.local/evidence/phase3-account-*.json/png`：进程重启、网络异常及过期处理。
- `.local/evidence/phase3-service-restart.json`：重启前后不同 Tomcat PID 和会话保持验证。
- `.local/evidence/phase3-secret-check.json`：凭据扫描范围与结果。
- `.local/evidence/phase3-artifacts.json`：最终 APK/WAR SHA-256、部署一致性及数据库健康状态。
- `android-client/app/build/reports/androidTests/connected/debug/index.html`：Gradle 仪器测试报告。

验收账号随机命名为 `p3_api_*` / `p3_ui_*`，均标注为测试资料。测试不清理或删除数据库记录，
测试密码随机生成且不输出、不保存；它们不作为提供给用户的演示登录凭据。

## 修改文件清单

```text
database/migrations/002_users_and_sessions.sql              新增用户和会话表
server/pom.xml                                            版本 0.3.0
server/src/main/java/com/campusmarket/UserServlet.java      用户接口路由与 JSON/异常处理
server/src/main/java/com/campusmarket/auth/ApiException.java
server/src/main/java/com/campusmarket/auth/PasswordHasher.java
server/src/main/java/com/campusmarket/dao/UserDao.java
server/src/main/java/com/campusmarket/service/AuthService.java
server/src/test/java/com/campusmarket/AuthIntegrationProbe.java
server/src/test/java/com/campusmarket/UiFixtureProbe.java
server/src/test/java/com/campusmarket/JdbcProbe.java         兼容结构升级
android-client/app/build.gradle                           版本及测试依赖
android-client/app/src/main/AndroidManifest.xml
android-client/app/src/main/java/com/campusmarket/MainActivity.java
android-client/app/src/main/java/com/campusmarket/AuthActivity.java
android-client/app/src/main/java/com/campusmarket/EditProfileActivity.java
android-client/app/src/main/java/com/campusmarket/UiInsets.java
android-client/app/src/main/java/com/campusmarket/auth/SessionStore.java
android-client/app/src/main/java/com/campusmarket/network/UserApi.java
android-client/app/src/main/res/layout/activity_main.xml
android-client/app/src/main/res/layout/profile_panel.xml
android-client/app/src/main/res/layout/activity_auth.xml
android-client/app/src/main/res/layout/activity_edit_profile.xml
android-client/app/src/main/res/values/strings.xml
android-client/app/src/main/res/values/styles.xml
android-client/app/src/main/res/xml/backup_rules.xml
android-client/app/src/main/res/xml/data_extraction_rules.xml
android-client/app/src/androidTest/java/com/campusmarket/AuthFlowTest.java
scripts/Configure-Database.ps1                            增量迁移入口
scripts/Test-Http.ps1                                     兼容结构升级
scripts/Test-App.ps1                                      回归新版“我的”入口
scripts/Test-Auth.ps1
scripts/Run-AndroidAuthTest.ps1
scripts/Test-AccountState.ps1
scripts/Test-UiFixture.ps1
README.md
docs/phase3-verification.md
```

## 当前边界

- 无已知阻塞问题；实体手机、生产 HTTPS 环境和 release 发布仍不在本次验收范围。
- 不启动、关闭或重置 Android Studio；全程命令行编译及 ADB 测试。
- 仅验收本机 HTTP 调试环境和 Android 16 模拟器；release 仍禁止 HTTP、服务地址未配置。
- 未加入忘记密码、修改密码、头像、校园身份认证或后台管理；这些不属于本阶段要求。
- 过期及已撤销会话保留用于核查，无自动清理任务。
