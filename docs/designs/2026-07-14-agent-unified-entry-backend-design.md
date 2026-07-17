# Agent 统一入口 Web 版后端设计文档

## 背景

《Agent统一入口Web版》需要提供统一登录、Agent 选择、对话代理、会话管理、Agent 管理、租户授权和访问审计能力。前端页面相对简单，后端的核心复杂度集中在：

- 如何对接现有 OAuth2 SSO，并通过 Cookie 维持登录态。
- 如何按普通用户和管理员进行角色分流。
- 如何校验租户级 Agent 授权。
- 如何实现前端到统一入口后端、统一入口后端到目标 Agent 的流式代理链路。
- 如何在不实现具体 Agent 的前提下，为不同 Agent 协议预留 Adapter 扩展点。

本设计仅覆盖后端设计，不进入编码实现。

## 目标

1. 使用 Java Spring Boot 3 + Spring WebFlux 构建后端服务。
2. 前端到统一入口后端统一使用 SSE 流式协议。
3. 后端到目标 Agent 通过 `AgentAdapter` 抽象接入，不强制目标 Agent 当前统一协议。
4. 登录态 Cookie 策略遵循现有 SSO 规范。
5. 用户消息先保存，Agent 回复在流式结束后一次性保存完整内容。
6. 管理员登录后进入管理员导航页，普通用户登录后进入 Agent 选择页。
7. 审计只记录访问元信息，不记录完整消息内容。

## 方案设计

### 总体架构

后端拆分为 6 个核心模块：

| 模块 | 职责 |
|---|---|
| Auth 模块 | 对接 OAuth2 SSO，处理登录、Cookie 校验、用户身份解析 |
| Tenant/Authz 模块 | 判断用户所属租户、角色，以及租户是否已授权 Agent |
| Agent 管理模块 | 维护 Agent 名称、描述、图标、调用地址、启停状态、协议类型 |
| Conversation 模块 | 保存用户消息、Agent 回复、会话状态、归档、删除、JSON 导出 |
| Agent Proxy 模块 | 接收前端 SSE 请求，调用目标 Agent，并转发流式结果 |
| Audit 模块 | 记录用户在什么时间访问了哪个 Agent，不记录完整消息内容 |

整体调用链路：

```text
浏览器
  -> Spring WebFlux Controller
  -> Auth/Cookie 校验
  -> 租户授权 + Agent 状态校验
  -> Conversation 保存用户消息
  -> AgentProxyService
  -> AgentAdapter
  -> 目标 Agent
  -> 流式返回
  -> Flux<ServerSentEvent<?>>
  -> 浏览器实时展示
```

### 推荐包结构

```text
com.xxx.agentportal
  auth
    AuthController
    AuthService
    SsoClient
    CookieAuthWebFilter
    UserContext
  agent
    AgentController
    AdminAgentController
    AgentService
    AgentRepository
  tenant
    TenantAuthorizationService
    TenantAgentAuthRepository
  conversation
    ConversationController
    ConversationService
    ConversationRepository
    MessageRepository
  proxy
    AgentProxyController
    AgentProxyService
    AgentAdapter
    HttpSseAgentAdapter
    AgentInvokeRequest
    AgentStreamChunk
  audit
    AuditService
    AgentAccessAuditRepository
  common
    ApiResponse
    ErrorCode
    GlobalExceptionHandler
```

### 登录与 Cookie 登录态

登录流程：

```text
用户打开统一入口
  -> 无有效 Cookie
  -> 返回登录页
  -> 用户输入 tenant、username、password
  -> POST /api/auth/login
  -> 后端调用 OAuth2 SSO
  -> SSO 返回 token + user + role + tenant
  -> 后端按 SSO 规范 Set-Cookie
  -> 普通用户跳转 Agent 选择页
  -> 管理员跳转管理员导航页
```

登录接口：

```http
POST /api/auth/login
Content-Type: application/json
```

请求体：

```json
{
  "tenant": "yann-cloud",
  "username": "yann.chen",
  "password": "******"
}
```

响应体：

