# 生产运维手册

## 1. 环境与秘密

- 开发、测试、生产必须使用独立 PostgreSQL、文件卷、证书和密钥。
- 仅将 `deploy/.env.example` 作为结构样例；`.env`、CA 私钥、签名/印章密钥、生产数据不进 Git。
- `SIGNING_ASSET_KEY` 使用 32 字节随机值的 Base64 编码；生产保持 `SIGNING_ASSET_REQUIRED=true`。
- `PERSONNEL_DATA_KEY` 使用另一份独立的 32 字节随机值 Base64 编码，用于身份证件号 AES-GCM 加密和带密钥去重摘要；不得与签章密钥复用。
- `BOOTSTRAP_ADMIN_SECRET` 只在空库初始化时提供；首次登录强制改密后立即从部署环境移除。
- 生产 HTTPS 保持 `COOKIE_SECURE=true`（默认值）。只有经授权、直接使用 HTTP 的局域网 QA 实例才设置 `COOKIE_SECURE=false`；切换到 HTTPS 前必须恢复为 `true`。

## 2. 本地开发

```sh
POSTGRES_PASSWORD='<local-secret>' docker compose -f deploy/compose.yml up -d postgres
npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend run build
DB_URL='jdbc:postgresql://127.0.0.1:55432/lims' DB_USER=lims DB_PASSWORD='<local-secret>' \
  SPRING_PROFILES_ACTIVE=dev BOOTSTRAP_ADMIN_SECRET='<one-time-secret>' DEV_SEED_PASSWORD='<role-secret>' \
  ./mvnw spring-boot:run
```

运行中的 Spring Boot 可执行 JAR 不得被 `package` 原地覆盖；打包前先停应用，打包完成后再启动。

当 CI 已产出并验证 `target/lims-0.1.0-SNAPSHOT.jar` 时，可用 `deploy/Dockerfile.runtime` 只组装运行时镜像；该入口不会绕过前端构建、Maven 测试或制品扫描门禁。

应用运行层使用 glibc 的 Debian 13、OpenJDK 21 和系统 Chromium，避免 Playwright Java 随依赖发布的 glibc 驱动在 musl 镜像中失效。镜像禁止 Playwright 下载第二份浏览器，并将运行时 HOME/cache 指向临时盘。应用以 UID 10001 和只读根文件系统运行；承载 JFR、Playwright 驱动与 Chromium 临时文件的 `/tmp` 使用有界的 1 GiB tmpfs（`rw,exec,nosuid,nodev,mode=1777`）。`exec` 是驱动启动所需，容器仍保持非 root、只读根、无 capabilities 和 `no-new-privileges`。部署冒烟必须同时检查 readiness、`/tmp/lims.jfr`、报告 PDF 作业完成和临时盘余量。

macOS + Colima 的 Testcontainers 验证命令：

```sh
DOCKER_HOST=unix:///var/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw verify
```

## 3. 必跑门禁

```sh
npm --prefix frontend run check
npm --prefix frontend run test
npm --prefix frontend run build
./mvnw verify
npm --prefix frontend run test:e2e
docker compose -f deploy/compose.yml config
LIMS_PASSWORD='<admin-secret>' deploy/scripts/performance-smoke.sh
```

本地 2026-08-23 洁净业务库只读冒烟：1,000 次工作台查询、50 并发、0 失败，P95=0.062344 秒；2,000 次、200 并发、0 失败，P95=0.031274 秒，均通过脚本默认 P95≤1 秒阈值。该结果只验证本机读取和已知工作台惊群修复，不覆盖业务写入、编号并发或固定 8 核/32 GB/NVMe 主机，不能代替生产容量签字。

OpenAPI 从已登录的 `/api/openapi` 导出到 `target/openapi.json`，然后执行 `npm --prefix frontend run openapi:lint`。

## 4. 生产部署

1. 安装内部 CA 签发的 `deploy/certs/tls.crt` 和 `tls.key`，校验 SAN 覆盖生产内网域名。
2. 将经扫描的受保护 `1.x.x` 镜像写入 `LIMS_IMAGE`，由 GitLab 人工批准生产作业。
3. `docker compose -f deploy/compose.yml --env-file <production-env> config` 必须无未解析变量。
4. `docker compose ... up -d`，等待 PostgreSQL、应用 readiness 和 Nginx HTTPS 全部正常。
5. 依次验证登录、委托查询、水印导出、PDF 作业、Prometheus 拉取和 Grafana 面板。

回滚只切回上一个经验证镜像；Flyway 只前进，数据库降版必须从成对备份恢复，禁止手工回退迁移脚本。

### 4.1 日常、证据与自动化数据边界

- 日常业务库只包含 Flyway 结构、受权配置主数据、真实机构/角色/账号和用户产生的业务；不得导入 `SHOWCASE` 账号、人员、工程、委托、质量记录或报告。
- 既有验收库切换前先生成 PostgreSQL 自定义格式备份和 SHA-256，冻结后只作为 QA 证据恢复到隔离环境，不在日常实例中按名称级联清理。
- 浏览器 E2E 使用独立数据库和文件卷，每轮可重建；运行前断言目标数据库名，运行后销毁。测试环境凭据从 Keychain/CI secret 注入，禁止进入 Git、日志或截图。
- 切换到洁净库会撤销旧会话，但不得擅自重置管理员密码。必须重新验证登录 POST、返回 Cookie 属性和随后 `/api/auth/me`；直接 HTTP 局域网验收仅在该实例显式设置 `COOKIE_SECURE=false`。

## 5. 备份、验证和恢复演练

```sh
BACKUP_DIR=/mnt/independent-backup/lims POSTGRES_PASSWORD='<secret>' deploy/scripts/backup.sh
BACKUP_PATH=/mnt/independent-backup/lims/<recovery-point> deploy/scripts/verify-backup.sh
BACKUP_PATH=/mnt/independent-backup/lims/<recovery-point> deploy/scripts/restore-rehearsal.sh
```

`backup.sh` 产生 PostgreSQL 自定义格式备份、不可变文件卷快照、SHA-256 清单和同一 recovery point，并写入 `LAST_PAIRED_BACKUP` 运维标记。每小时同步到独立 NAS/备份主机；每季度必须在空环境运行完整恢复演练并记录 RPO/RTO。

本地 2026-08-19 已从成对备份恢复到全新 PostgreSQL 18 容器，历史演练点校验 Flyway V9、739 条审计记录及文件卷解包均通过，临时数据库与文件卷均在演练后清理。V10–V13 已另行通过空库迁移、来源数量和幂等集成校验；检测配置整改后的新成对备份仍须再执行一次完整恢复演练。上述结果只证明本地脚本和备份格式可恢复；生产仍须在独立备份目标上实测 RPO≤1 小时、RTO≤4 小时。

## 6. 监控与告警

- 应用指标：健康、HTTP 错误率/P95、PostgreSQL 连接、报告作业积压/失败、登录锁定。
- 运维指标：数据库大小、文件卷字节数、最后成对备份时间、TLS 证书剩余天数。
- 规则在 `deploy/prometheus/rules/lims.yml`，面板在 `deploy/grafana/dashboards/lims-overview.json`。
- 应用输出 ECS JSON 日志，Compose 默认每服务 50 MB × 10 轮转。

## 7. 切换窗口

按 [migration.md](migration.md) 完成两次预演后，在不超过 4 小时的维护窗口内依次执行：旧系统冻结→最终导出→增量导入→对账→成对备份→部署→冒烟→业务签字→内网 DNS 切换。
