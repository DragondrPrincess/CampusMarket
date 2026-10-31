# 阶段二验收记录

验证日期：2026-10-19（Asia/Shanghai）。阶段三未开始。
阶段二全部验收项已通过；Android Studio 导入与 Gradle Sync 由用户在现有窗口操作并确认成功。

## 已实测通过

| 项目 | 实际验证 |
| --- | --- |
| MySQL | 8.0.45，TCP 127.0.0.1:3306；业务账号可登录，四项 DML 授权，无全局权限 |
| 持久化数据 | `campus_market.app_metadata`，id=1，project_name=校园易物，schema_version=1 |
| JDBC | JDK 17 加载 Connector/J，`Connection.isValid` 与实际 SELECT 通过 |
| 后端构建 | 项目 Maven Wrapper 3.9.9 执行 `package`，BUILD SUCCESS |
| Tomcat | 10.1.60；WAR 部署至项目独立运行目录，实际启动并通过数据库就绪检查 |
| HTTP | `/health` 和 `/health/db` 返回 200；数据库响应禁止缓存；POST 返回 405 |
| Android 构建 | 项目 Gradle Wrapper 9.3.1；JDK 17；`assembleDebug`、`lintDebug` 成功 |
| Android Studio | 用户在现有窗口打开 `android-client`，确认工程导入和 Gradle Sync 成功 |
| Android SDK | compile API 36.1、Build Tools 36.0.0，已用于实际 APK 构建 |
| SDK 命令行工具 | 新增官方 cmdline-tools 23 至原先缺失的目录，官方仓库校验和与大小一致；版本及已安装组件查询通过 |
| ADB | Platform Tools 37.0.0，可连接 emulator-5554；先前用户目录报错来自受限执行环境 |
| 模拟器 | Medium_Phone_API_36.1，Android 16，系统启动完成，AEHD 加速可用 |
| 安装与界面 | `adb install -r` 成功，MainActivity 启动成功，四个导航入口通过自动点击检查 |
| 完整通信 | App 显示真实项目记录、数据库名、MySQL 版本及数据库时间；刷新时间发生变化 |
| 失败路径 | 仅停止项目 Tomcat 后，App 显示可恢复连接错误、重试按钮恢复；随后重启 Tomcat 成功 |

Tomcat 日志存在 User-Agent 为 `CampusMarket-Android/0.2` 的 `/health/db` HTTP 200 请求，
与 App 自动验收的时间一致。数据库验证没有执行 DROP、TRUNCATE、DELETE 或重置账号密码。

## 证据位置（本机）

- `.local/logs/android-build.log`：正式构建与 Wrapper 生成。
- `.local/logs/android-wrapper-build.log`：项目 Wrapper 可重复构建。
- `.local/logs/server-wrapper-build.log`：项目 Maven Wrapper 构建。
- `.local/logs/sdk-installed.log`：SDK 命令行工具查询的已安装组件。
- `android-client/app/build/reports/lint-results-debug.html`：Lint 报告。
- `.local/evidence/http-health.json`：真实 HTTP 数据库响应。
- `.local/evidence/artifacts.json`：APK/WAR SHA-256、部署 WAR 一致性及最终健康检查。
- `.local/evidence/app-test.json`：安装、导航、真实查询及刷新验收结果。
- `.local/evidence/app-success.png`：成功界面截图。
- `.local/evidence/app-unavailable-test.json`、`app-unavailable.png`：后端不可达验收。
- `.local/tomcat/logs/access*.log`：Android 客户端访问记录。

这些证据不包含密码；`.local` 不进入版本库。不要分享其中的凭据 XML。
源码及项目文本日志/证据共 57 个文件已检查，未发现业务账号密码原文；运行中日志使用共享只读方式检查。

## IDE 状态与限制

- 用户确认原 Android Studio 窗口可交互，不能将约 1.1 GB 内存占用解释为卡死。
- 重复调用 IDE 启动器出现 Start Failed / DirectoryLock$CannotActivateException；已停止启动操作。
- 原进程和额外启动进程均未被本项目脚本结束，IDE 设置和配置目录未重置或删除。
- Android Studio 对 `android-client` 的工程导入及 Gradle Sync 已由用户在现有窗口操作并确认成功；
  该项证据来源为用户实测反馈，命令行构建与模拟器验证由开发脚本完成。
- Lint 当前为 0 errors、3 warnings：Gradle/Gson 有新版本提示，以及 Android 12+ 备份规则提示。
  当前无用户账号或私密客户端业务数据，`allowBackup=false`；后续引入登录存储时补齐备份规则。
- 仅验收 debug APK 和模拟器；实体手机网络、release HTTPS 地址及签名发布尚未验收。
- 未开发登录、商品、聊天、订单等业务功能；四个导航仅首页有真实连通验证，其余为明确占位。

## 文件清单（排除构建产物与私密配置）

```text
.gitignore
README.md
android-client/
  settings.gradle
  build.gradle
  gradle.properties
  gradlew
  gradlew.bat
  gradle/gradle-daemon-jvm.properties
  gradle/wrapper/gradle-wrapper.jar
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/campusmarket/MainActivity.java
  app/src/main/java/com/campusmarket/network/HealthClient.java
  app/src/main/java/com/campusmarket/network/HealthResponse.java
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/values/strings.xml
  app/src/main/res/values/styles.xml
  app/src/main/res/values-v27/styles.xml
  app/src/main/res/color/navigation_text.xml
  app/src/main/res/drawable/card_background.xml
  app/src/main/res/drawable/ic_launcher.xml
  app/src/debug/AndroidManifest.xml
  app/src/debug/res/xml/debug_network_security_config.xml
server/
  pom.xml
  mvnw
  mvnw.cmd
  .mvn/wrapper/maven-wrapper.properties
  src/main/java/com/campusmarket/Database.java
  src/main/java/com/campusmarket/HealthServlet.java
  src/main/webapp/WEB-INF/web.xml
  src/test/java/com/campusmarket/JdbcProbe.java
database/migrations/001_phase2.sql
scripts/
  Configure-Database.ps1
  LocalEnvironment.ps1
  Start-Backend.ps1
  Stop-Backend.ps1
  Test-Jdbc.ps1
  Test-Http.ps1
  Test-App.ps1
docs/
  database-setup.md
  phase2-verification.md
```

项目内额外创建了忽略跟踪的 Android Studio 本机导入配置及 `.gradle/config.properties`，
仅为客户端配置本机 JDK 17；原根目录 `.idea` 未由开发脚本覆盖。
SDK 环境修复仅新增 `D:\Android\SDK\cmdline-tools\latest`，未升级或覆盖原平台、模拟器及 ADB。
