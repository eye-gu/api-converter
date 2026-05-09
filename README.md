# API Converter

大模型 API 代理转换服务 —— 将 OpenAI Response API 格式转换为 ChatCompletion 格式，让只支持 Response API 的工具（如 Codex）可以使用任意 ChatCompletion 兼容的后端 LLM Provider。

## 架构概览

```
┌──────────┐    Response API     ┌─────────────────┐    ChatCompletion     ┌──────────────┐
│  Codex   │ ──────────────────> │  API Converter   │ ────────────────────> │  LLM Provider│
│  (Client)│ <────────────────── │  (Proxy Server)  │ <──────────────────── │  (Upstream)  │
└──────────┘    Response API     └─────────────────┘    ChatCompletion     └──────────────┘
```

**核心功能：**
- 接收 OpenAI Response API (`/v1/responses`) 格式的请求
- 转换为 ChatCompletion 格式转发给上游 LLM
- 将上游响应转回 Response API 格式返回给客户端
- 支持 SSE 流式传输
- 支持 `previous_response_id` 多轮对话上下文恢复
- 内置会话管理 Web UI

## 项目结构

```
src/main/java/com/apiconverter/
├── ApiConverterApplication.java          # Spring Boot 启动类
├── config/
│   ├── UpstreamConfig.java               # 上游 Provider 配置（URL、API Key、模型映射）
│   └── WebClientConfig.java              # WebClient 配置（超时、连接池）
├── controller/
│   ├── ResponseApiController.java        # HTTP 入口，处理 /v1/responses
│   └── AdminController.java              # 会话管理页面入口
├── converter/
│   └── ResponseToChatConverter.java      # 格式转换核心：Response ↔ ChatCompletion
├── model/
│   ├── chat/
│   │   ├── ChatCompletionRequest.java    # ChatCompletion 请求 DTO
│   │   └── ChatCompletionResponse.java   # ChatCompletion 响应 DTO
│   └── response/
│       ├── ResponseApiRequest.java       # Response API 请求 DTO
│       ├── ResponseApiResponse.java      # Response API 响应 DTO
│       └── ResponseStreamEvent.java      # Response API 流式事件 DTO
├── service/
│   └── ProxyService.java                # 代理核心逻辑（非流式 + 流式）
└── store/
    ├── ResponseStore.java               # 存储接口（可扩展多种后端）
    └── InMemoryResponseStore.java       # 内存实现（默认）
```

## 设计要点

### 格式转换

| Response API | ChatCompletion | 说明 |
|---|---|---|
| `input: "text"` | `messages: [{role: "user", content: "text"}]` | 字符串输入转为 user message |
| `input: [{role, content}]` | `messages: [...]` | 数组输入直接映射 |
| `instructions` | `messages: [{role: "system", content: "..."}]` | 系统指令转为 system message |
| `previous_response_id` | 从 store 恢复历史 messages | 多轮对话上下文恢复 |
| `max_output_tokens` | `max_tokens` | 参数名映射 |
| `output[].content[].text` | `choices[0].message.content` | 响应输出映射 |

### 流式处理

流式模式下，服务将上游 ChatCompletion 的 SSE 流实时转换为 Response API 的事件流格式：

```
response.created → response.output_item.added → response.content_part.added
→ response.output_text.delta (持续) → response.content_part.done → response.output_item.done → response.completed
```

### previous_response_id 支持

当请求携带 `previous_response_id` 时：
1. 从 store 中查找历史响应
2. 恢复历史 input 和 output 作为 messages
3. 拼接当前 input 后发送给上游
4. 新响应的 id 可作为下一次请求的 `previous_response_id`

### 存储层架构

`ResponseStore` 是一个接口，支持通过配置切换不同的存储后端：

```yaml
store:
  type: memory    # 当前支持: memory（默认）
```

**接口定义：**

| 方法 | 说明 |
|---|---|
| `store(response, inputMessages, previousResponseId)` | 存储响应及关联信息 |
| `get(responseId)` | 获取单个响应 |
| `getInputHistory(responseId)` | 获取某次请求的输入消息 |
| `getPreviousResponseId(responseId)` | 获取父响应 ID（用于追溯会话链） |
| `listResponses()` | 列出所有已存储的响应 |
| `getConversationMessages(responseId)` | 追溯会话链，返回完整对话历史 |

**扩展新存储后端：**
1. 实现 `ResponseStore` 接口
2. 添加 `@ConditionalOnProperty(name = "store.type", havingValue = "xxx")` 注解
3. 在 `application.yml` 中设置 `store.type: xxx`

例如添加 Redis 支持：
```java
@Component
@ConditionalOnProperty(name = "store.type", havingValue = "redis")
public class RedisResponseStore implements ResponseStore { ... }
```

## 快速开始

### 前置条件

- Java 17+
- Maven 3.6+

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
upstream:
  base-url: https://api.openai.com    # 上游 LLM 地址
  api-key: your-api-key-here          # 上游 API Key
```

或通过环境变量设置：

```bash
export UPSTREAM_API_KEY=sk-your-key
```

### 运行

```bash
mvn spring-boot:run
```

服务默认启动在 `http://localhost:8080`。

### 使用

将 Codex 或其他 Response API 客户端的 base_url 指向本服务：

```bash
export OPENAI_BASE_URL=http://localhost:8080
export OPENAI_API_KEY=your-upstream-key
```

#### 非流式请求示例

```bash
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "input": "Hello, world!"
  }'
```

#### 流式请求示例

```bash
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "input": "Hello, world!",
    "stream": true
  }'
```

#### 多轮对话示例

```bash
# 第一轮
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "input": "给我讲个笑话",
    "store": true
  }'
# 返回 {"id": "resp_xxx", ...}

# 第二轮（引用上一轮）
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "input": "再讲一个",
    "store": true,
    "previous_response_id": "resp_xxx"
  }'
```

## 会话管理页面

服务内置了一个 Web 管理界面，用于查看和管理会话：

访问 `http://localhost:8080/admin/`

**功能：**
- 查看所有已存储的 Response 会话列表
- 查看每个会话的 ID、模型、状态、创建时间和内容预览
- 点击「View Conversation」查看完整的对话历史（包括通过 `previous_response_id` 链接的多轮对话）

**管理 API：**

| 端点 | 说明 |
|---|---|
| `GET /admin/api/responses` | 获取所有会话列表 |
| `GET /admin/api/responses/{id}` | 获取单个响应详情 |
| `GET /admin/api/responses/{id}/conversation` | 获取完整对话历史 |

## 模型映射

可在 `application.yml` 中配置模型名称映射，将客户端请求的模型名映射为上游实际使用的模型名：

```yaml
upstream:
  model-mapping:
    "gpt-4o": "gpt-4o"
    "gpt-4o-mini": "gpt-4o-mini"
```

如果请求的模型名不在映射表中，则原样传递给上游。

## 技术栈

- **Java 17** + **Spring Boot 3.4** + **WebFlux** (响应式 Web 框架)
- **WebClient** (响应式 HTTP 客户端，天然支持 SSE 流式)
- **Jackson** (JSON 序列化/反序列化)
- **Lombok** (减少样板代码)

## 后续扩展

当前架构预留了扩展点：
- 新增 `converter/` 下的转换器即可支持 Anthropic Messages 格式
- `ResponseStore` 接口支持扩展为 Redis、数据库等持久化存储
- 模型路由、负载均衡等可在 `ProxyService` 中扩展
