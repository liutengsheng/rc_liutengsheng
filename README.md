# API 通知系统（Notification Delivery Service）

> RightCapital 线下实现 · Java / Spring Boot

---

## 目录

1. [问题理解](#问题理解)
2. [整体架构](#整体架构)
3. [核心设计决策](#核心设计决策)
4. [数据模型](#数据模型)
5. [API 接口](#api-接口)
6. [重试策略](#重试策略)
7. [系统边界](#系统边界)
8. [取舍与演进](#取舍与演进)
9. [快速开始](#快速开始)
10. [AI 使用说明](#ai-使用说明)

---

## 问题理解

企业内部多个业务系统需要在关键事件发生时，调用各种外部供应商的 HTTP API 进行通知。这本质上是一个**异步消息投递**问题，核心挑战是投递的可靠性、业务解耦和失败处理。

---

## 整体架构

```
业务系统
  |
  v  POST /api/notifications（同步返回 202 Accepted）
  |
NotificationController -> NotificationService -> H2 Database
                                                      |
                                               定时轮询（每 5 秒）
                                                      |
                                               DeliveryWorker
                                               PENDING -> PROCESSING -> SUCCESS
                                                                    -> FAILED -> 退避重试
                                                                    -> DEAD_LETTER（超限）
                                                      |
                                               外部供应商 API
```

### 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 框架 | Spring Boot 3.2 | Java 生态标准，快速启动 |
| 数据库 | H2（文件模式） | 无需部署额外服务，clone 即可运行，开箱即用 |
| ORM | Spring Data JPA + Flyway | Schema 版本管理，生产标准实践 |
| HTTP 客户端 | OkHttp 4 | 连接池复用，性能优于 RestTemplate |
| 任务调度 | Spring @Scheduled | 无需引入额外中间件 |

---

## 核心设计决策

### 决策 1：DB 轮询而非消息队列

MVP 阶段使用数据库轮询（DB Polling）实现队列语义。DB 轮询足够可靠，不引入额外运维负担；数据库天然提供持久化，不存在 MQ 宕机导致消息丢失的风险；调试排查更简单。

**已知不足**：轮询存在延迟（默认 5 秒），**不支持实时投递**，详见系统边界章节。

### 决策 2：投递语义——至少一次（At-least-once）

外部系统应具备幂等性，重复通知可接受，丢失通知不可接受。恰好一次语义需要分布式事务，实现成本极高，不采纳。

### 决策 3：不做供应商 API 格式适配

系统只做透明代理，业务系统提交时自行构造完整的 headers 和 body。格式适配属于业务知识，应由调用方负责；在本系统做适配违反开闭原则。

### 决策 4：不做认证鉴权

假设部署在企业内网，服务间天然信任。认证应由 API Gateway 或服务网格统一处理。

---

## 数据模型

```sql
CREATE TABLE notifications (
    id            VARCHAR(36),   -- UUID 主键
    target_url    TEXT,          -- 目标地址
    http_method   VARCHAR(10),   -- HTTP 方法
    headers       TEXT,          -- 自定义请求头（JSON 字符串）
    body          TEXT,          -- 请求体（透明透传）
    status        VARCHAR(20),   -- PENDING/PROCESSING/SUCCESS/FAILED/DEAD_LETTER
    retry_count   INT,           -- 已重试次数
    max_retries   INT,           -- 最大重试次数
    next_retry_at TIMESTAMP,     -- 下次可重试时间
    last_error    TEXT,          -- 最后一次失败原因
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
```

状态流转：PENDING -> PROCESSING -> SUCCESS / FAILED -> 退避重试 / DEAD_LETTER -> 手动重试 -> PENDING

---

## API 接口

```http
# 提交通知
POST /api/notifications
{ "targetUrl": "...", "httpMethod": "POST", "headers": {...}, "body": "...", "maxRetries": 5 }
# 响应 202 Accepted: { "id": "uuid", "status": "PENDING" }

# 查询状态
GET /api/notifications/{id}

# 手动重试（针对 DEAD_LETTER）
POST /api/notifications/{id}/retry

# 健康检查
GET /api/health
```

---

## 重试策略

采用**预定义退避间隔表**，而非纯指数退避（`2^n`）：

| 第 N 次失败 | 等待时间 |
|------------|----------|
| 第 1 次 | 1 分钟 |
| 第 2 次 | 5 分钟 |
| 第 3 次 | 15 分钟 |
| 第 4 次 | 1 小时 |
| 第 5 次 | 6 小时 |
| 超过上限 | 进入 DEAD_LETTER |

上限设为 6 小时，避免通知被无限期推迟；`2^n` 在次数较多时会产生不合理的超长等待。

**宕机恢复**：服务启动时将卡在 `PROCESSING` 超过 10 分钟的记录重置为 `PENDING`，防止 Worker 崩溃后任务永久卡死。

---

## 系统边界

### 解决的问题

| 功能 | 说明 |
|------|------|
| 接收通知请求 | REST API，立即返回 202 |
| 请求持久化 | 存入数据库，服务重启不丢失 |
| 异步投递 | Worker 定时投递，不阻塞业务系统 |
| 失败重试 | 指数退避，最终进入 Dead Letter |
| 宕机恢复 | 重启后恢复 PROCESSING 状态记录 |
| 状态查询 | 业务系统可查询投递状态 |
| 手动重试 | 运维人员可对 Dead Letter 手动触发重试 |

### 明确不解决的问题

| 不解决 | 理由 |
|--------|------|
| 供应商 API 格式适配 | 避免耦合，由调用方负责 |
| 认证鉴权 | 内网部署，应由 API Gateway 处理 |
| 消息去重 | 外部系统应幂等，本系统保证「至少一次」 |
| 分布式部署 | MVP 阶段，单实例足够 |
| 实时监控大盘 | 可通过日志 + H2 Console 观测，不过度建设 |
| 速率限制/流量控制 | 当前 QPS 不高，暂不需要 |
| 实时发送（Zero-latency delivery） | 当前基于定时轮询，提交后最长有 5 秒延迟才触发投递，这是 DB 轮询的固有代价。若需毫秒级实时投递，需改造为事件驱动架构（如 MQ 消费） |

---

## 取舍与演进

### AI 建议了但我没有采纳的设计

1. **使用 PostgreSQL 替代 H2**：AI 建议使用 PostgreSQL，理由是生产级能力更强。我坚持选择 H2，因为本项目核心目的是演示设计思路和 AI 工具使用熟练度，H2 无需任何外部依赖，clone 后直接运行，大幅降低评审者运行成本。若是真实生产系统 PostgreSQL 是正确选择，但在此场景下 H2 是更务实的决策。

2. **熔断器（Circuit Breaker）**：AI 建议引入 Resilience4j 对单个供应商做熔断。对 MVP 来说过度设计，Dead Letter 机制已足够隔离长期失败的目标。

3. **消息去重 / 幂等键**：AI 建议维护 `idempotency_key` 避免重复投递。外部系统本身应该幂等，不应由中间系统弥补。

4. **分布式锁（Redis）**：AI 建议用 Redis 分布式锁防止多实例并发投递。当前单实例，`PROCESSING` 状态标记已解决并发问题，引入 Redis 是不必要的复杂度。

5. **使用 Kafka 作为消息队列**：AI 建议引入 Kafka 支持高吞吐。当前规模下是典型过度设计，DB 轮询的秒级延迟对通知场景完全可接受。

6. **死信告警（钉钉/邮件）**：AI 建议在进入 DEAD_LETTER 时发送告警。告警是运维基础设施，不应耦合在业务服务中，应由日志监控（如 ELK）实现。

### 未来演进路径

| 场景 | 演进方向 |
|------|----------|
| 高 QPS，DB 轮询成为瓶颈 | 引入 RabbitMQ / Redis Stream，Worker 改为消费 MQ |
| 需要多实例部署 | 用 MQ 消费组语义天然解决并发，替代 PROCESSING 状态锁 |
| 需要按供应商限速 | 在 Worker 层增加 per-host 的令牌桶 |
| 需要实时监控 | 接入 Prometheus + Grafana，暴露通知成功率/延迟指标 |
| 需要审计日志 | 增加 notification_events 表，记录每次状态变更 |

#### 关于分布式改造后的补偿机制

迁移到 MQ 架构后，**补偿机制不能省略**，只是形态不同：

**方案 A：MQ 死信队列（DLQ）补偿**

消费者投递失败后消息进入 Dead Letter Queue，配置 DLQ 消费者做有限次重试，超限后告警人工介入。优点是与 MQ 深度集成无需额外存储；缺点是重试策略受限于 MQ 能力，跨系统排查链路复杂。

**方案 B：保留数据库队列做补偿轮询（Outbox Pattern）**

业务事件与业务数据在同一事务写入本地 DB outbox 表，保证不丢失；独立 Relay 服务将 outbox 记录发布到 MQ；MQ 消费失败的消息回写 DB 重试队列，由本系统轮询兜底。优点是补偿逻辑统一、可观测性强；缺点是引入更多组件。

**判断**：DLQ 适合对 MQ 高度依赖且运维成熟的团队；Outbox Pattern 适合对数据一致性要求极高、需要跨系统统一重试策略的场景。无论哪种，**补偿机制（等价于当前的 Dead Letter + 手动重试）都是不可或缺的**，这是「至少一次」投递语义的工程保障。

---

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 启动服务

```bash
git clone https://github.com/your-username/rc_liutengsheng.git
cd rc_liutengsheng
mvn spring-boot:run
```

服务启动后监听 `http://localhost:8080`

H2 控制台：`http://localhost:8080/h2-console`（JDBC URL: `jdbc:h2:file:./data/notificationdb`）

### 运行测试

```bash
mvn test
```

### 快速体验

```bash
# 提交通知
curl -X POST http://localhost:8080/api/notifications -H "Content-Type: application/json" -d "{\"targetUrl\":\"https://httpbin.org/post\"}"

# 查询状态
curl http://localhost:8080/api/notifications/{id}

# 健康检查
curl http://localhost:8080/api/health
```

---

## AI 使用说明

### AI 提供了哪些帮助

1. **需求分析与系统边界梳理**：AI 帮助拆解需求，明确核心功能与可选功能，提出多种技术方案供选择。

2. **代码骨架生成**：生成了 Spring Boot 项目基础结构，包括 Entity、Repository、Service、Controller 代码框架，减少重复性样板代码编写。

3. **测试用例设计**：提出了关键测试场景（投递成功、500 失败重试、连接拒绝、超过最大重试次数进入 Dead Letter），帮助覆盖边界情况。

4. **SQL Schema 设计**：提供了 notifications 表的初始设计，包括索引建议。

### AI 给出了哪些我没有采纳的建议

1. **使用 PostgreSQL 替代 H2**：AI 建议使用 PostgreSQL，理由是生产级能力更强。我坚持选择 H2，因为本项目核心目的是演示设计思路和 AI 工具使用熟练度，H2 无需任何外部依赖，clone 后直接运行，大幅降低评审者运行成本。这是在充分理解 AI 建议后，结合实际场景做出的取舍，而非不了解 PostgreSQL。

2. **引入 Resilience4j 做熔断**：对 MVP 来说过度设计，Dead Letter 机制已足够隔离长期失败的目标。

3. **引入 Redis 做分布式锁**：单实例部署在 MVP 阶段合理，`PROCESSING` 状态标记已解决单实例内的并发问题。

4. **使用 Kafka 作为消息队列**：当前规模下是典型过度设计，DB 轮询的秒级延迟对通知场景完全可接受。

5. **添加幂等键去重**：选择「至少一次」语义，外部系统应自行保证幂等性。

### 哪些关键决策是我自己做出的

1. **系统边界的划定**：决定本系统只做「透明代理投递」，不负责格式适配。AI 最初建议加入格式转换层，我认为这会导致系统与业务耦合，因此拒绝。

2. **DB 轮询 + H2 的组合选型**：在 AI 推荐 MQ + PostgreSQL 的情况下，我坚持选择 DB 轮询 + H2。两个决策都是在充分理解 AI 建议后，结合「演示目的 > 生产完备性」的场景判断主动做出的取舍。

3. **主动标注「不支持实时投递」为已知限制**：AI 生成的设计文档没有主动提及轮询延迟的问题。我主动在系统边界中将「实时发送」列为明确不解决的问题并说明原因。知道自己系统在哪里有边界，和知道它能做什么同样重要。

4. **预定义退避表而非纯指数退避**：AI 生成了 `2^n` 分钟的指数退避实现，我将其替换为预定义间隔表（1/5/15/60/360 分钟），上限更可控，对运维更友好。

5. **`PROCESSING` 状态的引入**：AI 最初设计只有 `PENDING/SUCCESS/FAILED/DEAD_LETTER`。我主动引入了 `PROCESSING` 状态 + 宕机恢复机制，这是基于真实工程经验：Worker 崩溃后需要能够识别并恢复「被取出但未完成投递」的记录。

6. **对分布式演进路径中补偿机制的补充判断**：AI 在演进路径中只提到「换 MQ」，没有说明换 MQ 后补偿机制如何处理。我补充了 DLQ 和 Outbox Pattern 两种方案的对比分析，并指出无论哪种方案，补偿机制都不能省略。这个判断来自对分布式消息投递的实际工程经验，而非 AI 输出。
