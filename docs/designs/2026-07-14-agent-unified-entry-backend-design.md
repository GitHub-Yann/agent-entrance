# Agent 统一入口 Web 版后端设计文档

## 背景

《Agent统一入口Web版》需要提供统一登录、Agent 选择、对话代理、会话管理、Agent 管理、租户授权和访问审计能力。前端页面相对简单，后端的核心复杂度集中在：

- 如何对接现有 OAuth2 SSO，并通过 Cookie 维持登录态。
- 如何按普通用户和管理员进行角色分流。
- 如何校验租户级 Agent 授权。
- 如何接收图片、文档、语音等附件，并以附件引用的方式进入 Agent 调用链路。
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
8. 支持附件与语音上传，且附件以引用形式随消息一并转发给目标 Agent。

## 方案设计

### 总体架构

后端拆分为 7 个核心模块：

| 模块 | 职责 |
|---|---|
| Auth 模块 | 对接 OAuth2 SSO，处理登录、Cookie 校验、用户身份解析 |
| Tenant/Authz 模块 | 判断用户所属租户、角色，以及租户是否已授权 Agent |
| Agent 管理模块 | 维护 Agent 名称、描述、图标、调用地址、启停状态、协议类型 |
| Attachment 模块 | 接收附件/语音上传，保存对象存储引用，返回 attachmentId，并管理附件元信息 |
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
  -> Attachment 读取并校验附件引用
  -> AgentProxyService
  -> AgentAdapter
  -> 目标 Agent
  -> 流式返回
  -> Flux<ServerSentEvent<?>>
  -> 浏览器实时展示
```

### 统一入口与目标 Agent 职责边界

统一入口和目标 Agent 的核心分工是：统一入口负责“能不能调用、如何安全调用、如何保存和展示”，目标 Agent 负责“调用后具体生成什么、如何理解附件和领域任务”。

统一入口侧职责：

- 统一入口负责登录态校验、租户授权、Agent 启停校验、会话保存、附件引用解析和审计。
- 统一入口不实现目标 Agent 的业务能力，不解释目标 Agent 的流式语义。
- 统一入口只定义并维护自身到目标 Agent 的请求契约，以及自身对前端的 SSE 外层响应格式。
- 统一入口负责生成 `correlationId`，选择 `AgentAdapter`，将目标 Agent 的响应归一化为前端稳定的 SSE 事件。
- 统一入口负责连接超时、空闲超时、取消、失败保存和错误码映射。
- 统一入口不得向前端暴露目标 Agent 的原始协议事件、下游堆栈、Token 或敏感配置。

目标 Agent 侧职责：

- 目标 Agent 负责具体智能能力、领域逻辑、工具调用、知识检索和模型推理。
- 目标 Agent 接收统一入口传入的 `conversationId`、`message`、`attachments`、`clientMessageId` 等上下文，并按自身需要使用。
- 目标 Agent 如需读取附件内容、OCR、语音转写、文档解析或内容摘要，由目标 Agent 自行完成。
- 目标 Agent 按约定协议返回可被 Adapter 解析的流式响应；其原始协议不直接作为前端契约。
- 目标 Agent 内部失败时应返回可识别的错误信息，由统一入口映射为前端稳定错误码。
- 目标 Agent 如需鉴权，应明确接受统一入口透传的用户令牌，或接受统一入口换取的下游访问凭证。

### 推荐包结构

```text
com.xxx.agent.entrance
  controller
    AgentProxyController
    TestPageController
  service
    AgentService
    AgentProxyService
    AuditService
    ConversationService
    SsoJwtConfigRefreshService
    TenantAuthorizationService
  adapter
    AgentAdapter
    HttpSseAgentAdapter
  dto
    StreamMessageRequest
    StreamEvent
    AgentInvokeRequest
    AgentAttachmentRef
    TargetAgentRequestBody
    AgentStreamChunk
    SsoJwtConfigResponse
  model
    Agent
    AgentAccessAudit
    AgentProtocol
    AgentStatus
    MessageRole
    MessageStatus
    StoredMessage
    UserContext
  support
    CorrelationContext
    JsonLog
    TokenExtractor
    UserContextResolver
    SsoJwtVerifier
    SsoJwtConfigClient
    SsoPublicKeyClient
    PemPublicKeyParser
    FlatJsonLogEncoder
  config
    TimeConfiguration
    SsoJwtProperties
