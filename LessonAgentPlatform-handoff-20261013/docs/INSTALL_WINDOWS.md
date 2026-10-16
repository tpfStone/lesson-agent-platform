# Windows 安装与启动

本阶段按 Windows、每模块单实例完成开发验收。结果统一见 [验收总报告](ACCEPTANCE_RESULTS_20261017.md)；可选本机入口、功能开关、超时、升级与回退见 [Windows 开发运行与维护](WINDOWS_DEVELOPMENT_OPERATIONS.md)。本机入口不表示已开放互联网访问。

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

请在当前策略允许执行本地脚本的 Windows PowerShell 环境运行这些命令。脚本及子进程遵守有效执行策略，不修改策略、不传入 Bypass；若被组织或系统策略阻止，应由环境负责人提供允许执行的验收环境。可用 `Get-ExecutionPolicy -List` 记录实际策略。

```powershell
powershell -NoProfile -File .\scripts\start-all.ps1
```

浏览器打开：

```text
http://127.0.0.1:5177
```

检查：

```powershell
powershell -NoProfile -File .\scripts\health-check.ps1
```

停止：

```powershell
powershell -NoProfile -File .\scripts\stop-all.ps1
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

## 12. 已有开发环境的维护

首次安装后，不要再次复制 `.env.example` 覆盖已填写的 `.env`。四个 Python 模块使用各自虚拟环境；SOCKS 代理依赖已经写入模块依赖声明，无需依靠临时全局安装。Windows 日志需保留 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`PYTHONUNBUFFERED=1`。

若 MySQL 运行于 Docker，先启动 Docker Desktop，再按 `.env` 中的数据库端口核对并启动对应容器。容器名和端口不是项目固定配置，不要误用其他已有数据库或删除数据卷。

PDF 转换器通过 `platform.pdf.soffice` 配置本机 LibreOffice 路径。`.runtime` 可能包含转换工具、配置、日志、PID 和本机验收账号，不能整体删除或公开上传。迁移到其他目录/机器时重建 `.venv` 并更新本机绝对路径，不直接复制虚拟环境。

10 月 17 日验收临时服务已清理；当前运行状态以健康检查和端口检查为准。历史环境与验收结果已合并到 [验收总报告](ACCEPTANCE_RESULTS_20261017.md)，日常操作统一见 [维护说明](WINDOWS_DEVELOPMENT_OPERATIONS.md)。

