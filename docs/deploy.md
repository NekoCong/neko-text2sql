# 部署与恢复手册（云服务器全栈 Docker）

> 这份文档是写给**下一个来接手的人或 AI** 的。设计原则只有一条：
> **恢复所需的一切都在这个 git 仓库里，不依赖服务器上的任何状态。**
>
> 服务器是**抢占式实例**，随时可能被回收并重开成一台空机器。所以"恢复"不是一个特殊流程，
> 它就是本文档的正常流程 —— 重新跑一遍即可。

---

## 1. 架构与端口暴露

```
                    ┌──────────────────────────── 公网只开 22 / 80 ───────────────────────────┐
浏览器 ──80──▶ frontend (nginx:alpine)                                                       │
                    │  /            → 静态 SPA                                                  │
                    │  /api/        → proxy_pass http://backend:8080  （含 SSE，关缓冲）        │
                    ▼                                                                          │
                backend (eclipse-temurin:25-jre-alpine)  ── R2DBC ──▶ mysql:8.4               │
                    │                                                      127.0.0.1:3306       │
                    └── OTLP ──▶ otel-lgtm (Grafana/Tempo/Prometheus/Loki)                    │
                                 127.0.0.1:3000 / 4317 / 4318 / 9090                           │
                    └──────────────────────────────────────────────────────────────────────────┘
```

| 服务 | 镜像 | 端口 | 对公网 |
| --- | --- | --- | --- |
| `frontend` | `nginx:alpine` | `80` | ✅ 页面入口 |
| `backend` | `eclipse-temurin:25-jre-alpine` | `8080`（仅容器网络） | ❌ |
| `mysql` | `mysql:8.4` | `127.0.0.1:3306` | ❌ |
| `pgvector` | `pgvector/pgvector:pg16` | `127.0.0.1:5433` | ❌ |
| `minio` | `minio/minio` | `9000`（S3 API）、`127.0.0.1:9001`（控制台） | ⚠️ 仅 9000 |
| `otel-lgtm` | `grafana/otel-lgtm:0.22.0` | `127.0.0.1:3000/4317/4318/9090` | ❌ |

> **为什么 MinIO 的 9000 要对公网开放**：原文预览用的是**预签名 URL**，签名覆盖 host，
> 浏览器拿到的必须是可以直连的地址。桶本身私有，匿名列举/读取都会被拒绝，只有 10 分钟有效期的
> 预签名 GET 能访问。控制台（9001）仍只绑 `127.0.0.1`。
> 如果不想开放 9000，可把 `KNOWLEDGE_MINIO_PUBLIC_ENDPOINT` 指向一个已反代 MinIO 的域名/端口。

前端镜像里**没有写死任何 IP**：`src/api/http.ts` 的 `baseURL` 是 `/api`，由 nginx 同源反代。
所以实例换 IP、换域名都不需要重新构建。

---

## 1.1 镜像源（重要 —— 大陆网络必读）

本部署面向**中国大陆网络**。实测该环境下 Docker Hub 完全不可用：

| 目标 | 结果 |
| --- | --- |
| `registry-1.docker.io:443` | ❌ 不可达 |
| `auth.docker.io:443` | ❌ 不可达 |
| `https://get.docker.com` | ❌ 拉取失败 |
| `github.com` / `mirrors.aliyun.com` / `mirrors.cloud.aliyuncs.com` | ✅ 可用 |

因此：

- **装 Docker 不走 `get.docker.com`**，改用发行版自带包：`docker.io` + `docker-compose-v2`（Ubuntu 26.04 上实测为 Docker 29.1.3 / compose 2.40.3，BuildKit 齐全）
- **拉镜像全部走华为云 SWR 镜像站**（映射查询：<https://docker.aityp.com/>），具体地址由 `deploy/.env` 的变量注入，Dockerfile 里用 `ARG` 接住，不写死

实测可用地址（均已验证返回 HTTP 200）：

