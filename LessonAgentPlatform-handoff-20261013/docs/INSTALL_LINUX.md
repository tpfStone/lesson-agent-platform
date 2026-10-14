# Linux 服务器安装与启动（Ubuntu 示例）

本说明采用“同机部署”：F1–F4、Spring Boot、MySQL 和 Nginx 位于同一台服务器。后端只监听回环地址，Nginx 是唯一公网入口。

## 1. 基础软件

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip openjdk-17-jdk mysql-server nginx curl unzip
```

安装 Node.js 20/22 LTS。安装后确认：

```bash
python3 --version
java -version
node --version
npm --version
mysql --version
```

## 2. 放置源码

示例位置：

```bash
sudo mkdir -p /opt/lesson-agent
sudo chown -R "$USER":"$USER" /opt/lesson-agent
cd /opt/lesson-agent
```

把发布包内容解压到该目录。不要上传 `.env`、本地数据库和用户教案到 GitHub。

## 3. 创建 MySQL 数据库

```bash
sudo mysql
```

```sql
CREATE DATABASE IF NOT EXISTS lesoongen
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'lesoongen'@'localhost'
  IDENTIFIED BY '请替换为强密码';

GRANT ALL PRIVILEGES ON lesoongen.* TO 'lesoongen'@'localhost';
FLUSH PRIVILEGES;
```

## 4. 配置

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

至少填写三个模型 Key、`ENGINE_INTERNAL_TOKEN` 和 `DB_PASSWORD`。使用域名时，把：

```text
FRONTEND_ORIGIN=https://你的域名
```

## 5. 安装四个 Python 环境

F1：

```bash
cd /opt/lesson-agent/System-v1.2
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -c "import platform_api; print('F1_IMPORT_OK')"
```

F2 Engine：

```bash
cd /opt/lesson-agent/Lessongen-main/paper4_pipeline
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -e ".[web,docx]"
.venv/bin/python -c "import paper4_pipeline; print('F2_ENGINE_IMPORT_OK')"
```

F3：

```bash
cd /opt/lesson-agent/SimClass-main
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -c "import app.api.classroom_api; print('F3_IMPORT_OK')"
```

F4：

```bash
cd /opt/lesson-agent/NoviceTeacher-AI-main/backend
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m alembic -c alembic.ini upgrade head
.venv/bin/python -c "import app.api; print('F4_IMPORT_OK')"
```

## 6. 构建 Java 和 Vue

```bash
cd /opt/lesson-agent/Lessongen-main/web
chmod +x mvnw
./mvnw test
./mvnw clean package

cd frontend
npm ci
npm run build
```

## 7. 首次启动检查

```bash
cd /opt/lesson-agent
chmod +x scripts/*.sh Lessongen-main/web/mvnw
./scripts/start-all.sh
./scripts/health-check.sh
```

日志：

```bash
ls -la .runtime/logs
tail -n 100 .runtime/logs/spring.log
```

停止：

```bash
./scripts/stop-all.sh
```

## 8. Nginx

先构建 Vue，然后复制示例：

```bash
sudo cp deploy/nginx/lesson-agent.conf.example /etc/nginx/sites-available/lesson-agent
sudo ln -s /etc/nginx/sites-available/lesson-agent /etc/nginx/sites-enabled/lesson-agent
sudo nginx -t
sudo systemctl reload nginx
```

编辑配置中的：

```text
server_name
root
```

Nginx 托管前端时，只启动后端：

```bash
./scripts/start-all.sh --skip-frontend
```

正式公开前应配置 HTTPS、防火墙和定期备份。建议使用 systemd 托管各服务；本包的脚本主要用于首次部署和验收。

## 9. 防火墙

公网只开放：

```text
80/tcp
443/tcp
```

不要开放：

```text
8000-8003
8080
3306
```