```

说明：

- 这里按现有工程的分层包来写，方便和当前代码目录直接对应。
- `controller / service / adapter / dto / model / support / config` 是主分层。
- 具体业务模块通过类命名和注释区分，比如 `AgentProxyService`、`TenantAuthorizationService`、`ConversationService`。

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

### 附件与语音上传链路

附件与语音不直接通过消息流转发二进制内容，而是先上传、再引用。

推荐接口：

```http
POST /api/conversations/{conversationId}/attachments
Content-Type: multipart/form-data
Cookie: SSO 登录态
```

上传完成后，后端返回附件元信息：

```json
{
  "attachmentId": "att-001",
  "fileName": "design.pdf",
  "contentType": "application/pdf",
  "mediaType": "DOCUMENT",
  "size": 123456,
  "status": "ACTIVE"
}
```

职责边界：

- 统一入口只做附件基础校验、归属校验、元信息保存和引用转发。
- 统一入口不做文件正文解析、病毒扫描、OCR、语音转写或内容摘要。
- 目标 Agent 如需理解文件内容，由目标 Agent 自行读取附件 URL 并处理。
- 附件内容不进入 SSE 流，SSE 请求只携带附件引用。

基础校验：

| 校验项 | 规则 | 失败码 |
|---|---|---|
| 归属校验 | 附件必须属于当前 `tenantId`、`userId` 和 `conversationId` | `ATTACHMENT_FORBIDDEN` |
| 状态校验 | 附件状态必须为 `ACTIVE` | `ATTACHMENT_NOT_ACTIVE` |
| 大小校验 | 按 `mediaType` 配置最大大小，默认图片 20MB、文档 100MB、音频 200MB | `ATTACHMENT_TOO_LARGE` |
| 类型校验 | 仅允许 `IMAGE`、`DOCUMENT`、`AUDIO` | `ATTACHMENT_TYPE_UNSUPPORTED` |
| MIME 校验 | `contentType` 必须命中白名单，并与文件扩展名做基础匹配 | `ATTACHMENT_TYPE_UNSUPPORTED` |

说明：

- `contentType` 和文件扩展名只作为基础准入判断，不代表统一入口已验证文件真实内容。
- 文件 URL 的可访问性、有效期和权限策略遵循对象存储桶配置，统一入口不负责续期。
- 日志和审计只记录 `attachmentId`、`mediaType`、`size` 等元信息，不打印完整 `accessUrl`。
- 删除会话时删除附件元信息；对象存储中的实际文件按存储桶生命周期策略清理。

消息发送时，前端仅传 `attachmentIds`，例如：

```json
{
  "agentId": "dev-assistant",
  "message": "请结合附件内容分析这个接口设计",
  "attachmentIds": ["att-001", "att-002"],
  "clientMessageId": "optional-client-id"
}
```

入口编排职责补充如下：

1. 校验附件是否属于当前租户、当前用户和当前会话。
2. 校验附件状态是否为 `ACTIVE`，且类型、大小、MIME 和扩展名符合基础白名单约束。
3. 通过附件 ID 查询附件元信息，并组装为 `AgentAttachmentRef`。
4. 将附件绑定到本次用户消息，避免跨会话、跨用户或上传后未发送的附件被误用。
5. 将用户文本和 `AgentAttachmentRef` 一起转发给目标 Agent。
6. `AgentAttachmentRef.accessUrl` 直接使用对象存储桶地址；目标 Agent 读取失败时，由目标 Agent 返回对应错误，后续如有必要再扩展 URL 刷新机制。

### 流式代理链路

前端到统一入口后端使用 SSE。

本节定义的是统一 Agent 前端与统一 Agent 后端之间的 SSE 事件契约，也是前端联调和后端测试用例共同遵循的外层协议。目标 Agent 不需要直接实现这些事件；统一入口后端可以适配不同目标 Agent 协议，但对前端暴露的 SSE 事件必须保持稳定。

本期前端仅按纯文本或 Markdown 渲染 `delta.text`，可交互图表、MCP Apps、A2UI 或组件化富文本输出作为后续受控内容块协议扩展。

#### ID 约定

| ID | 生成方 | 生成时机 | 格式 | 作用 |
|---|---|---|---|---|
| `conversationId` | 统一入口后端 | 用户点击“新建会话”时 | `c-` + UUID | 标识一次业务会话，贯穿会话保存、查询、导出和删除 |
| `clientMessageId` | 前端 | 用户点击发送前 | `cm-` + UUID | 标识本次用户请求，用于前端本地气泡关联、重试幂等和日志排查 |
| `correlationId` | 统一入口后端 | 统一入口收到一次请求时 | UUID | 标识一次请求链路，用于日志关联和请求头 `x-correlation-id` 透传 |

说明：

- `conversationId` 是业务会话级 ID，随请求体传入目标 Agent。
- `clientMessageId` 是本次请求级 ID，随请求体传入目标 Agent，目标 Agent 可按需使用。
- 统一入口返回给前端 SSE 事件中的 `messageId` 取自 `clientMessageId`；如果前端未传入 `clientMessageId`，统一入口生成一个 `cm-` 前缀 ID 后再封装到 SSE 响应中。
- 如落库时需要区分用户消息和助手消息的内部主键，可另设存储层 ID；该内部 ID 不作为前端 SSE 的 `messageId`。
- `correlationId` 是链路追踪 ID，统一入口在请求头中透传给目标 Agent，首期不用放入业务响应体。

#### 前端请求对象

前端提交给统一入口后端的请求体使用 `StreamMessageRequest`，其职责是承载用户本次发送内容与附件引用。

```json
{
  "agentId": "dev-assistant",
  "message": "帮我分析这个接口设计",
  "attachmentIds": ["att-001", "att-002"],
  "clientMessageId": "optional-client-id"
}
```

说明：

- `agentId` 表示目标 Agent。
- `message` 是用户输入的文本。
- `attachmentIds` 是前端在上传附件/语音后拿到并回填的引用集合。
- `clientMessageId` 用于前端幂等或日志关联，统一入口保存用户消息和组装 SSE 响应时均使用该 ID 作为本次请求的 `messageId`。

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
  "attachmentIds": ["att-001", "att-002"],
  "clientMessageId": "optional-client-id"
}
```

SSE 返回示例：

```text
event: message_start
data: {"messageId":"cm-001"}

event: delta
data: {"text":"统一入口后端应先校验"}

event: delta
data: {"text":"租户授权和 Agent 状态"}

event: message_end
data: {"messageId":"cm-001","status":"SUCCESS"}
```

失败事件：

```text
event: error
data: {"messageId":"cm-001","status":"FAILED","reason":"Agent stream interrupted"}
```

Controller 返回类型：

```java
Flux<ServerSentEvent<StreamEvent>>
```

#### SSE 事件契约

统一入口后端对前端只暴露以下事件类型。除 `delta` 承载目标 Agent 的可展示文本外，其余事件均是统一入口后端生成的控制事件。

| 事件 | 触发时机 | data 字段 | 是否终止事件 | 前端处理 |
|---|---|---|---|---|
| `message_start` | 用户消息保存成功，准备连接目标 Agent 前 | `messageId` | 否 | 创建助手消息占位气泡 |
| `delta` | 目标 Agent 返回一段可展示文本 | `text` | 否 | 追加到当前助手消息 |
| `message_end` | 目标 Agent 正常结束，助手完整回复保存成功 | `messageId`、`status` | 是 | 标记消息成功完成 |
| `error` | 连接失败、目标 Agent 异常、保存失败或协议解析失败 | `messageId`、`status`、`code`、`reason` | 是 | 标记消息失败并展示可读提示 |
| `stopped` | 用户主动停止或前端连接断开并被后端识别为取消 | `messageId`、`status`、`reason` | 是 | 标记消息已停止 |

`StreamEvent` 字段建议：

```java
public record StreamEvent(
    String messageId,
    String text,
    String status,
    String code,
    String reason
) {}
```

字段规则：

- `message_start` 必须先于任何 `delta`、`message_end`、`error` 或 `stopped` 返回。
- `delta.text` 允许为空白以外的任意文本，不承载状态语义。
- `message_end`、`error`、`stopped` 三者互斥，同一次流式请求最多只出现一个。
- 终止事件发出后，服务端必须完成 SSE 响应，不再发送新事件。
- `reason` 是面向前端展示的简短原因，不包含下游响应体、堆栈、Token、对象存储地址等敏感信息。
- `code` 是稳定错误码，前端使用 `code` 做分支，不解析 `reason`。

#### 流式顺序与状态机

统一入口后端对一次发送请求维护以下状态：

```text
RECEIVED
  -> USER_MESSAGE_SAVED
  -> AGENT_CONNECTING
  -> STREAMING
  -> SUCCESS | FAILED | STOPPED
```

状态转换规则：

- 请求校验、登录态校验、Agent 启停校验、租户授权校验、附件校验失败时，不进入 SSE 流，直接返回对应 HTTP 错误。
- 用户消息保存成功后才允许发送 `message_start`。
- 目标 Agent 连接建立但尚未返回业务内容时，进入 `AGENT_CONNECTING`，统一入口后端等待目标 Agent 返回业务分片或触发超时。
- 收到至少一个可展示分片后进入 `STREAMING`。
- 正常结束时保存助手完整回复，保存成功后发送 `message_end`。
- 下游异常、协议解析异常、保存助手回复失败时保存已返回部分并发送 `error`。
- 用户主动停止或前端断开连接时取消上游订阅，保存已返回部分并标记 `STOPPED`。若已经发送 `message_end` 或 `error`，取消信号不再覆盖最终状态。

#### 超时与取消

本期建议使用以下默认值，后续放入 `application-*.yml`：

| 配置项 | 默认值 | 触发后的处理 |
|---|---:|---|
| 目标 Agent 连接超时 | 10 秒 | 返回 `error`，`code=AGENT_CONNECT_TIMEOUT` |
| 空闲超时 | 120 秒 | 返回 `error`，`code=AGENT_IDLE_TIMEOUT` |
| 最大单次流式时长 | 10 分钟 | 取消上游订阅并返回 `error`，`code=AGENT_STREAM_TIMEOUT` |

目标 Agent 响应超时策略：