| 用途 | `.env` 变量 | 地址 |
| --- | --- | --- |
| 应用数据库 | `MYSQL_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/mysql:8.4` |
| 可观测平台 | `OTEL_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/grafana/otel-lgtm:0.22.0` |
| Maven 构建 | `MAVEN_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.9.16-eclipse-temurin-25-noble` |
| JRE 运行 | `JRE_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/eclipse-temurin:25-jre-alpine` |
| Node 构建 | `NODE_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/node:22-alpine` |
| Nginx 运行 | `NGINX_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/nginx:alpine` |
| 向量库 | `PGVECTOR_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/pgvector/pgvector:pg16` |
| 对象存储 | `MINIO_IMAGE` | `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/minio/minio:RELEASE.2025-07-23T15-54-02Z` |

⚠️ 两个**实测得出**的坑，不要凭"规律"去猜：

1. **`maven` 不能加 `library/` 前缀** —— `.../docker.io/library/maven:...` 返回 404，`.../docker.io/maven:...` 才是 200；而 `eclipse-temurin` 恰好相反，**必须**带 `library/`
2. **`grafana/otel-lgtm` 只有 `0.22.0`，没有 `latest`** —— 所以 compose 里钉的是 `0.22.0`

> 换到能直连 Docker Hub 的网络时，把 `deploy/.env` 里这 6 个变量删掉即可 —— compose 的默认值就是标准 Docker Hub 引用。

---

## 2. 前置条件

### 2.1 实例规格

| 项 | 建议 |
| --- | --- |
| vCPU / 内存 | **≥ 4 vCPU / 8 GB**（`otel-lgtm` 本身就不轻，且首次要在服务器上跑 Maven + Vite 构建） |
| 最低可用 | 2 vCPU / 4 GB，但首次构建会很慢，且可能 OOM |
| 磁盘 | ≥ 40 GB（镜像 + Maven/Node 依赖缓存） |
| 系统 | Ubuntu 22.04+ / Debian 12+ / RHEL 系（脚本会自适应包管理器） |

> 内存不够时的替代方案：本地构建镜像后 `docker save | ssh ... docker load`，或在服务器上临时
> 加大 swap。但注意**本机没有装 Docker**，所以本地构建这条路目前走不通。

### 2.2 安全组（必须）

入方向**只放行**：

- `22/tcp`（SSH）
- `80/tcp`（HTTP）
- `9000/tcp`（MinIO S3 API，预签名原文预览需要；见 §1 的说明）

**不要**放行 `3306`、`5433`、`3000`、`3001`、`4317`、`4318`、`9090`、`2375`、`2376`。

需要 Grafana / 直连 MySQL 时用 SSH 隧道：

```bash
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 3306:127.0.0.1:3306 <user>@<host>
# 然后本地浏览器打开 http://localhost:3000
```

### 2.3 代码怎么上服务器

仓库远端：`https://github.com/NekoCong/text2sql-demo.git` —— **public**，匿名克隆即可：

```bash
git clone https://github.com/NekoCong/text2sql-demo.git /root/text2sql-demo
```

服务器上现在是真正的 git checkout，日常更新就是：

```bash
cd /root/text2sql-demo && git pull
```

> **历史说明（否则会误判）**：首次部署时这个仓库是私有的，服务器没有凭据，`git clone` 会报
> `fatal: could not read Username for 'https://github.com'`，当时是用打包传输绕过的。
> 之后仓库已改为 public，代码树也换成了真正的 checkout。
>
> 如果以后又改回私有，两条备选路：配只读 Deploy Key；或继续打包传输 ——
> 本机 `tar -czf repo.tgz --exclude=.git ...` → `scp` → 服务器 `tar -xzf`。
> 重新解包时**不要** `rm -rf` 整个目录，否则会连 `deploy/.env`（密钥）一起删掉。

---

## 3. 从零部署