```json
{
  "userId": "u-001",
  "username": "Yann Chen",
  "tenantId": "t-001",
  "tenantName": "Yann Cloud",
  "role": "ADMIN",
  "redirectTo": "/admin"
}
```

同时由后端 Response Header 设置 Cookie。Cookie 内容、名称、有效期、续期策略和安全属性遵循公司现有 SSO 规范。若规范允许配置，建议使用：

- `HttpOnly`
- `Secure`
- `SameSite=Lax` 或 `SameSite=Strict`

所有受保护接口统一经过 `CookieAuthWebFilter`：

```text
CookieAuthWebFilter
  -> 读取 Cookie
  -> 调用 SSO 校验或本地校验
  -> 解析 UserContext
  -> 放入 Reactive SecurityContext / Reactor Context
```

`UserContext` 建议定义：

```java
public record UserContext(
    String userId,
    String username,
    String tenantId,
    String tenantName,
    Role role,
    String accessToken
) {}
```

安全规则：

- 密码仅用于本次登录请求。
- 密码不得写入业务库、普通应用日志或审计日志。
- 前端不得持久化明文 Token。
- `tenantId`、`userId`、`role`、`accessToken` 以后端解析出的登录态为准，不信任前端传值。
- 管理接口额外校验 `role == ADMIN`。

### 流式代理链路

前端到统一入口后端使用 SSE。

推荐接口：

```http
POST /api/conversations/{conversationId}/messages/stream
Content-Type: application/json
Accept: text/event-stream
Cookie: SSO 登录态
```

请求体：

```json
{
  "agentId": "dev-assistant",
  "message": "帮我分析这个接口设计",
  "clientMessageId": "optional-client-id"
}
```

SSE 返回示例：

```text
event: message_start
data: {"messageId":"m-001"}

event: delta
data: {"text":"统一入口后端应先校验"}

event: delta
data: {"text":"租户授权和 Agent 状态"}

event: message_end
data: {"messageId":"m-001","status":"SUCCESS"}
```

失败事件：

```text
event: error
data: {"messageId":"m-001","status":"FAILED","reason":"Agent stream interrupted"}
```

Controller 返回类型：

```java
Flux<ServerSentEvent<StreamEvent>>
```

入口编排职责：

1. 校验 Cookie 登录态。
2. 解析 `userId`、`tenantId`、`role`、`accessToken`。
3. 校验 Agent 是否存在、启用。
4. 校验当前租户是否授权该 Agent。
5. 保存用户消息。
6. 记录访问审计元信息。
7. 调用 `AgentProxyService.stream(...)`。
8. 返回 SSE 流。

### AgentAdapter 抽象

目标 Agent 当前不强制统一协议。统一入口后端定义 Adapter 抽象，屏蔽目标 Agent 差异。

```java
public interface AgentAdapter {
    Flux<AgentStreamChunk> stream(AgentInvokeRequest request);
    boolean supports(AgentProtocol protocol);
}
```

请求对象：

```java
public record AgentInvokeRequest(
    String agentId,
    String tenantId,
    String userId,
    String conversationId,
    String accessToken,
    String message,
    URI endpoint
) {}
```

流式分片：

```java
public record AgentStreamChunk(
    String text,
    boolean terminal,
    String rawPayload
) {}
```

本期至少落一个默认实现：

```java
public class HttpSseAgentAdapter implements AgentAdapter
```

未来可扩展：

- `HttpChunkedAgentAdapter`
- `VendorAgentAdapter`
- `MockAgentAdapter`
- `NonStreamAgentAdapter`

### 会话保存策略

本期采用：

- 用户消息：请求开始时立即保存。
- Agent 回复：流式过程中在内存中累积。
- 正常完成：保存完整 Agent 回复，状态为 `SUCCESS`。
- 流式失败：保存已返回部分，状态为 `FAILED`。
- 用户停止生成：取消上游订阅，保存已返回部分，状态为 `STOPPED`。

实现建议：

