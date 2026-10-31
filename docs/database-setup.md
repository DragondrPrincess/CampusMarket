# 阶段二数据库初始化（Windows）

在普通本机 PowerShell 中运行，不要在聊天中提供密码，不要启用终端录制：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\FQH_Programs\CampusMarket\scripts\Configure-Database.ps1
```

管理员用户名直接回车使用 `root`，密码在隐藏提示处输入。脚本固定通过 TCP 连接
`127.0.0.1:3306`，使用 `D:\MySQL\Server\bin\mysql.exe`。管理员凭据不保存。

## 验证与结果

脚本依次验证管理员连接，创建不存在的 `campus_market` 数据库及 `app_metadata` 表，
仅在 `id=1` 不存在时插入项目元数据。随后创建随机名称的本项目业务账号，授予
`campus_market.*` 上的 SELECT、INSERT、UPDATE、DELETE 权限。

最后实际使用业务账号重新连接，验证数据库、元数据记录、localhost 账号匹配、四项
DML 权限和没有全局权限，并打印数据库版本、账号名称、项目名称及数据库时间。
不输出密码、密码哈希、原始 CREATE USER 语句或可能包含密码的服务器错误片段。

只有全部验证通过后，才打印 `SUCCESS`，并将业务凭据保存为
`.local/db.credentials.xml`。该文件使用 Windows DPAPI 加密，只能由本机当前
Windows 用户解密；`.local/` 已被 `.gitignore` 忽略。不要分享或提交该文件。

## 重复运行与失败恢复

- 成功后重复执行同一命令：只做业务账号和数据的只读验证，不执行迁移或 GRANT。
- 初始化中断：保留 `.local/db.credentials.pending.xml`，再次运行同一命令并输入
  管理员密码，会复用同一账号和密码恢复。不要删除待完成凭据文件。
- 已存在账号时先验证保存的密码可以登录；不会执行 ALTER USER、DROP USER 或重置密码。
- 数据库 DDL 会隐式提交，失败不代表此前所有步骤已回滚。脚本保留已完成的步骤，
  采用条件建库、建表和条件插入恢复，不删除数据库、表或已有记录。
- 本机同时只允许一个初始化进程运行。
- 如遇 `ERROR xxxx (SQLSTATE)`，可以提供步骤名称、错误代码和安全提示；无需提供密码。
- 更严格的自定义密码策略可能拒绝随机密码，脚本会报告 1819 并保留待完成凭据；
  此时停止操作并诊断，不修改服务器密码策略或自动重置账号。

`-UseExistingAccount` 仅用于已有、权限正确的业务账号：验证通过后保存凭据，不建库、
不建表、不修改授权，拒绝 root。正常首次初始化无需此参数。

## 无数据库修改的自检

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\FQH_Programs\CampusMarket\scripts\Configure-Database.ps1 -CheckOnly
```

此模式只运行客户端版本检查和错误脱敏自检，不连接数据库或要求密码。

客户端使用当前子进程的临时环境传递连接密码，退出时清除进程启动信息中的密码；
密码不进入命令行参数、源码或临时明文文件。通过 `MYSQL_TEST_LOGIN_FILE` 指向
不存在的随机路径，隔离个人 `.mylogin.cnf`，避免其覆盖连接凭据；不修改个人配置。