```bash
# 1) 拉代码（仓库是 public）
sudo apt-get update && sudo apt-get install -y git    # RHEL 系用 dnf
git clone https://github.com/NekoCong/text2sql-demo.git /root/text2sql-demo
cd /root/text2sql-demo

# 2) 准备密钥
cp deploy/.env.example deploy/.env
# 生成一个强 JWT 密钥：
openssl rand -base64 48
# 编辑 deploy/.env，至少填好：
#   MYSQL_ROOT_PASSWORD / TEXT2SQL_JWT_SECRET
#   KNOWLEDGE_PGVECTOR_PASSWORD / MINIO_ROOT_PASSWORD
#   KNOWLEDGE_MINIO_PUBLIC_ENDPOINT=http://<服务器公网IP>:9000
# DASHSCOPE_API_KEY 可留空（留空则登录/会话管理可用、知识库走词法检索，但 LLM 问答不可用）
# MINERU_TOKEN 可留空（留空则 PDF/图片解析报可读错误，Office/文本/网页仍可用）

# 3) 一条命令拉起全部
sudo bash deploy/bootstrap.sh
```

`bootstrap.sh` 做的事，按顺序：

1. 装基础包（`ca-certificates` / `curl` / `git`）
2. 装 Docker Engine + compose plugin（**系统源**：`docker.io` + `docker-compose-v2`；不用 `get.docker.com`，见 §1.1）
3. 写 `/etc/docker/daemon.json`：日志轮转 10MB×3；**故意不配 `hosts`，守护进程不监听 TCP**
4. `ufw` 只放行 22 / 80
5. 校验 `deploy/.env`：缺 `MYSQL_ROOT_PASSWORD` 或 `TEXT2SQL_JWT_SECRET`、或 JWT 密钥短于 32 字符 → **直接退出**，绝不写默认值
6. `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`
7. 轮询 `http://127.0.0.1/api/text2sql/health` 直到就绪（最多 5 分钟），失败则打印排查命令
8. 打印访问地址与账号

脚本**幂等**：重复执行安全，这正是实例被回收后的恢复方式。

---

## 4. 验证清单

```bash
# 容器状态：mysql 必须是 healthy，其余 running
docker compose -f deploy/docker-compose.prod.yml ps

# 只应看到 22 / 80 对公网监听（3306 等只能看到 127.0.0.1）
sudo ss -lntp

# 后端存活
curl -s http://127.0.0.1/api/text2sql/health          # → OK

# 知识库依赖
docker compose -f deploy/docker-compose.prod.yml ps   # pgvector / minio 应为 healthy
docker compose -f deploy/docker-compose.prod.yml exec -T pgvector \
  psql -U postgres -d text2sql -c "select extversion from pg_extension where extname='vector'"
curl -sI http://127.0.0.1:9000/minio/health/live | head -1   # → HTTP/1.1 200 OK

# 前端
curl -sI http://127.0.0.1/ | head -1                  # → HTTP/1.1 200 OK

# 种子数据 + 角色是否真的生效（用 student01 登录）
curl -s -X POST http://127.0.0.1/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"student01","password":"student123"}'
# 响应里应含 "role":"student"；用返回的 token 调 /api/text2sql/datasources 只应看到 campus
```

浏览器端到端：

1. `http://<服务器IP>/` → 用 `admin/admin123` 登录
2. 左侧会话列表应看到 **5 条**（student01 2 条 + student02 1 条 + counselor01 1 条 + admin 1 条）
3. 退出，用 `student01/student123` 登录 → 只看到 **2 条**；提问「我的成绩」只得到本人数据
4. 用 `student01` 提问「全校平均分」→ 只基于本人数据回答，并说明数据范围
5. 退出，`counselor01/counselor123` → 可查询 1 班与 2 班数据，看不到 3 班
6. 管理员打开「数据源权限」页 → 勾选/撤销学生或辅导员的 `campus` 授权，下一次请求立即生效
7. 管理员打开「知识库」页（顶部导航）→ 元数据分栏点「元数据同步」（应出现 5 条表条目）→
   业务/智能体分栏上传一个 docx 或导入一个网页 → 状态变为「就绪」→ 用「检索调试」查看候选表与
   引用 → 回到对话提问，回答下方出现引用角标，点击可打开原文/文本快照并高亮命中位置

---

## 5. 抢占式实例被回收后怎么恢复

**这是常规操作，不是应急。**