- 不在每个 `delta` 上写库，避免高频写入。
- 使用 `doOnComplete` 保存完整 Agent 回复。
- 使用 `doOnError` 保存已返回部分并标记失败。
- 使用 `doFinally` 处理取消、停止生成和资源释放。
- 长时间无返回时可发送 heartbeat，避免连接被误判断开。

### 数据模型

#### agent

| 字段 | 说明 |
|---|---|
| id | Agent ID |
| name | Agent 名称 |
| description | Agent 描述 |
| icon | 图标地址或标识 |
| endpoint | 目标 Agent 调用地址 |
| status | `ENABLED` / `DISABLED` |
| protocol | `SSE` / `CHUNKED` / `CUSTOM` |
| created_at | 创建时间 |
| updated_at | 更新时间 |

#### tenant_agent_auth

| 字段 | 说明 |
|---|---|
| tenant_id | 租户 ID |
| agent_id | Agent ID |
| enabled | 是否授权启用 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

#### conversation

| 字段 | 说明 |
|---|---|
| id | 会话 ID |
| user_id | 用户 ID |
| tenant_id | 租户 ID |
| agent_id | Agent ID |
| title | 会话标题 |
| status | `ACTIVE` / `ARCHIVED` / `DELETED` |
| created_at | 创建时间 |
| updated_at | 更新时间 |

删除为物理删除，`DELETED` 可作为内部过渡状态或审计扩展点，是否落库按实现决定。

#### message

| 字段 | 说明 |
|---|---|
| id | 消息 ID |
| conversation_id | 会话 ID |
| role | `USER` / `ASSISTANT` / `SYSTEM` |
| content | 完整消息内容 |
| status | `SUCCESS` / `FAILED` / `STOPPED` |
| created_at | 创建时间 |

#### agent_access_audit

| 字段 | 说明 |
|---|---|
| id | 审计 ID |
| user_id | 用户 ID |
| username | 用户名 |
| tenant_id | 租户 ID |
| tenant_name | 租户名 |
| agent_id | Agent ID |
| agent_name | Agent 名称 |
| access_time | 访问时间 |

审计表不保存完整消息内容。

### API 设计

#### Auth

```http
POST /api/auth/login
GET  /api/auth/me
POST /api/auth/logout
```

- `login`：后端对接 SSO，设置 Cookie。
- `me`：返回当前用户、租户、角色和默认首页。
- `logout`：按 SSO 规范清理 Cookie 或退出登录。

#### Agent 使用

```http
GET  /api/agents/available
GET  /api/conversations?agentId={agentId}
POST /api/conversations
POST /api/conversations/{conversationId}/messages/stream
```

- `GET /api/agents/available`：返回当前租户已授权且启用的 Agent，用于 Agent 选择页展示可用 Agent 数量和列表。
- `GET /api/conversations?agentId={agentId}`：返回当前用户在指定 Agent 下的历史会话，用于统一对话页左侧“我的对话”。
- `POST /api/conversations`：根据 `agentId` 创建该 Agent 下的新会话。
- `POST /messages/stream`：发送用户消息并返回 SSE 流。

#### 我的对话

```http
GET    /api/conversations
GET    /api/conversations?agentId={agentId}
GET    /api/conversations/{conversationId}
POST   /api/conversations/{conversationId}/archive
GET    /api/conversations/{conversationId}/export
DELETE /api/conversations/{conversationId}
```

规则：

- 只能访问自己的会话。
- 支持按 `agentId` 过滤会话；统一对话页左侧只展示当前 Agent 下的历史会话。
- 删除为物理删除。
- 导出格式为 JSON。
- Agent 停用后，历史对话仍可查看。

#### 管理接口

```http
GET    /api/admin/agents
POST   /api/admin/agents
PUT    /api/admin/agents/{agentId}
POST   /api/admin/agents/{agentId}/enable
POST   /api/admin/agents/{agentId}/disable

GET    /api/admin/tenants/{tenantId}/agents
PUT    /api/admin/tenants/{tenantId}/agents

GET    /api/admin/audits/agent-access
```

规则：

- 所有 `/api/admin/**` 必须校验管理员角色。
- 管理员不能通过接口查看用户完整对话。
- 审计只返回访问元信息。

### 核心时序

