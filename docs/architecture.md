# 架构说明

## 模块边界

后端是单个 Spring Boot 部署单元，按领域包保持边界：`identity`、`organization`、`masterdata`、`configuration`、`commission`、`sample`、`task`、`test`、`equipment`、`nonconformance`、`report`、`archive`、`notification` 和 `audit`。

每个领域拥有自己的写模型、状态迁移和校验。台账与工作台是只读投影，不建立第二份业务事实；跨领域动作由当前事务中的明确服务调用完成。

## 数据与一致性

- 内部标识使用 UUID，时间戳以 UTC 保存、Asia/Shanghai 展示，检测数值以十进制字符串传输并由 `BigDecimal` 计算。
- 创建、导入和状态迁移接受 `Idempotency-Key`；更新携带 `expectedVersion`，冲突返回 HTTP 409。
- Flyway 只做前向迁移；已发布配置和签发文件通过新版本修订。
- 列表使用稳定游标分页，排序包含业务时间与 UUID 兜底，避免并发翻页重复或遗漏。
- 大型导入、导出和 PDF 使用 PostgreSQL 作业表与应用内 worker，依赖锁、重试和唯一键，不增加外部队列。

## 安全边界

- Spring Security 与 Spring Session JDBC 负责身份、Cookie 会话、CSRF、限流、锁定和会话撤销。
- 领域服务是权限与状态前置的最终执行点；前端禁用态只用于解释，不作为安全边界。
- 附件先进入临时区，通过扩展名、MIME、文件头、大小和结构检查后按 SHA-256 原子移动到不可变路径。
- 报告模板只允许受控区块，不执行外部 URL、脚本或任意 HTML；签章素材使用 AES-GCM 加密。

## 部署

公开版默认启动 PostgreSQL 与应用两个容器，便于本地体验。Nginx 与 Prometheus/Grafana 使用 Compose profile 按需启用。内部生产部署还需要正式域名、CA、备份目标、密钥管理与恢复演练，这些内容不在公开仓库中。