```bash
# 新机器上（IP 可能已经变了）
git clone https://github.com/NekoCong/text2sql-demo.git /root/text2sql-demo
cd /root/text2sql-demo
cp deploy/.env.example deploy/.env   # 重新填密钥
sudo bash deploy/bootstrap.sh
```

关于数据：**MySQL、pgvector 与 MinIO 的数据都不持久化，会被一起丢掉**。这是有意为之：

- 应用库的所有数据都由 `db/app-schema.sql` + `db/app-data.sql` 在 MySQL 容器**首次初始化**时重建
- 演示目标库（`campus`）在**同一个 MySQL 实例**里，是独立的 schema，首次连接时由
  `createDatabaseIfNotExist=true` 自动创建；后端每次启动都会用 `db/schema.sql` + `db/data.sql`
  重建并重灌它（`text2sql.datasources.items[].seed: true`），所以演示数据永远是已知状态
- 连接参数使用 `CAMPUS_DB_JDBC_URL` / `CAMPUS_DB_USERNAME` / `CAMPUS_DB_PASSWORD`
- 知识库的元数据条目可在恢复后重新「元数据同步」生成；上传的文档与网页快照需要重新上传
  （演示场景可接受，真要保留就把 `pgvector-data` / `minio-data` 挂到独立云盘）
- 所以恢复后立刻就是可演示状态，不需要备份恢复流程

如果以后需要保留真实数据，再引入独立云盘挂载 `/var/lib/mysql`，或定期 `mysqldump`。

> ⚠️ 实例重开后**公网 IP 可能变化**（本次已确认不绑弹性公网 IP）。前端同源反代不受影响，
> 但书签和外部引用会失效。需要固定入口时再绑弹性公网 IP。

---

## 6. 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `bootstrap.sh` 报 `These keys are empty` | `deploy/.env` 没填 | 填好 `MYSQL_ROOT_PASSWORD` / `TEXT2SQL_JWT_SECRET` |
| backend 启动即退出，日志有 `R2dbcPermissionDeniedException` | **给 R2DBC URL 加了 `sslMode=DISABLED`** | 去掉它。MySQL 8.4 的 root 用 `caching_sha2_password`，`r2dbc-mysql` 默认 `sslMode=PREFERRED` 靠 TLS 完成握手；r2dbc-mysql **没有** `allowPublicKeyRetrieval` 选项，禁用 TLS 就必然认证失败 |
| backend 连不上 mysql | mysql 还没健康 | compose 里已配 `depends_on: condition: service_healthy`；若仍失败看 `docker compose logs mysql` |
| 首次构建 OOM / 卡死 | 内存不足 | 升配，或临时加 swap，或先只 `up -d mysql otel-lgtm`，再单独 build |
| 登录返回 401 但账号密码没错 | `TEXT2SQL_JWT_SECRET` 被改过，旧 token 失效 | 重新登录；密钥变更会使所有既有会话失效 |
| 页面能开但接口 502 | backend 未就绪或崩了 | `docker compose -f deploy/docker-compose.prod.yml logs --tail=200 backend` |
| 提问时 SSE 中途断流 | nginx 缓冲/超时 | `nginx.conf` 里已设 `proxy_buffering off; proxy_read_timeout 3600s;`，确认容器用的是仓库里的这份配置 |
| 种子数据没进库 | MySQL 卷已存在，init 脚本不会再跑 | `docker compose -f deploy/docker-compose.prod.yml down -v` 再 up（**会删库**） |
| 改了 `app-schema.sql` 但表结构没变 | init 脚本只在空库时执行 | 新列/索引同时写进 `deploy/app-migrate.sql`（幂等，`bootstrap.sh` 每次部署都会执行）；或 `down -v` 重建 |
| `docker pull` 卡住 / `TIMEOUT` / `no such host` | Docker Hub 在大陆被封（见 §1.1） | 确认 `deploy/.env` 里 6 个 `*_IMAGE` 变量都指向华为云 SWR 且不为空 |
| 构建报 `docker.io/library/maven:... : not found` | 给 maven 加了 `library/` 前缀 | maven 用 `.../docker.io/maven:...`（不带 library），见 §1.1 |
| `docker compose` 不存在 | 只装了 `docker.io` 没装插件 | `apt-get install -y docker-compose-v2` |

