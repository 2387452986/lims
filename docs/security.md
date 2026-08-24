# 安全基线与验证

## 应用控制

- HttpOnly、Secure、SameSite=Strict 会话 Cookie；CSRF Cookie/Header 双提交；会话版本可立即撤销。
- BCrypt 12 密码哈希、强密码策略、连续失败锁定、登录限频、签发/签章 5 分钟内密码复核。
- CSP、`frame-ancestors 'none'`、无基本认证、无表单登录、无外部模板 URL/脚本。
- 菜单、动作、数据范围、导出、下载、审核、签发和签章均由后端强制检查。
- 签名/印章 AES-GCM 加密保存；签发文件和已发布配置不可原地修改。
- 上传只允许白名单类型，同时检查扩展名、MIME、文件头、大小和安全解析；禁止宏、可执行文件和压缩包。
- 不可变 SHA-256 文件路径、下载审计和可见水印导出。

## 容器与运行时

- 应用镜像使用 UID 10001，生产容器只读根文件系统、`no-new-privileges`、丢弃全部 Linux capabilities。
- Alpine 安装 Chromium 前先执行安全更新，修复已确认的 libexpat 与 p11-kit 漏洞。
- Nginx 统一 HTTPS 交付；应用、PostgreSQL、Prometheus/Grafana 只在 Compose 网络中可达。

## 本地验证证据（2026-08-19）

- `npm ci` / `npm audit` 解析 145 个包，0 个已知 npm 漏洞。
- Trivy 0.74.0 源码 vulnerability + misconfiguration + secret 扫描：0 发现；CI 将 `HOME` 对齐到项目目录，复用已解析的 Maven 缓存，避免外部仓库限流造成伪失败。
- Trivy Java 扫描先识别出 pgJDBC 42.7.11 的 CVE-2026-54291；固定到 42.7.12 后，最终镜像 Alpine OS 与 Java 依赖 HIGH/CRITICAL + `ignore-unfixed` 复扫均为 0。
- Trivy 最终镜像 secret 扫描：0 发现；CycloneDX 1.6 SBOM 已生成 JSON 与 XML 制品。
- 镜像运行验证：UID 10001、Java 21.0.11 LTS、Chromium 149 可执行。
- 前端依赖、Java SBOM、源码和镜像完整扫描均作为 GitLab 的独立失败门禁；生产 `1.x.x` 标签只能从保护标签人工批准。

## 发布前必须复验

1. 在保护标签 CI 中使用全新扫描缓存重复执行 OS、Java 依赖与镜像秘密扫描，并保存 JSON 结果及 SBOM。
2. 以生产配置执行跨角色越权、CSRF、上传绕过、SQL/XSS 和默认账号检查。
3. 校验镜像摘要、SBOM、CA 链、证书 SAN/到期日和秘密管理器注入。
