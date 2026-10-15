# Windows 安装与启动

2026-10-14 修复后的迁移、PDF 配置和验收限制见 [修复实施结果](REPAIR_RESULTS_20261014.md)。Node 固定为根目录 `.nvmrc` 中的 22.19.0。

以下命令以项目位于：

```text
C:\Users\你的用户名\Desktop\LessonAgentPlatform-Release
```

为例。项目不依赖 Docker。

## 1. 安装基础软件

确认命令可用：

```powershell
python --version
java -version
node --version
npm --version
git --version
Get-Service MySQL80
```

推荐 Python 3.11/3.12、Java 17+、Node 20/22 LTS、MySQL 8。

## 2. 创建 MySQL 数据库

登录 MySQL：

```powershell
mysql -u root -p
```

执行：

```sql
CREATE DATABASE IF NOT EXISTS lesoongen
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'lesoongen'@'localhost'
  IDENTIFIED BY '请替换为强密码';

GRANT ALL PRIVILEGES ON lesoongen.* TO 'lesoongen'@'localhost';
FLUSH PRIVILEGES;
```

如果用户已经存在，只需确认密码和授权，不要删除已有数据库。

## 3. 配置环境变量

在项目根目录：

```powershell
Copy-Item .\.env.example .\.env
notepad .\.env
```

至少替换：

```text
DEEPSEEK_API_KEY
API_KEY
LLM_API_KEY
ENGINE_INTERNAL_TOKEN
DB_PASSWORD
```

三个模型密钥通常填写同一个 DeepSeek Key。不要把 `.env` 上传 GitHub。

生成随机 Engine Token：

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
```

## 4. 安装 F1

```powershell
cd .\System-v1.2
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -c "import platform_api; print('F1_IMPORT_OK')"
cd ..
```

必须输出 `F1_IMPORT_OK`。

## 5. 安装 F2 Python Engine

```powershell
cd .\Lessongen-main\paper4_pipeline
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".[web,docx]"
.\.venv\Scripts\python.exe -c "import paper4_pipeline; print('F2_ENGINE_IMPORT_OK')"
cd ..\..
```

## 6. 安装 F3

```powershell
cd .\SimClass-main
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -c "import app.api.classroom_api; print('F3_IMPORT_OK')"
cd ..
```

## 7. 安装 F4 并迁移数据库

```powershell
cd .\NoviceTeacher-AI-main\backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m alembic -c alembic.ini upgrade head
.\.venv\Scripts\python.exe -c "import app.api; print('F4_IMPORT_OK')"
cd ..\..
```

## 8. 构建 Java

```powershell
cd .\Lessongen-main\web
.\mvnw.cmd test
.\mvnw.cmd clean package
cd ..\..
```

要求 `BUILD SUCCESS`。

## 9. 安装并构建 Vue

```powershell
cd .\Lessongen-main\web\frontend
npm ci
npm run build
cd ..\..\..
```

要求看到 `built`，并确认：

```powershell
Test-Path .\Lessongen-main\web\frontend\dist\index.html
```

返回 `True`。

## 10. 启动、检查、停止

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
```

浏览器打开：

```text
http://127.0.0.1:5177
```

检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\health-check.ps1
```

停止：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1
```

## 11. 故障排查

查看端口：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -in 8000,8001,8002,8003,8080,5177 |
  Select-Object LocalPort,OwningProcess
```

查看日志：

```powershell
Get-ChildItem .\.runtime\logs
Get-Content .\.runtime\logs\spring.err.log -Tail 100
```

不要通过删除 MySQL 数据库或 Flyway 历史表解决 migration 错误。

## 12. 本机已准备环境（2026-10-15）

当前机器已完成安装，项目实际位置为 `D:\projects\LessonAgentPlatform\LessonAgentPlatform-handoff-20261013`。四个 Python 模块使用各自 Python 3.11.15 `.venv`；Java JAR 和前端构建已具备。不要再次复制 `.env.example` 覆盖已填写的 `.env`。

准备完成时六个服务保持运行，打开 `http://127.0.0.1:5177`，在页面注册自己的账号即可使用。2026-10-15 已完成最小生成和四模块真实业务试跑；F1 评价、F4 一轮共析写回已有通过证据，F2 返回需要人工复核且未达到质量门槛，F3 课堂完成、问题分析曾分段超时后已恢复为 `COMPLETED`。完整结论以 [验收报告](ACCEPTANCE_RESULTS_20261015.md) 的最新记录为准。本地健康检查本身不代表业务验收通过。

真实业务测试补齐了 SOCKS 代理依赖：F1 的 `socksio`、F2/F3/F4 的 `httpx[socks]` 已写入依赖声明；F2 的基础运行依赖和 `requirements.txt` 均覆盖，不依赖安装 `dev` extra。本机四个虚拟环境已补装并通过无网络客户端初始化检查。

Windows 重定向 Python 日志时，GBK 不能编码 emoji，可能让课堂线程中断。根 `.env` 和 `.env.example` 已设置 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`PYTHONUNBUFFERED=1`；已有环境需保留这三项，修改后重启服务。停止脚本若报告权限不足或进程仍存活，会返回非零并保留 PID 记录；应在有权停止该进程的终端重试，不手动删除记录冒充停止成功。

日后重新启动，先开启 Docker Desktop，再执行：

```powershell
cd D:\projects\LessonAgentPlatform\LessonAgentPlatform-handoff-20261013
docker start lessonagent-local-mysql
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
```

检查及停止应用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\health-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1
```

停止数据库可执行 `docker stop lessonagent-local-mysql`，保留数据卷 `lessonagent-local-mysql-data`。数据库仅在 `127.0.0.1:3308` 提供服务，原有 `3307` 数据库独立保留。

PDF 转换器位于 `.runtime/tools/LibreOffice/program/soffice.exe`，路径已配置到 `.env`。`.runtime` 还保存本地数据库配置、服务日志、PID 和验收账户信息，不能整体删除或公开上传。迁移到其他目录/机器时，需重建 `.venv` 并重新配置本机绝对路径，不要直接复制本机虚拟环境。

