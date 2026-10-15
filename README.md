# Aperture

[![Build](https://github.com/vex-rune/aperture/actions/workflows/build.yml/badge.svg)](https://github.com/vex-rune/aperture/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1%2B-brightgreen.svg)](https://spring.io/projects/spring-boot)

> 中文 | [English](./README_EN.md)

**Aperture** 是一个面向 Spring Boot 4 的注解驱动型 WebSocket 帧路由框架。

它借鉴了 Spring 的 `@MessageMapping` 设计思路：用 `@AprController` 声明 WebSocket 端点，用 `@AprTextFrame` / `@AprByteFrame` 声明方法级帧路由，启动期自动扫描并注册到 Spring 的 `WebSocketHandlerRegistry`，业务层几乎零配置即可开始处理文本帧与二进制帧。

---

## 目录

- [特性](#特性)
- [模块组成](#模块组成)
- [安装](#安装)
- [快速开始](#快速开始)
- [核心注解](#核心注解)
- [配置说明](#配置说明)
- [多通道配置](#多通道配置)
- [协议与错误码](#协议与错误码)
- [运行 Demo](#运行-demo)
- [构建与测试](#构建与测试)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

---

## 特性

- **注解驱动**：类 Spring 的声明式路由，文本帧 / 二进制帧统一处理。
- **自动装配**：starter 自动完成 `@AprController` 扫描、`WebSocketHandler` 注册与拦截器链组装。
- **多通道**：一个工程可并存多条 WebSocket 通道（如 ASR、Agent、Device），每条通道独立配置前缀、超时、拦截器。
- **内置拦截器**：Trace（握手期写入 MDC，日志贯穿会话）与 CORS（OPTIONS 预检）。
- **参数校验**：`@AprPayload` 自动完成 Jackson 反序列化 + JSR-303 Bean Validation。
- **可扩展**：不依赖 starter 时，直接实现 `ApertureControllerConfigurer` 接口即可自定义全局与单 controller 行为。
- **零业务侵入**：core 与 starter 均不含业务代码，只提供框架能力。

---

## 模块组成

```
aperture/                                      ← Maven 多模块聚合工程
├── aperture-core/                             ← 框架核心：注解、基类、反射路由、适配层
├── aperture-spring-boot-starter/              ← 自动装配 + ApertureProperties + 内置拦截器
└── aperture-demo/                             ← 可直接运行的最小示例
```

| 模块 | 作用 | 是否随 Release 发布 |
|---|---|---|
| `aperture-core` | 注解 / 基类 / 反射路由 / Spring `WebSocketHandlerRegistry` 适配层；无业务代码 | ✅ |
| `aperture-spring-boot-starter` | 一键开箱：`ApertureConfigurer` 工厂 + Trace / CORS 拦截器 | ✅ |
| `aperture-demo` | 最小可运行示例，默认不打 fat jar | 仅作为参考 |

---

## 安装

### Maven

> 请将 `VERSION` 替换为 [GitHub Releases](https://github.com/vex-rune/aperture/releases) 中的最新版本号；当前源码版本为 `1.0.0-SNAPSHOT`。

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-spring-boot-starter</artifactId>
    <version>VERSION</version>
</dependency>
```

如果只需要核心能力、不需要 starter 的自动装配与内置拦截器：

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>VERSION</version>
</dependency>
```

### 从 GitHub Packages 拉取

发布包托管在 GitHub Packages，需要在 `~/.m2/settings.xml` 中配置访问凭据：

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>ghp_xxx</password> <!-- PAT，至少需要 read:packages 权限 -->
    </server>
  </servers>
</settings>
```

同时在 `pom.xml` 中添加仓库：

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/vex-rune/aperture</url>
  </repository>
</repositories>
```

### Gradle

```kotlin
dependencies {
    implementation("com.vex.aperture:aperture-spring-boot-starter:VERSION")
}
```

---

## 快速开始

只需 3 步即可跑通一个 Echo 服务。

### 1. 引入依赖

见 [安装](#安装)。

### 2. 编写 Controller

```java
import com.vex.aperture.channel.AbstractChannel;
import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.AprByteFrame;
import com.vex.aperture.channel.AprController;
import com.vex.aperture.channel.AprPayload;
import com.vex.aperture.channel.AprTextFrame;
import com.vex.aperture.channel.BinaryFrame;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@AprController("/ws/demo")
public class EchoController extends AbstractChannelHandler<EchoController.Session> {

    public EchoController(JsonMapper jsonMapper, jakarta.validation.Validator validator) {
        super(jsonMapper, validator, java.util.List.of());
    }

    @jakarta.annotation.PostConstruct
    void scanSelf() {
        super.registerSelf(this);
    }

    @Override
    protected String channel() {
        return "ws.demo";
    }

    @Override
    protected Session newChannel(org.springframework.web.socket.WebSocketSession session) {
        return new Session(session, jsonMapper);
    }

    @AprTextFrame("echo")
    public void onEcho(Session session, @AprPayload EchoBody body) {
        session.send("echo.reply", com.vex.aperture.channel.Channel.payload("echo", body.text()));
    }

    @AprByteFrame(0x01)
    public void onBinary(Session session, BinaryFrame frame) {
        session.send("binary.reply", com.vex.aperture.channel.Channel.payload(
                "frameType", frame.frameType(),
                "bytes", frame.payload().length));
    }

    public static final class Session extends AbstractChannel {
        public Session(org.springframework.web.socket.WebSocketSession session, JsonMapper jsonMapper) {
            super(session, jsonMapper);
        }
    }

    public record EchoBody(@NotBlank String text) {}
}
```

### 3. 编写配置与 YAML

```java
import com.vex.aperture.autoconfigure.ApertureControllerConfigurer;
import com.vex.aperture.starter.ApertureProperties;
import com.vex.aperture.starter.configurer.ApertureConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ApertureProperties.class)
public class WebSocketConfig {

    @Bean
    public ApertureControllerConfigurer demoConfigurer(ApertureProperties props) {
        return ApertureConfigurer.create("demo")
                .pathPrefix("/ac")
                .useTrace(props)
                .useCors(props, "*")
                .idleTimeout(Duration.ofMinutes(5))
                .build();
    }
}
```

```yaml
aperture:
  enabled: true
  base-packages: com.example.demo
  path-prefix: /ac
  allowed-origins: "*"
  idle-timeout: 5m
  interceptors:
    trace:
      enabled: true
      key: X-Trace-Id
    cors:
      enabled: true

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [traceId=%X{traceId:-}] %-5level %logger{36} - %msg%n"
```

启动后，用浏览器或 WebSocket 客户端连接：

```
ws://localhost:8080/ac/ws/demo
```

发送文本帧：

```json
{ "frame": "echo", "payload": { "text": "hello" } }
```

服务端将回复：

```json
{ "frame": "echo.reply", "payload": { "echo": "hello" } }
```

---

## 核心注解

| 注解 | 类比 Spring | 作用 |
|---|---|---|
| `@AprController(path)` | `@RestController` + `@RequestMapping` | 声明 WebSocket 端点 URL |
| `@AprTextFrame("type")` | `@MessageMapping` | 声明文本帧路由，按 `frame` 字段匹配 |
| `@AprByteFrame(N)` | `@MessageMapping`（binary） | 声明二进制帧路由，按首字节匹配 |
| `@AprPayload` | `@Valid @RequestBody` | 方法形参：Jackson 反序列化 + Bean Validation |

---

## 配置说明

### `ApertureProperties` 全量 YAML

```yaml
aperture:
  enabled: true                       # 是否启用 Aperture 自动扫描，默认 true
  base-packages: com.example.demo     # 扫描 @AprController 的基础包，可逗号分隔多个包
  path-prefix: /ac                    # 全局 WebSocket 路径前缀
  allowed-origins: "*"                # 默认允许源（可被 ApertureConfigurer.useCors 覆盖）
  idle-timeout: 5m                    # 默认握手空闲超时
  interceptors:
    trace:
      enabled: true                   # 是否启用 Trace 拦截器
      key: X-Trace-Id                 # 读取 traceId 的请求头名
    cors:
      enabled: true                   # 是否启用 CORS 拦截器
```

### `ApertureConfigurer` 链式 API

| 方法 | 说明 |
|---|---|
| `create(String name)` | 按通道名创建 builder |
| `pathPrefix(String)` | 全局路径前缀 |
| `useTrace(ApertureProperties)` | 根据 YAML 启用 Trace 拦截器 |
| `useTrace(boolean)` | 显式启用/关闭 Trace 拦截器 |
| `traceMdcKey(String)` | 自定义 MDC key |
| `useCors(ApertureProperties, String...)` | 根据 YAML + 业务 origins 启用 CORS 拦截器 |
| `useCors(boolean, String...)` | 显式启用/关闭 CORS 拦截器 |
| `idleTimeout(Duration)` | 握手空闲超时 |
| `addInterceptors(HandshakeInterceptor...)` | 追加自定义拦截器 |
| `build()` | 装配出 `ApertureControllerConfigurer` 实例 |

---

## 多通道配置

Aperture 默认你的工程会有多条 WebSocket 通道，因此每条通道都需要独立创建一个 `ApertureConfigurer` Bean：

```java
@Bean
public ApertureControllerConfigurer asrConfigurer(ApertureProperties props) {
    return ApertureConfigurer.create("asr")
            .pathPrefix("/ac")
            .useTrace(props)
            .useCors(props, "*.example.com")
            .build();
}

@Bean
public ApertureControllerConfigurer agentConfigurer(ApertureProperties props) {
    return ApertureConfigurer.create("agent")
            .pathPrefix("/ac")            // 与 asr 共用 prefix 时必须使用同一值
            .useTrace(props)
            .useCors(props, "*")
            .idleTimeout(Duration.ofMinutes(10))
            .build();
}
```

> ⚠️ 同一个全局属性（如 `path-prefix`）**不允许被多个 configurer 设置成不同的值**，否则启动期会抛出 `IllegalStateException`。

---

## 协议与错误码

### 文本帧协议格式

```json
{
  "frame": "echo",
  "payload": { "text": "hello" }
}
```

- `frame`：路由键，匹配 `@AprTextFrame("echo")`。
- `payload`：业务负载，由 `@AprPayload` 形参接收并反序列化。

### 二进制帧协议格式

框架只读取首字节作为 `frameType` 路由键，剩余字节原样交给业务方法：

```
[ frameType: 1 byte ][ payload: N bytes ]
```

### 错误响应

| 场景 | 行为 |
|---|---|
| 同一 `frame` 重复绑定 | 启动期 `IllegalStateException`，服务无法启动 |
| `@AprPayload` 校验失败 | 自动回复 `error{4400, "..."}` |
| 收到未知 `frame` | 自动回复 `error{4401, "未知报文类型 X"}` |
| 全局 `path-prefix` 被多 configurer 冲突设置 | 启动期 `IllegalStateException` |

---

## 运行 Demo

```bash
# 安装 core + starter + demo
mvn clean install -DskipTests

# 启动 demo（默认端口 8099）
mvn -pl aperture-demo spring-boot:run
```

启动后：

- 浏览器调试页：[http://localhost:8099/](http://localhost:8099/)
- WebSocket 端点：`ws://localhost:8099/ac/ws/demo`

在浏览器控制台或任意 WebSocket 客户端发送：

```js
const ws = new WebSocket("ws://localhost:8099/ac/ws/demo");
ws.onmessage = e => console.log(e.data);
ws.send(JSON.stringify({ frame: "echo", payload: { text: "hello" } }));
// 收到 {"frame":"echo.reply","payload":{"echo":"hello"}}
```

---

## 构建与测试

```bash
# 全量编译 + 单测
mvn clean verify

# 仅编译
mvn clean compile

# 不打 demo fat jar 的本地安装（默认行为）
mvn clean install -DskipTests
```

---

## 参与贡献

欢迎 Issue 与 PR！

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -am 'Add some feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 发起 Pull Request

请确保 `mvn clean verify` 通过，并遵循现有代码风格。

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
