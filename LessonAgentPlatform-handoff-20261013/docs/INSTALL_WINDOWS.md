# Windows 安装与启动

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