- 统一入口后端只关注目标 Agent 的业务响应超时，不向统一 Agent 前端额外发送心跳事件。
- 空闲超时以“最近一次业务事件”为判断基准，业务事件包括 `message_start` 和 `delta`。
- 如果当前时间距离最近业务事件超过 `idleTimeout`，统一入口后端判定目标 Agent 在 2 分钟内无有效响应。
- 判定空闲超时后，后端必须取消目标 Agent 的上游订阅，释放 WebClient 连接和相关资源。
- 若此时已返回部分 `delta`，保存部分助手消息并标记 `FAILED`；若尚未返回任何 `delta`，保存空助手消息或仅记录失败状态，具体按 `ConversationService` 的落库约定执行。
- 后端向前端发送终止事件 `error`，`code=AGENT_IDLE_TIMEOUT`，随后完成 SSE 响应。

实现建议：

```java
return adapter.stream(invokeRequest)
    .timeout(Duration.ofMinutes(2))
    .map(chunk -> toDeltaEvent(chunk))
    .concatWith(Mono.fromSupplier(() -> toMessageEndEvent(messageId)))
    .onErrorResume(TimeoutException.class, ex -> {
        cancelTargetAgentCall();
        saveAssistantMessage(messageId, partialContent, MessageStatus.FAILED);
        return Mono.just(toErrorEvent(messageId, "AGENT_IDLE_TIMEOUT"));
    });
```

说明：

- 上述代码是实现思路，不要求逐字照搬；实际实现可使用 Reactor `timeout`、`takeUntilOther`、`materialize` 等操作符收敛。
- 关键约束是：超过阈值后由统一入口后端主动取消目标 Agent 调用；前端只接收统一入口定义的 `error` 事件。
- 若使用 Reactor `timeout(Duration)`，目标 Agent 在 2 分钟内没有任何业务分片时会触发超时；每次新的 `delta` 到达后，超时窗口会重新开始计算。
- 需要避免业务完成、下游异常和超时同时发送终止事件，建议用 `AtomicBoolean terminal` 或等价状态机保证终止事件只发送一次。

取消语义：

- 本期不单独提供停止接口；前端点击“停止生成”时关闭 SSE 连接，后端将取消信号视为 `STOPPED`。
- 后端收到取消信号后必须释放上游 WebClient 连接，不继续消费目标 Agent 响应。
- 已返回给前端的部分内容需要保存，状态为 `STOPPED`。
- 停止不是失败，不计入目标 Agent 可用性错误指标，但需要记录取消次数和已生成长度。

#### 错误码映射

| code | 场景 | HTTP/SSE 表现 | 消息保存策略 |
|---|---|---|---|
| `AGENT_NOT_FOUND` | Agent 不存在 | HTTP 404 | 不保存助手消息 |
| `AGENT_DISABLED` | Agent 已停用 | HTTP 409 | 不保存助手消息 |
| `AGENT_NOT_AUTHORIZED` | 租户未授权 | HTTP 403 | 不保存助手消息 |
| `ATTACHMENT_INVALID` | 附件不存在、无权限、类型或大小非法 | HTTP 400/403 | 不保存助手消息 |
| `AGENT_CONNECT_TIMEOUT` | 连接目标 Agent 超时 | SSE `error` | 保存空或部分助手消息为 `FAILED` |
| `AGENT_IDLE_TIMEOUT` | 流式过程中长时间无分片 | SSE `error` | 保存部分助手消息为 `FAILED` |
| `AGENT_STREAM_INTERRUPTED` | 下游连接中断或异常关闭 | SSE `error` | 保存部分助手消息为 `FAILED` |
| `AGENT_PROTOCOL_ERROR` | 下游响应无法被当前 Adapter 解析 | SSE `error` | 保存部分助手消息为 `FAILED` |
| `MESSAGE_SAVE_FAILED` | 助手消息保存失败 | SSE `error` | 用户消息已保存，助手消息按补偿任务修复 |

说明：

- HTTP 错误用于“流开始前”的失败场景。
- SSE `error` 用于“流已经开始后”的失败场景。
- 前端展示优先使用 `code` 对应的本地化文案，`reason` 只作为兜底说明。
- 所有错误日志必须带 `correlationId`、`agentId`、`conversationId` 和最终状态，禁止输出完整消息内容和凭证。

入口编排职责：

1. 校验 Cookie 登录态。
2. 解析 `userId`、`tenantId`、`role`、`accessToken`。
3. 校验 Agent 是否存在、启用。
4. 校验当前租户是否授权该 Agent。
5. 保存用户消息。
6. 记录访问审计元信息。
7. 解析 `attachmentIds` 并组装附件引用。
8. 调用 `AgentProxyService.stream(...)`，转发时在请求头中携带 `x-correlation-id` 和 `Authorization: Bearer <accessToken>`，在请求体中携带 `conversationId`、`message` 和校验后的 `attachments`。
9. 通过 `AgentAdapter` 将目标 Agent 的流式结果归一化为 `AgentStreamChunk`，再包装成统一入口对前端承诺的 SSE 事件。

### AgentAdapter 抽象

目标 Agent 当前不强制统一协议。统一入口后端定义 Adapter 抽象，屏蔽目标 Agent 差异。

```java
public interface AgentAdapter {
    Flux<AgentStreamChunk> stream(AgentInvokeRequest request);
    boolean supports(AgentProtocol protocol);
}
```

#### 代理上下文对象

```java
public record AgentInvokeRequest(
    String agentId,
    String tenantId,
    String userId,
    String conversationId,
    String accessToken,
    String message,
    List<AgentAttachmentRef> attachments,
    String correlationId,
    String clientMessageId,
    URI endpoint
) {}
```

```java
public record AgentAttachmentRef(
    String attachmentId,
    String fileName,
    String contentType,
    String mediaType,
    String storageKey,
    String accessUrl
) {}
```

说明：

- 这是统一入口内部的代理上下文对象，不是直接发给目标 Agent 的 body。
- `correlationId` 对应请求头 `x-correlation-id`。
- `attachments` 是统一入口在发送前解析出来的附件引用集合。
- `accessUrl` 和 `storageKey` 都是附件引用的实现细节，具体取哪一个由目标 Agent 决定。

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

### 统一转发契约

统一入口与目标 Agent 之间采用“请求头 + 请求体 + Adapter 归一化流式响应”的最小契约。

#### 请求头

| Header | 必填 | 说明 |
|---|---|---|
| `x-correlation-id` | 是 | 链路追踪标识，由统一入口生成并透传到目标 Agent |
| `Authorization` | 是 | 统一入口为目标 Agent 传递的访问令牌，格式为 `Bearer eyJ...` |

#### 下游请求体对象

```java
public record TargetAgentRequestBody(
    String agentId,
    String tenantId,
    String userId,
    String conversationId,
    String message,
    List<AgentAttachmentRef> attachments,
    String clientMessageId
) {}
```

说明：

- 这是统一入口实际发送给目标 Agent 的 body。
- `attachments` 是统一入口校验后组装的附件引用，包含 `attachmentId`、文件元信息、`storageKey` 和 `accessUrl`。
- `accessUrl` 直接使用对象存储桶公网可访问地址，生命周期和访问策略遵循对象存储桶配置；本期统一入口不额外处理附件 URL 安全模型。
- `conversationId` 由统一入口透传，目标 Agent 可自主决定是否使用。

#### 请求体示例