---

## 7. 本地开发时的一个坑

本机 `JAVA_HOME` 指向 JDK 11，而项目要求 Java 25，直接 `mvnw` 会报
`不支持发行版本 25`。本机已装 JDK 25，构建时指定即可：

```powershell
$env:JAVA_HOME = "C:\Users\14364\.jdks\corretto-25.0.4.1"
.\mvnw.cmd test
```

Docker 构建不受影响（镜像里自带 JDK 25）。

---

## 8. 相关文件

| 文件 | 作用 |
| --- | --- |
| `deploy/bootstrap.sh` | 幂等的服务器初始化脚本 |
| `deploy/docker-compose.prod.yml` | 全栈编排（MySQL + pgvector + MinIO + otel + backend + frontend） |
| `deploy/.env.example` | 密钥 + 镜像地址模板；`deploy/.env` 已 git-ignore |
| `text2sql-backend/Dockerfile` | 多阶段：`maven:3.9-eclipse-temurin-25` 构建 → `eclipse-temurin:25-jre-alpine` 运行（非 root） |
| `text2sql-frontend/Dockerfile` | `node:22-alpine` 构建 → `nginx:alpine` 托管 |
| `text2sql-frontend/nginx.conf` | SPA 回退 + `/api` 反代 + SSE 关缓冲 |
| `docker-compose.yml` | **本地**只用基础设施（MySQL + otel-lgtm），不含应用容器 |
| `text2sql-backend/src/main/resources/db/app-data.sql` | 种子数据：admin / student01-03 / counselor01 + 数据范围 + 会话 |

---

## 9. 本次部署记录（2026-10-23）

留作对照：下次恢复后应当看到同样的结果。

| 项 | 值 |
| --- | --- |
| 主机 | 阿里云 ECS，Ubuntu 26.04.1 LTS，8 vCPU / 30 GB / 49 GB |
| Docker | 29.1.3（Ubuntu 系统包 `docker.io`） |
| Docker Compose | 2.40.3（`docker-compose-v2`） |
| 代码 | `git clone`（仓库已改为 public，`git pull` 可用） |
| 镜像 | 全部来自华为云 SWR（见 §1.1） |
| 演示数据源 | `campus`（MySQL schema `campus`，与 app 库同实例，自动创建 + 启动时重灌） |
| MySQL root 密码 | `root`（`deploy/.env`，mode 600） |
| JWT 密钥 | 随机生成，未记录、无需人工持有 |

验收结果（全部通过）：

```
容器          4/4 running，restart 计数均为 0
公网监听      仅 22 与 80；3306/3000/4317/4318/9090 均绑定 127.0.0.1
ufw           active，仅放行 22/tcp、80/tcp
外网访问      http://<服务器IP>/                       -> 200
              http://<服务器IP>/api/text2sql/health     -> 200 OK
未带 token    GET /api/auth/me                 -> 401
种子账号      admin       role=admin      可见会话 5 条
              student01   role=student    可见会话 2 条
              student02   role=student    可见会话 1 条
              counselor01 role=counselor  可见会话 1 条
数据范围      student01 -> student02 的会话 -> 404（拒绝）
              admin -> student02 的会话 -> 200（放行）
行级权限      student01 查询 -> SQL 自动带 student_no/student_id 过滤
数据源权限    撤销 student 的 campus 授权 -> 下一次查询被拒绝（新增能力，待部署环境复跑确认）
```

> 「行级权限」「数据源权限」两行属于本分支新增能力，当前仅在本地测试环境验证；部署后请按 §4 的浏览器步骤复跑确认。

> 已知小问题：`bootstrap.sh` 结尾用 `api.ipify.org` 探测公网 IP，但该域名在本网络不可达，
> 所以最后打印的是 `http://<server-ip>/` 占位符。不影响部署，实际地址见上表。