#### 登录时序

```text
Browser
  -> POST /api/auth/login
  -> AuthController
  -> AuthService
  -> SsoClient.login(tenant, username, password)
  -> SSO 返回 token + user + role + tenant
  -> AuthService 构造登录态
  -> Response Set-Cookie
  -> 返回 redirectTo
```

#### 对话流式时序

```text
Browser
  -> Agent 选择页点击目标 Agent
  -> 进入统一对话页并加载该 Agent 下历史会话
  -> POST /api/conversations/{id}/messages/stream
  -> CookieAuthWebFilter 校验 Cookie
  -> AgentProxyController
  -> AgentService 校验 Agent 存在且启用
  -> TenantAuthorizationService 校验当前租户已授权 Agent
  -> ConversationService 保存用户消息
  -> AuditService 记录访问 Agent 元信息
  -> AgentProxyService 调用 AgentAdapter.stream()
  -> Adapter 连接目标 Agent
  -> 目标 Agent 流式返回
  -> 后端转成 SSE delta
  -> 前端实时展示
  -> 流式完成后保存完整 Agent 回复
```

#### 管理接口时序

```text
Browser
  -> GET /api/admin/agents
  -> CookieAuthWebFilter 校验 Cookie
  -> AdminRoleWebFilter 校验 role == ADMIN
  -> AdminAgentController
  -> AgentService 查询 Agent 列表
  -> 返回管理数据
```

### 异常处理

| 场景 | 后端处理 | 前端表现 |
|---|---|---|
| 租户为空 | 参数校验失败 | 提示请输入租户 |
| 用户名为空 | 参数校验失败 | 提示请输入用户名 |
| 密码为空 | 参数校验失败 | 提示请输入密码 |
| SSO 认证失败 | 返回 `AUTH_FAILED` | 停留登录页 |
| Cookie 设置失败 | 返回 `COOKIE_SET_FAILED` | 提示重新登录 |
| SSO 不可用 | 返回 `SSO_UNAVAILABLE` | 提示稍后重试 |
| Cookie 无效 | 返回 401 | 跳转登录页 |
| 普通用户访问管理接口 | 返回 403 | 提示无权限 |
| Agent 不存在 | 返回 `AGENT_NOT_FOUND` | 提示 Agent 不存在 |
| Agent 停用 | 返回 `AGENT_DISABLED` | 提示无法发起新对话 |
| 租户未授权 | 返回 `AGENT_NOT_AUTHORIZED` | 提示当前租户未开通 |
| 目标 Agent 连接失败 | SSE 返回 `error` 事件 | 展示失败标记 |
| 流式中断 | 保存已返回部分，状态 `FAILED` | 保留部分内容并标记失败 |
| 用户停止生成 | 取消上游订阅，保存部分内容，状态 `STOPPED` | 标记已停止 |

## 技术选型

| 技术 | 用途 | 选择理由 |
|---|---|---|
| Java 17+ | 运行时 | Spring Boot 3 推荐基线 |
| Spring Boot 3 | 应用框架 | 成熟稳定，适合企业后端 |
| Spring WebFlux | Web 框架 | 支持响应式流，适合 SSE 和流式代理 |
| WebClient | 目标 Agent 调用 | 非阻塞 HTTP Client，适合流式读取 |
| SSE | 前端流式协议 | 浏览器支持好，适合 Agent 文本流 |
| R2DBC 或 JDBC | 数据访问 | 若团队响应式经验足可用 R2DBC；否则 JDBC 需隔离阻塞线程池 |
| 公司 OAuth2 SSO | 登录认证 | 遵循现有统一登录体系 |

数据访问建议：

- 如果项目已有成熟 JDBC/MyBatis 体系，可以继续使用，但所有数据库写入应避免阻塞 WebFlux 事件循环。
- 若使用 JDBC/MyBatis，需要通过专用调度器或服务层隔离阻塞调用。
- 若团队具备响应式数据访问经验，可以选择 R2DBC，以保持端到端非阻塞。

## 实施计划

### 阶段 1：工程骨架与认证