```json
{
  "agentId": "dev-assistant",
  "tenantId": "t-001",
  "userId": "u-001",
  "conversationId": "c-001",
  "message": "帮我分析这个接口设计",
  "attachments": [
    {
      "attachmentId": "att-001",
      "fileName": "design.pdf",
      "contentType": "application/pdf",
      "mediaType": "DOCUMENT",
      "storageKey": "tenant/t-001/conversation/c-001/design.pdf",
      "accessUrl": "https://bucket.example.com/tenant/t-001/conversation/c-001/design.pdf"
    }
  ],
  "clientMessageId": "optional-client-id"
}
```

#### 响应体

- 统一入口不向前端原样暴露目标 Agent 的原始协议事件。
- 统一入口外层继续使用自身 SSE 事件包装；其中 `delta.text` 承载目标 Agent 生成的可展示文本，不额外改写目标 Agent 的业务语义。
- 统一入口必须按“前端 SSE 事件契约”输出稳定事件；目标 Agent 的原始事件名称、字段和错误结构由 Adapter 负责转换。
- 超时、取消和错误码映射属于本期必备契约，默认值可配置。

#### 目标 Agent 响应映射规则

统一入口后端不把目标 Agent 的原始响应直接暴露给前端，而是先交给 Adapter 归一化，再由 `AgentProxyService` 包装成统一 Agent 前端可消费的 SSE 事件。

本期只实现 `SSE` 类型目标 Agent；`CHUNKED` 和 `CUSTOM` 仅作为后续扩展预留。

| 目标 Agent 响应 | Adapter 处理 | 统一入口输出 |
|---|---|---|
| `data: {"text":"..."}` | 提取 `text` 字段作为可展示文本分片 | `delta` |
| SSE 流正常完成 | 解析为终止信号 | `message_end` |
| 连接异常或响应无法解析 | 解析为下游错误或协议错误 | `error` |
| 未知事件或无法解析内容 | 记录 `rawPayload` 并判定协议错误 | `error` |

规则说明：

- Adapter 负责把不同下游协议转换为 `AgentStreamChunk`，统一入口前端只看统一 SSE 事件。
- `AgentStreamChunk.text` 用于承载可展示文本。
- `AgentStreamChunk.terminal=true` 表示下游已结束，`AgentProxyService` 据此发送 `message_end`。
- `AgentStreamChunk.rawPayload` 用于保留原始下游内容，方便排障，不直接返回前端。
- 下游返回结构不完整、字段缺失或事件语义不明确时，统一入口必须按协议错误处理，返回 `AGENT_PROTOCOL_ERROR`。

目标 Agent 首期 SSE 响应样例：

```text
data: {"text":"统一入口后端应先校验"}

data: {"text":"租户授权和 Agent 状态"}
```
- 任何目标 Agent 私有事件名、字段名或错误格式都不允许直接透传给前端。

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
- 长时间无返回时按目标 Agent 响应超时处理，不额外发送前端心跳事件。

### 数据模型

#### agent

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | Agent ID |
| name | varchar(128) | Agent 名称 |
| description | varchar(512) | Agent 描述 |
| icon | varchar(512) | 图标地址或标识 |
| endpoint | varchar(1024) | 目标 Agent 调用地址 |
| status | varchar(32) | `ENABLED` / `DISABLED` |
| protocol | varchar(32) | `SSE` / `CHUNKED` / `CUSTOM` |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

#### tenant_agent_auth

| 字段 | 类型 | 说明 |
|---|---|---|
| tenant_id | varchar(64) | 租户 ID |
| agent_id | varchar(64) | Agent ID |
| enabled | boolean | 是否授权启用 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

#### conversation

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | 会话 ID |
| user_id | varchar(64) | 用户 ID |
| tenant_id | varchar(64) | 租户 ID |
| agent_id | varchar(64) | Agent ID |
| title | varchar(256) | 会话标题 |
| status | varchar(32) | `ACTIVE` / `ARCHIVED` / `DELETED` |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

删除为物理删除，`DELETED` 可作为内部过渡状态或审计扩展点，是否落库按实现决定。

#### attachment

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | 附件 ID |
| conversation_id | varchar(64) | 会话 ID |
| message_id | varchar(64) | 消息 ID，允许为空，表示已上传但尚未绑定到消息 |
| user_id | varchar(64) | 上传人用户 ID |
| tenant_id | varchar(64) | 租户 ID |
| file_name | varchar(255) | 原始文件名 |
| content_type | varchar(128) | MIME 类型 |
| media_type | varchar(32) | `IMAGE` / `DOCUMENT` / `AUDIO` / `OTHER` |
| storage_key | varchar(1024) | 对象存储中的存储键 |
| access_url | varchar(2048) | 下载或签名访问地址 |
| size | bigint | 文件大小，单位字节 |
| status | varchar(32) | `ACTIVE` / `DELETED` / `EXPIRED` |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

#### message

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | 消息 ID |
| conversation_id | varchar(64) | 会话 ID |
| role | varchar(32) | `USER` / `ASSISTANT` / `SYSTEM` |
| content | text | 完整消息内容 |
| status | varchar(32) | `SUCCESS` / `FAILED` / `STOPPED` |
| created_at | timestamp | 创建时间 |

#### agent_access_audit

| 字段 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | 审计 ID |
| user_id | varchar(64) | 用户 ID |
| username | varchar(128) | 用户名 |
| tenant_id | varchar(64) | 租户 ID |
| tenant_name | varchar(128) | 租户名 |
| agent_id | varchar(64) | Agent ID |
| agent_name | varchar(128) | Agent 名称 |
| access_time | timestamp | 访问时间 |

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

##### POST /api/auth/login

请求体：

