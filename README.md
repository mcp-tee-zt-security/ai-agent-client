# AI Agent Client

Spring Boot 기반 AI Agent Client 프로젝트입니다.

Ollama의 Qwen3 모델과 MCP Server를 연결하여 자연어 요청을 MCP Tool 호출로 처리합니다.

## Overview

```text
                         Ollama :11434
                         Qwen3:8b
                              ^
                              |
                    LLM request / response
                              |
                              |
User --> AI Agent Client :8081
                  |
                  | MCP
                  v
           MCP Server :8080
                  |
                  v
           Java Services
                  |
                  v
             Redis :6379
```

## Technology

* Java 17
* Spring Boot 4.1.1
* Spring AI 2.0.1
* Spring Web MVC
* Spring AI MCP Client
* Spring AI Ollama
* Ollama
* Qwen3:8b

## Configuration

`application.properties`

```properties
spring.application.name=ai-agent-client

server.port=8081

spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=qwen3:8b

spring.ai.mcp.client.streamable-http.connections.order-server.url=http://localhost:8080
```

## Running

The following components should be running:

```text
Redis        :6379
MCP Server   :8080
Ollama       :11434
```

Then start the AI Agent Client:

```powershell
mvn spring-boot:run
```

Client:

```text
http://localhost:8081
```

## Request Flow

The AI Agent Client receives a user's natural-language request.

The client sends the request to Qwen3 through Ollama together with the available MCP tool definitions.

Qwen3 decides which tool should be used.

The MCP Client invokes the selected tool on the MCP Server.

The MCP Server executes the Java business logic.

The Java service accesses Redis when required.

The tool result is returned to Qwen3.

Qwen3 generates the final natural-language response.

```text
User
 |
 v
AI Agent Client
 |
 +----------------------+
 |                      |
 v                      v
Ollama / Qwen3       MCP Client
                         |
                         | MCP
                         v
                    MCP Server
                         |
                         v
                    Java Tools
                         |
                         v
                       Redis
```

## Important Concept

The LLM does not directly access Redis.

The LLM also does not execute Java code.

Instead:

```text
LLM
 |
 | Tool selection
 v
MCP Client
 |
 | MCP request
 v
MCP Server
 |
 | Java method execution
 v
Redis / Backend
```

The LLM receives tool definitions such as:

```text
cancelOrder(orderId)
```

It does not receive the implementation:

```java
redisTemplate.opsForHash()
```

The actual implementation remains on the MCP Server.

## Example: Order Status

User:

```text
What is the status of order ORD-1001?
```

Flow:

```text
1. AI Agent Client receives the question.

2. The request and available tool definitions are sent
   to Qwen3 through Ollama.

3. Qwen3 determines that getOrderStatus is appropriate.

4. MCP Client invokes:

   getOrderStatus("ORD-1001")

5. MCP Server executes OrderService.

6. OrderService reads the order from Redis.

7. Redis returns:

   FILLED

8. The tool result is returned to Qwen3.

9. Qwen3 generates the final response.
```

## Example: Multi-Tool Agent

User:

```text
What are the orders for CUST-001
and what is the payment status of each order?
```

The Agent can perform multiple tool calls:

```text
getCustomerOrders("CUST-001")
        |
        +-- ORD-1001
        +-- ORD-1002
                |
                v
getPaymentStatus("ORD-1001")
getPaymentStatus("ORD-1002")
                |
                v
          Qwen3 summarizes
```

Example response:

```text
ORD-1001: PAID
ORD-1002: PAYMENT_PENDING
```

## Example: Action Tool

User:

```text
Cancel order ORD-1002
```

Flow:

```text
Qwen3
  |
  | selects cancelOrder("ORD-1002")
  v
MCP Client
  |
  | MCP
  v
MCP Server
  |
  v
OrderService
  |
  v
Redis
  |
  +-- PENDING -> CANCELLED
```

The LLM does not directly modify Redis.

The Java service validates the order and performs the state change.

## Example: Agentic Workflow

A more complex request can combine several tools:

```text
Show me all pending orders and cancel the ones
that can be cancelled.
```

Possible workflow:

```text
getOrders("PENDING")
        |
        v
   ORD-1002
        |
        v
cancelOrder("ORD-1002")
        |
        v
     CANCELLED
        |
        v
Qwen3 summarizes the result
```

This demonstrates the difference between a simple LLM chatbot and an Agent that can select and invoke tools.

## Available MCP Tools

```text
getOrderStatus(orderId)
getOrders(status)
cancelOrder(orderId)
getCustomerOrders(customerId)
getPaymentStatus(orderId)
```

## Test Examples

### Order Status

```text
http://localhost:8081/ai?question=What%20is%20the%20status%20of%20order%20ORD-1001?
```

### Customer Orders

```text
http://localhost:8081/ai?question=Show%20me%20all%20orders%20for%20customer%20CUST-001
```

### Pending Orders

```text
http://localhost:8081/ai?question=Show%20me%20all%20pending%20orders
```

### Payment Status

```text
http://localhost:8081/ai?question=What%20is%20the%20payment%20status%20of%20ORD-1001?
```

## Why Use an LLM?

A normal REST API is usually simpler for a fixed operation.

The Agent becomes useful when a user provides a natural-language request that may require different tools or multiple operations.

The LLM handles:

* Natural-language understanding
* Tool selection
* Parameter extraction
* Multi-step tool orchestration
* Final response generation

The MCP Server and Java services remain responsible for:

* Business logic
* Validation
* Data access
* State changes

This separation allows the LLM to handle reasoning and tool selection while the backend remains responsible for actual business operations.