1. 初始化 Spring Boot 3 + WebFlux 工程。
2. 接入公司 OAuth2 SSO 登录接口。
3. 实现 `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`。
4. 实现 `CookieAuthWebFilter` 和 `UserContext`。
5. 完成普通用户和管理员默认首页分流：普通用户进入 Agent 选择页，管理员进入管理员导航页。

### 阶段 2：Agent 与租户授权

1. 建表：`agent`、`tenant_agent_auth`。
2. 实现 Agent 管理接口。
3. 实现租户授权接口。
4. 实现 `GET /api/agents/available`，支持 Agent 选择页展示可用 Agent 数量和列表。
5. 完成 Agent 启停和租户授权校验。

### 阶段 3：会话与消息

1. 建表：`conversation`、`message`。
2. 实现会话创建、列表、详情、归档、导出、物理删除。
3. 会话列表接口支持按 `agentId` 过滤，统一对话页左侧只展示当前 Agent 下的历史会话。
4. 实现用户只能访问自己会话的权限校验。
5. 支持 Agent 停用后历史会话仍可查看。

### 阶段 4：流式代理

1. 定义 `AgentAdapter`、`AgentInvokeRequest`、`AgentStreamChunk`。
2. 实现默认 `HttpSseAgentAdapter`。
3. 实现 `AgentProxyService.stream()`。
4. 实现 SSE Controller：`POST /api/conversations/{conversationId}/messages/stream`。
5. 实现用户消息保存、Agent 回复累积保存、失败保存。
6. 支持 heartbeat、取消和资源释放。

### 阶段 5：审计与验收

1. 建表：`agent_access_audit`。
2. 对 Agent 访问记录元信息。
3. 实现访问审计查询接口。
4. 确保审计不记录完整消息内容。
5. 联调前端页面和流式展示。
6. 完成功能、权限、异常和安全测试。

## 风险和挑战

### 流式链路风险

风险：

- WebFlux 使用不当可能阻塞事件循环。
- 目标 Agent 流式协议不统一。
- 长连接异常、取消、超时处理复杂。

应对：

- 前端固定 SSE，后端用 Adapter 屏蔽目标 Agent 差异。
- 数据库写入不放在高频 `delta` 链路中。
- `doOnError`、`doFinally` 必须覆盖失败和取消。
- MVP 前端直连统一入口后端，暂不引入网关缓冲问题。

### SSO/Cookie 风险

风险：

- Cookie 内容、有效期、续期策略依赖公司 SSO 规范。
- 目标 Agent 是否接受当前 `accessToken` 需要确认。

应对：

- 登录态 Cookie 完全遵循 SSO 标准。
- 统一入口内部只依赖 `UserContext`。
- 若 SSO 不允许直接转发 `accessToken`，增加后端换取下游访问凭证的能力。

### 数据一致性风险

风险：

- 流式完成前进程异常可能导致 Agent 回复未保存。
- 失败时部分内容保存策略需要前后端一致。

应对：

- 用户消息先保存。
- Agent 回复失败时保存已返回部分并标记 `FAILED`。
- 前端根据 `message_end`、`error`、`STOPPED` 状态展示最终状态。

### 权限风险

风险：

- 前端传入的 `tenantId`、`userId` 可能被伪造。
- 管理员不允许查看用户完整对话内容，需要接口层严格限制。

应对：

- 后端所有身份信息从 Cookie 登录态解析。
- 前端只传 `agentId`、`conversationId`、`message`。
- 管理接口不提供用户完整对话查询能力。
- 所有 `/api/admin/**` 统一校验 `role == ADMIN`。

## 待确认事项

1. 公司 SSO Cookie 的名称、有效期、续期和清理规范。
2. SSO 返回的角色字段和租户字段格式。
3. 目标 Agent 是否接受当前 `accessToken`，或是否需要统一入口换取下游凭证。
4. 本期默认 Agent Adapter 的目标协议是否优先实现 SSE。
5. 数据访问层使用 R2DBC 还是 JDBC/MyBatis。
6. 是否需要支持用户主动“停止生成”的后端接口或前端断连即视为停止。