```json
{
  "tenant": "yann-cloud",
  "username": "yann.chen",
  "password": "******"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `tenant` | 是 | 租户标识，传给 SSO 做租户域认证 |
| `username` | 是 | 登录用户名 |
| `password` | 是 | 登录密码，仅用于本次 SSO 登录，不落库、不打印日志 |

成功响应：

```http
HTTP/1.1 200 OK
Set-Cookie: agent_sso=...; HttpOnly; Secure; SameSite=Lax; Path=/
Content-Type: application/json
```

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

响应字段说明：

| 字段 | 说明 |
|---|---|
| `userId` | 后端从 SSO 返回结果解析出的用户 ID |
| `username` | 展示用户名 |
| `tenantId` | 后端从 SSO 返回结果解析出的租户 ID |
| `tenantName` | 展示租户名 |
| `role` | 用户角色，普通用户为 `USER`，管理员为 `ADMIN` |
| `redirectTo` | 登录成功后的默认跳转地址，管理员为 `/admin`，普通用户为 `/agents` |

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 租户为空 | 400 | `TENANT_REQUIRED` |
| 用户名为空 | 400 | `USERNAME_REQUIRED` |
| 密码为空 | 400 | `PASSWORD_REQUIRED` |
| SSO 认证失败 | 401 | `AUTH_FAILED` |
| SSO 不可用 | 503 | `SSO_UNAVAILABLE` |
| Cookie 设置失败 | 500 | `COOKIE_SET_FAILED` |

失败响应体统一格式：

```json
{
  "code": "AUTH_FAILED",
  "message": "登录失败",
  "correlationId": "b7e7b2d8-1a6a-4df2-9a7e-8b3f1f3d8f01"
}
```

##### GET /api/auth/me

请求：

```http
GET /api/auth/me
Cookie: agent_sso=...
```

成功响应：

```json
{
  "authenticated": true,
  "userId": "u-001",
  "username": "Yann Chen",
  "tenantId": "t-001",
  "tenantName": "Yann Cloud",
  "role": "USER",
  "defaultHome": "/agents"
}
```

响应字段说明：

| 字段 | 说明 |
|---|---|
| `authenticated` | 当前 Cookie 是否已通过后端校验 |
| `userId` | 当前用户 ID |
| `username` | 当前用户名 |
| `tenantId` | 当前租户 ID |
| `tenantName` | 当前租户名 |
| `role` | 当前角色 |
| `defaultHome` | 当前角色对应的默认首页 |

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失 | 401 | `UNAUTHENTICATED` |
| Cookie 无效或过期 | 401 | `SESSION_EXPIRED` |
| SSO 校验不可用 | 503 | `SSO_UNAVAILABLE` |

##### POST /api/auth/logout

请求：

```http
POST /api/auth/logout
Cookie: agent_sso=...
```

成功响应：

```http
HTTP/1.1 200 OK
Set-Cookie: agent_sso=; Max-Age=0; HttpOnly; Secure; SameSite=Lax; Path=/
Content-Type: application/json
```

```json
{
  "success": true,
  "redirectTo": "/login"
}
```

规则：

- `logout` 必须清理统一入口自己的登录 Cookie。
- 若 SSO 规范要求服务端调用退出接口，则后端同步调用 SSO logout；若 SSO logout 失败，本地 Cookie 仍需清理。
- `logout` 不返回 Token、Cookie 原值或其他敏感信息。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 已不存在 | 200 | 不返回错误，视为登出成功 |
| SSO logout 失败但本地 Cookie 已清理 | 200 | 不返回错误，记录告警日志 |

#### Agent 使用

```http
GET  /api/agents/available
GET  /api/conversations?agentId={agentId}
POST /api/conversations
POST /api/conversations/{conversationId}/attachments
POST /api/conversations/{conversationId}/messages/stream
```

- `GET /api/agents/available`：返回当前租户已授权且启用的 Agent，用于 Agent 选择页展示可用 Agent 数量和列表。
- `GET /api/conversations?agentId={agentId}`：返回当前用户在指定 Agent 下的历史会话，用于统一对话页左侧“我的对话”。
- `POST /api/conversations`：根据 `agentId` 创建该 Agent 下的新会话。
- `POST /api/conversations/{conversationId}/attachments`：上传图片、文档、语音等附件，返回 `attachmentId`。
- `POST /api/conversations/{conversationId}/messages/stream`：发送用户消息与 `attachmentIds` 并返回 SSE 流。

##### GET /api/agents/available

请求：

```http
GET /api/agents/available
Cookie: agent_sso=...
```

成功响应：

```json
{
  "total": 2,
  "agents": [
    {
      "agentId": "dev-assistant",
      "name": "开发助手",
      "description": "辅助开发人员完成代码分析、设计评审和问题排查",
      "icon": "https://cdn.example.com/agents/dev-assistant.png",
      "status": "ENABLED",
      "protocol": "SSE"
    }
  ]
}
```

规则：

- 仅返回当前用户所属租户已授权且状态为 `ENABLED` 的 Agent。
- `tenantId` 从 Cookie 登录态解析，前端不得传入。
- 管理端禁用 Agent 后，该接口不再返回该 Agent；但历史会话仍可通过会话详情接口查看。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| SSO 校验不可用 | 503 | `SSO_UNAVAILABLE` |

##### GET /api/conversations?agentId={agentId}

请求：

```http
GET /api/conversations?agentId=dev-assistant
Cookie: agent_sso=...
```

成功响应：

```json
{
  "agentId": "dev-assistant",
  "conversations": [
    {
      "conversationId": "c-001",
      "title": "后端设计评审",
      "status": "ACTIVE",
      "createdAt": "2026-07-14T10:00:00+08:00",
      "updatedAt": "2026-07-14T10:30:00+08:00"
    }
  ]
}
```

规则：

- 只返回当前登录用户自己的会话。
- `agentId` 必填，用于统一对话页左侧只展示当前 Agent 下的历史会话。
- Agent 已停用时，历史会话仍可查询；但不能发起新的流式消息。
- 已物理删除的会话不返回。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| `agentId` 为空 | 400 | `AGENT_ID_REQUIRED` |
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |
| 租户未授权 | 403 | `AGENT_NOT_AUTHORIZED` |

##### POST /api/conversations

请求体：

```json
{
  "agentId": "dev-assistant"
}
```

成功响应：

```json
{
  "conversationId": "c-001",
  "agentId": "dev-assistant",
  "title": "新对话",
  "status": "ACTIVE",
  "createdAt": "2026-07-14T10:00:00+08:00"
}
```

规则：

- 后端根据当前登录态中的 `userId`、`tenantId` 创建会话。
- 创建前必须校验 Agent 存在、已启用，且当前租户已授权。
- 默认标题可为“新对话”，后续可在用户发送首条消息后异步更新。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| `agentId` 为空 | 400 | `AGENT_ID_REQUIRED` |
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |
| Agent 停用 | 409 | `AGENT_DISABLED` |
| 租户未授权 | 403 | `AGENT_NOT_AUTHORIZED` |

##### POST /api/conversations/{conversationId}/attachments

请求：

```http
POST /api/conversations/c-001/attachments
Content-Type: multipart/form-data
Cookie: agent_sso=...
```

表单字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 上传的附件文件 |
| `mediaType` | 是 | 附件媒体类型：`IMAGE` / `DOCUMENT` / `AUDIO` |

成功响应：

```json
{
  "attachmentId": "att-001",
  "conversationId": "c-001",
  "fileName": "design.pdf",
  "contentType": "application/pdf",
  "mediaType": "DOCUMENT",
  "size": 123456,
  "status": "ACTIVE"
}
```

规则：

- 上传前必须校验当前用户是否拥有该会话。
- 统一入口仅保存附件元信息和对象存储引用，不解析文件正文。
- 上传成功后附件允许暂不绑定消息，发送消息时再通过 `attachmentIds` 绑定到本次用户消息。
- 响应体不返回完整 `accessUrl`，避免前端日志或浏览器插件泄露存储桶地址。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 会话不存在 | 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | 403 | `CONVERSATION_FORBIDDEN` |
| 文件为空 | 400 | `ATTACHMENT_FILE_REQUIRED` |
| 类型不支持 | 400 | `ATTACHMENT_TYPE_UNSUPPORTED` |
| 文件超限 | 400 | `ATTACHMENT_TOO_LARGE` |
| 对象存储上传失败 | 502 | `ATTACHMENT_UPLOAD_FAILED` |

##### POST /api/conversations/{conversationId}/messages/stream

请求：

```http
POST /api/conversations/c-001/messages/stream
Content-Type: application/json
Accept: text/event-stream
Cookie: agent_sso=...
```

请求体：

```json
{
  "agentId": "dev-assistant",
  "message": "请结合附件内容分析这个接口设计",
  "attachmentIds": ["att-001", "att-002"],
  "clientMessageId": "cm-001"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `agentId` | 是 | 本次调用的目标 Agent |
| `message` | 是 | 用户输入文本 |
| `attachmentIds` | 否 | 已上传附件 ID 列表 |
| `clientMessageId` | 否 | 前端生成的消息 ID，用于本地气泡关联和重试幂等 |

成功响应：

```text
event: message_start
data: {"messageId":"cm-001"}

event: delta
data: {"text":"统一入口后端应先校验附件归属"}

event: message_end
data: {"messageId":"cm-001","status":"SUCCESS"}
```

失败事件：

```text
event: error
data: {"messageId":"cm-001","status":"FAILED","code":"AGENT_IDLE_TIMEOUT","reason":"目标 Agent 响应超时"}
```

规则：

- 流开始前必须完成登录态、会话归属、Agent 启停、租户授权和附件归属校验。
- 流开始前的准入失败直接返回 HTTP 错误，不返回 SSE。
- 用户消息保存成功后才发送 `message_start`。
- 目标 Agent 返回的文本分片经 Adapter 转换后，以统一前端 SSE `delta` 事件输出。
- 目标 Agent 2 分钟内无业务响应时，统一入口取消下游调用并返回 `AGENT_IDLE_TIMEOUT`。
- 流式结束、失败或停止时，统一入口保存完整或部分 Agent 回复。

失败响应：

| 场景 | HTTP/SSE 表现 | code |
|---|---|---|
| 会话不存在 | HTTP 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | HTTP 403 | `CONVERSATION_FORBIDDEN` |
| `agentId` 为空 | HTTP 400 | `AGENT_ID_REQUIRED` |
| `message` 为空 | HTTP 400 | `MESSAGE_REQUIRED` |
| Agent 不存在 | HTTP 404 | `AGENT_NOT_FOUND` |
| Agent 停用 | HTTP 409 | `AGENT_DISABLED` |
| 租户未授权 | HTTP 403 | `AGENT_NOT_AUTHORIZED` |
| 附件不存在或无权限 | HTTP 400/403 | `ATTACHMENT_NOT_FOUND` / `ATTACHMENT_FORBIDDEN` |
| 目标 Agent 响应超时 | SSE `error` | `AGENT_IDLE_TIMEOUT` |
| 下游协议解析失败 | SSE `error` | `AGENT_PROTOCOL_ERROR` |
| 流式中断 | SSE `error` | `AGENT_STREAM_INTERRUPTED` |

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
- 删除会话时需一并删除关联附件元信息，并按对象存储生命周期策略处理实际文件。

##### GET /api/conversations

请求：

```http
GET /api/conversations?agentId=dev-assistant
Cookie: agent_sso=...
```

查询参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `agentId` | 否 | 按 Agent 过滤；统一对话页左侧建议传入当前 Agent ID |

成功响应：

```json
{
  "conversations": [
    {
      "conversationId": "c-001",
      "agentId": "dev-assistant",
      "agentName": "开发助手",
      "title": "后端设计评审",
      "status": "ACTIVE",
      "createdAt": "2026-07-14T10:00:00+08:00",
      "updatedAt": "2026-07-14T10:30:00+08:00"
    }
  ]
}
```

规则：

- 只返回当前登录用户自己的会话。
- `agentId` 不传时返回当前用户全部未删除会话。
- `agentId` 传入时只返回该 Agent 下的会话。
- 已物理删除的会话不返回。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| `agentId` 不存在 | 404 | `AGENT_NOT_FOUND` |

##### GET /api/conversations/{conversationId}

请求：

```http
GET /api/conversations/c-001
Cookie: agent_sso=...
```

成功响应：

```json
{
  "conversationId": "c-001",
  "agentId": "dev-assistant",
  "agentName": "开发助手",
  "title": "后端设计评审",
  "status": "ACTIVE",
  "createdAt": "2026-07-14T10:00:00+08:00",
  "updatedAt": "2026-07-14T10:30:00+08:00",
  "messages": [
    {
      "messageId": "m-001",
      "role": "USER",
      "content": "帮我分析这个接口设计",
      "status": "SUCCESS",
      "createdAt": "2026-07-14T10:01:00+08:00",
      "attachments": [
        {
          "attachmentId": "att-001",
          "fileName": "design.pdf",
          "contentType": "application/pdf",
          "mediaType": "DOCUMENT",
          "size": 123456,
          "status": "ACTIVE"
        }
      ]
    },
    {
      "messageId": "m-002",
      "role": "ASSISTANT",
      "content": "这个后端设计的主干是清晰的。",
      "status": "SUCCESS",
      "createdAt": "2026-07-14T10:01:30+08:00",
      "attachments": []
    }
  ]
}
```

规则：

- 只允许查看当前登录用户自己的会话。
- Agent 停用后，历史会话仍可查看。
- 响应中的附件只返回展示所需元信息，不返回完整 `accessUrl`。
- 若消息状态为 `FAILED` 或 `STOPPED`，`content` 返回已保存的部分内容。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| 会话不存在 | 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | 403 | `CONVERSATION_FORBIDDEN` |

##### POST /api/conversations/{conversationId}/archive

请求：

```http
POST /api/conversations/c-001/archive
Cookie: agent_sso=...
```

成功响应：

```json
{
  "conversationId": "c-001",
  "status": "ARCHIVED",
  "updatedAt": "2026-07-14T10:40:00+08:00"
}
```

规则：

- 只允许归档当前登录用户自己的会话。
- 已归档会话仍可查看和导出。
- 已归档会话不建议继续发起新消息；如需继续对话，前端应创建新会话。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| 会话不存在 | 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | 403 | `CONVERSATION_FORBIDDEN` |

##### GET /api/conversations/{conversationId}/export

请求：

```http
GET /api/conversations/c-001/export
Cookie: agent_sso=...
```

成功响应：

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Disposition: attachment; filename="conversation-c-001.json"
```

```json
{
  "conversationId": "c-001",
  "agentId": "dev-assistant",
  "title": "后端设计评审",
  "exportedAt": "2026-07-14T11:00:00+08:00",
  "messages": [
    {
      "role": "USER",
      "content": "帮我分析这个接口设计",
      "createdAt": "2026-07-14T10:01:00+08:00"
    },
    {
      "role": "ASSISTANT",
      "content": "这个后端设计的主干是清晰的。",
      "status": "SUCCESS",
      "createdAt": "2026-07-14T10:01:30+08:00"
    }
  ],
  "attachments": [
    {
      "attachmentId": "att-001",
      "fileName": "design.pdf",
      "contentType": "application/pdf",
      "mediaType": "DOCUMENT",
      "size": 123456
    }
  ]
}
```

规则：

- 只允许导出当前登录用户自己的会话。
- 导出内容包含消息正文和附件元信息。
- 导出内容不包含 Cookie、Token、对象存储完整 `accessUrl` 或其他敏感信息。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| 会话不存在 | 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | 403 | `CONVERSATION_FORBIDDEN` |

##### DELETE /api/conversations/{conversationId}

请求：

```http
DELETE /api/conversations/c-001
Cookie: agent_sso=...
```

成功响应：

```json
{
  "success": true,
  "conversationId": "c-001"
}
```

规则：

- 只允许删除当前登录用户自己的会话。
- 删除为物理删除，会话和消息不再通过用户侧接口返回。
- 删除会话时一并删除附件元信息。
- 对象存储中的实际文件按存储桶生命周期策略清理，统一入口不强制同步删除对象文件。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| Cookie 缺失或无效 | 401 | `UNAUTHENTICATED` / `SESSION_EXPIRED` |
| 会话不存在 | 404 | `CONVERSATION_NOT_FOUND` |
| 会话不属于当前用户 | 403 | `CONVERSATION_FORBIDDEN` |

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

##### GET /api/admin/agents

请求：

```http
GET /api/admin/agents
Cookie: agent_sso=...
```

成功响应：

```json
{
  "agents": [
    {
      "agentId": "dev-assistant",
      "name": "开发助手",
      "description": "辅助开发人员完成代码分析、设计评审和问题排查",
      "icon": "https://cdn.example.com/agents/dev-assistant.png",
      "endpoint": "https://agent.example.com/dev/stream",
      "status": "ENABLED",
      "protocol": "SSE",
      "createdAt": "2026-07-14T10:00:00+08:00",
      "updatedAt": "2026-07-14T10:30:00+08:00"
    }
  ]
}
```

规则：

- 仅管理员可访问。
- 返回 Agent 管理元信息，不返回下游访问 Token 或敏感配置。

##### POST /api/admin/agents

请求体：

```json
{
  "agentId": "dev-assistant",
  "name": "开发助手",
  "description": "辅助开发人员完成代码分析、设计评审和问题排查",
  "icon": "https://cdn.example.com/agents/dev-assistant.png",
  "endpoint": "https://agent.example.com/dev/stream",
  "protocol": "SSE"
}
```

成功响应：

```json
{
  "agentId": "dev-assistant",
  "status": "ENABLED",
  "createdAt": "2026-07-14T10:00:00+08:00"
}
```

规则：

- 本期仅允许 `protocol=SSE`。
- 新增 Agent 默认状态为 `ENABLED`；如需先创建后启用，可由管理员创建后立即调用禁用接口。
- `endpoint` 必须是合法 HTTP/HTTPS 地址。
- 管理端写入成功后需要刷新内存缓存；刷新失败时不影响数据库保存，但代理链路继续使用上一次成功缓存。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| `agentId` 已存在 | 409 | `AGENT_ALREADY_EXISTS` |
| 必填字段为空 | 400 | `AGENT_FIELD_REQUIRED` |
| 协议不支持 | 400 | `AGENT_PROTOCOL_UNSUPPORTED` |
| endpoint 非法 | 400 | `AGENT_ENDPOINT_INVALID` |

##### PUT /api/admin/agents/{agentId}

请求体：

```json
{
  "name": "开发助手",
  "description": "辅助开发人员完成代码分析、设计评审和问题排查",
  "icon": "https://cdn.example.com/agents/dev-assistant.png",
  "endpoint": "https://agent.example.com/dev/stream",
  "protocol": "SSE"
}
```

成功响应：

```json
{
  "agentId": "dev-assistant",
  "status": "ENABLED",
  "updatedAt": "2026-07-14T10:30:00+08:00"
}
```

规则：

- 仅管理员可更新 Agent 元信息。
- 本期不允许修改 `agentId`。
- 更新 `endpoint` 或 `protocol` 后，需要刷新内存缓存。
- 若 Agent 正在被调用，已建立的流式请求继续使用请求开始时选定的配置；新请求使用刷新后的配置。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |
| 必填字段为空 | 400 | `AGENT_FIELD_REQUIRED` |
| 协议不支持 | 400 | `AGENT_PROTOCOL_UNSUPPORTED` |
| endpoint 非法 | 400 | `AGENT_ENDPOINT_INVALID` |

##### POST /api/admin/agents/{agentId}/enable

请求：

```http
POST /api/admin/agents/dev-assistant/enable
Cookie: agent_sso=...
```

成功响应：

```json
{
  "agentId": "dev-assistant",
  "status": "ENABLED",
  "updatedAt": "2026-07-14T10:40:00+08:00"
}
```

规则：

- 启用后，已授权租户可发起新的对话和流式消息。
- 启用状态更新后需要刷新内存缓存。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |

##### POST /api/admin/agents/{agentId}/disable

请求：

```http
POST /api/admin/agents/dev-assistant/disable
Cookie: agent_sso=...
```

成功响应：

```json
{
  "agentId": "dev-assistant",
  "status": "DISABLED",
  "updatedAt": "2026-07-14T10:45:00+08:00"
}
```

规则：

- 禁用后，不允许发起新的会话和流式消息。
- 历史会话仍允许用户查看、导出和删除。
- 已建立的流式请求继续使用请求开始时选定的配置，不主动中断；禁用只阻断新请求。
- 禁用状态更新后需要刷新内存缓存。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |

##### GET /api/admin/tenants/{tenantId}/agents

请求：

```http
GET /api/admin/tenants/t-001/agents
Cookie: agent_sso=...
```

成功响应：

```json
{
  "tenantId": "t-001",
  "agents": [
    {
      "agentId": "dev-assistant",
      "name": "开发助手",
      "enabled": true,
      "agentStatus": "ENABLED"
    }
  ]
}
```

规则：

- 仅管理员可查询租户 Agent 授权。
- `enabled=true` 表示租户已授权该 Agent；`agentStatus` 表示 Agent 自身启停状态。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| 租户不存在 | 404 | `TENANT_NOT_FOUND` |

##### PUT /api/admin/tenants/{tenantId}/agents

请求体：

```json
{
  "agents": [
    {
      "agentId": "dev-assistant",
      "enabled": true
    },
    {
      "agentId": "disabled-agent",
      "enabled": false
    }
  ]
}
```

成功响应：

```json
{
  "tenantId": "t-001",
  "updatedAgents": [
    {
      "agentId": "dev-assistant",
      "enabled": true
    },
    {
      "agentId": "disabled-agent",
      "enabled": false
    }
  ],
  "updatedAt": "2026-07-14T11:00:00+08:00"
}
```

规则：

- 仅管理员可更新租户 Agent 授权。
- 本接口按请求体覆盖指定 Agent 的授权状态，不要求一次性覆盖租户全部 Agent。
- 授权关闭后，用户不能再发起该 Agent 的新会话和流式消息；历史会话仍可查看。
- 写入成功后需要刷新租户授权缓存；刷新失败时，代理链路继续使用上一次成功缓存。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| 租户不存在 | 404 | `TENANT_NOT_FOUND` |
| Agent 不存在 | 404 | `AGENT_NOT_FOUND` |
| 请求体为空 | 400 | `TENANT_AGENT_AUTH_REQUIRED` |

##### GET /api/admin/audits/agent-access

请求：

```http
GET /api/admin/audits/agent-access?tenantId=t-001&agentId=dev-assistant&from=2026-07-01T00:00:00+08:00&to=2026-07-31T23:59:59+08:00
Cookie: agent_sso=...
```

查询参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `tenantId` | 否 | 按租户过滤 |
| `agentId` | 否 | 按 Agent 过滤 |
| `userId` | 否 | 按用户过滤 |
| `from` | 否 | 访问时间起点 |
| `to` | 否 | 访问时间终点 |

成功响应：

```json
{
  "audits": [
    {
      "auditId": "audit-001",
      "userId": "u-001",
      "username": "Yann Chen",
      "tenantId": "t-001",
      "tenantName": "Yann Cloud",
      "agentId": "dev-assistant",
      "agentName": "开发助手",
      "accessTime": "2026-07-14T10:01:00+08:00"
    }
  ]
}
```

规则：

- 仅管理员可查询访问审计。
- 审计只返回访问元信息，不返回完整消息内容、附件 URL、Cookie 或 Token。
- 查询时间范围过大时，可由后端限制最大跨度；超过限制返回 `AUDIT_RANGE_TOO_LARGE`。

失败响应：

| 场景 | HTTP 状态 | code |
|---|---:|---|
| 非管理员访问 | 403 | `ADMIN_REQUIRED` |
| 时间范围非法 | 400 | `AUDIT_TIME_RANGE_INVALID` |
| 时间范围过大 | 400 | `AUDIT_RANGE_TOO_LARGE` |

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
  -> 用户上传附件/语音到 /api/conversations/{id}/attachments
  -> 后端返回 attachmentId
  -> POST /api/conversations/{id}/messages/stream
  -> CookieAuthWebFilter 校验 Cookie
  -> AgentProxyController
  -> AgentService 校验 Agent 存在且启用
  -> TenantAuthorizationService 校验当前租户已授权 Agent
  -> ConversationService 保存用户消息
  -> AttachmentService 校验 attachmentIds 并绑定附件元信息
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
| 附件不存在或无权限 | 返回 `ATTACHMENT_NOT_FOUND` / `ATTACHMENT_FORBIDDEN` | 提示附件不可用 |
| 附件状态不可用 | 返回 `ATTACHMENT_NOT_ACTIVE` | 提示附件不可用 |
| 附件类型非法 | 返回 `ATTACHMENT_TYPE_UNSUPPORTED` | 提示不支持该文件类型 |
| 附件超限 | 返回 `ATTACHMENT_TOO_LARGE` | 提示附件过大 |
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
| JDBC/MyBatis | 数据访问 | 采用团队熟悉的传统数据访问方案，并通过专用阻塞线程池隔离阻塞 I/O |
| 公司 OAuth2 SSO | 登录认证 | 遵循现有统一登录体系 |

数据访问建议：

- 本项目采用传统 JDBC/MyBatis，不引入 R2DBC。
- 所有数据库、对象存储元信息、审计和配置保存等阻塞 I/O 必须通过专用线程池隔离，避免阻塞 WebFlux 事件循环。
- 附件内容建议落对象存储，数据库仅保存元信息和访问引用。
- 配置管理、Agent 元数据维护、租户授权维护等冷路径模块可以使用 JDBC/MyBatis，管理端写入后刷新到内存缓存即可。
- Agent 代理转发热路径优先读取已加载到内存的 Agent、授权和附件引用信息；必要的消息保存、附件绑定和审计写入必须通过阻塞 I/O 线程池执行。
- 若管理配置刷新失败，代理链路应继续使用上一次成功加载的内存配置，避免配置波动影响在线转发。

阻塞 I/O 线程池隔离：

- 统一定义专用线程池，建议命名为 `agent-blocking-io-*`。
- 线程池仅用于 JDBC/MyBatis、附件元信息读写、审计写入、管理配置保存和配置刷新等可能阻塞的 I/O。
- WebFlux event loop 只负责请求接收、SSE 输出、WebClient 非阻塞调用和流式编排，不直接执行阻塞 I/O。
- Controller 不直接选择线程池，建议由 Service 层或统一的 `BlockingTaskExecutor` 封装阻塞调用。
- 线程数、队列长度、超时时间和拒绝策略放入 `application-*.yml`，默认拒绝策略建议使用 `CallerRunsPolicy` 或明确转换为业务失败。
- `delta` 分片只在内存中累积，不在每个分片上写库。
- 用户消息保存失败时不调用目标 Agent，直接返回 HTTP 错误。
- Agent 回复保存失败时返回 SSE `error`，记录失败日志；如需要补偿，可后续扩展补偿任务。
- 审计写入失败不阻断主链路，但必须记录错误日志和指标。

### 运行时边界

为了避免把阻塞访问带入代理热路径，建议将系统分成两类路径：

- 热路径：登录态校验、Agent 选择、消息转发、SSE 输出、附件引用解析。
- 冷路径：Agent 配置管理、租户授权管理、访问审计查询、配置刷新。

其中：

- 冷路径允许使用 JDBC/MyBatis 访问数据库，但仍需通过阻塞 I/O 线程池隔离。
- 冷路径写入后，通过刷新机制将配置同步到内存。
- 热路径不得在 WebFlux event loop 上执行阻塞数据库或对象存储操作。
- 热路径不得在每个 `delta` 分片上写库。
- 热路径中的必要写入包括用户消息保存、Agent 最终回复保存、失败/停止状态保存、附件绑定和审计写入，这些写入必须通过阻塞 I/O 线程池执行。
- Agent 启停、租户授权等路由类校验优先基于内存缓存或已加载配置完成。

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

1. 建表：`conversation`、`message`、`attachment`。
2. 实现会话创建、列表、详情、归档、导出、物理删除。
3. 会话列表接口支持按 `agentId` 过滤，统一对话页左侧只展示当前 Agent 下的历史会话。
4. 实现用户只能访问自己会话的权限校验。
5. 支持 Agent 停用后历史会话仍可查看。
6. 实现附件上传、附件校验、附件下载和附件与消息绑定。

### 阶段 4：流式代理

1. 定义 `AgentAdapter`、`AgentInvokeRequest`、`TargetAgentRequestBody`、`AgentStreamChunk`。
2. 仅实现默认 `HttpSseAgentAdapter`，本期目标协议仅支持 `SSE`。
3. 实现 `AgentProxyService.stream()`。
4. 实现 SSE Controller：`POST /api/conversations/{conversationId}/messages/stream`。
5. 实现用户消息保存、Agent 回复累积保存、失败保存。
6. 将 `attachmentIds` 转换为 `AgentAttachmentRef` 并随请求透传给目标 Agent。
7. 支持目标 Agent 响应超时、取消和资源释放。

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
- 附件下载、权限校验和对象存储访问需要额外设计。

应对：

- 前端固定 SSE，后端用 Adapter 屏蔽目标 Agent 差异。
- 数据库写入不放在高频 `delta` 链路中。
- `doOnError`、`doFinally` 必须覆盖失败和取消。
- MVP 前端直连统一入口后端，暂不引入网关缓冲问题。
- 附件先上传再引用，避免把二进制内容塞入 SSE 链路。

### SSO/Cookie 风险

风险：

- Cookie 内容、有效期、续期策略依赖公司 SSO 规范。
- 目标 Agent 当前接受统一入口透传的用户 `accessToken`。

应对：

- 登录态 Cookie 完全遵循 SSO 标准。
- 统一入口内部只依赖 `UserContext`。
- 统一入口调用目标 Agent 时，将 `accessToken` 放入请求头 `Authorization`，格式为 `Bearer eyJ...`。
- 附件元信息与对象存储访问控制需保证当前用户只能访问自己租户下的资源。

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
- 前端只传 `agentId`、`conversationId`、`message`、`attachmentIds`。
- 管理接口不提供用户完整对话查询能力。
- 所有 `/api/admin/**` 统一校验 `role == ADMIN`。
- 附件上传、下载和转发必须校验 `conversationId`、`tenantId`、`userId` 归属关系。

## 待确认事项

1. 公司 SSO Cookie 的名称、有效期、续期和清理规范。
2. SSO 返回的角色字段和租户字段格式。
3. 审计记录时机和审计失败策略后续详谈。
