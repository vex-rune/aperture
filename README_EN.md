# Aperture

[![Build](https://github.com/vex-rune/aperture/actions/workflows/build.yml/badge.svg)](https://github.com/vex-rune/aperture/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1%2B-brightgreen.svg)](https://spring.io/projects/spring-boot)

> [中文](./README.md) | English

**Aperture** is an annotation-driven WebSocket frame routing framework for Spring Boot 4.

Inspired by Spring's `@MessageMapping`, it lets you declare WebSocket endpoints with `@AprController` and method-level frame routes with `@AprTextFrame` / `@AprByteFrame`. At startup, Aperture automatically scans and registers controllers into Spring's `WebSocketHandlerRegistry`, so your business code can start handling text and binary frames with near-zero configuration.

---

## Table of Contents

- [Features](#features)
- [Module Overview](#module-overview)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Annotations](#core-annotations)
- [Configuration](#configuration)
- [Multi-Channel Setup](#multi-channel-setup)
- [Protocol & Error Codes](#protocol--error-codes)
- [Running the Demo](#running-the-demo)
- [Building & Testing](#building--testing)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Annotation-driven**: Spring-like declarative routing for both text and binary frames.
- **Auto-configuration**: The starter automatically scans `@AprController`, registers `WebSocketHandler`s, and assembles interceptor chains.
- **Multi-channel**: Multiple WebSocket channels (e.g., ASR, Agent, Device) can coexist in one project, each with its own prefix, timeout, and interceptors.
- **Built-in interceptors**: Trace (writes traceId into MDC during handshake, keeping it through the whole session) and CORS (OPTIONS preflight handling).
- **Parameter validation**: `@AprPayload` automatically performs Jackson deserialization and JSR-303 Bean Validation.
- **Extensible**: Without the starter, you can implement `ApertureControllerConfigurer` directly to customize global and per-controller behavior.
- **Zero business logic**: Neither `core` nor `starter` contains business code — they only provide framework capabilities.

---

## Module Overview

```
aperture/                                      ← Maven multi-module aggregator
├── aperture-core/                             ← Framework core: annotations, base classes, reflection routing, adapter layer
├── aperture-spring-boot-starter/              ← Auto-configuration + ApertureProperties + built-in interceptors
└── aperture-demo/                             ← Minimal runnable example
```

| Module | Purpose | Published in Release |
|---|---|---|
| `aperture-core` | Annotations / base classes / reflection routing / Spring `WebSocketHandlerRegistry` adapter; no business code | ✅ |
| `aperture-spring-boot-starter` | Out-of-the-box experience: `ApertureConfigurer` factory + Trace / CORS interceptors | ✅ |
| `aperture-demo` | Minimal runnable example; no fat jar by default | Reference only |

---

## Installation

### Maven

> Replace `VERSION` with the latest version from [GitHub Releases](https://github.com/vex-rune/aperture/releases). The current source version is `1.0.0-SNAPSHOT`.

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-spring-boot-starter</artifactId>
    <version>VERSION</version>
</dependency>
```

If you only need the core framework without the starter's auto-configuration and built-in interceptors:

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>VERSION</version>
</dependency>
```

### Pulling from GitHub Packages

Artifacts are hosted on GitHub Packages. Add credentials to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>ghp_xxx</password> <!-- PAT with at least read:packages scope -->
    </server>
  </servers>
</settings>
```

And add the repository to your `pom.xml`:

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

## Quick Start

Get an Echo service running in 3 steps.

### 1. Add the Dependency

See [Installation](#installation).

### 2. Write a Controller

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

### 3. Add Configuration and YAML

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

After startup, connect with a browser or WebSocket client:

```
ws://localhost:8080/ac/ws/demo
```

Send a text frame:

```json
{ "frame": "echo", "payload": { "text": "hello" } }
```

The server replies with:

```json
{ "frame": "echo.reply", "payload": { "echo": "hello" } }
```

---

## Core Annotations

| Annotation | Spring Equivalent | Purpose |
|---|---|---|
| `@AprController(path)` | `@RestController` + `@RequestMapping` | Declares the WebSocket endpoint URL |
| `@AprTextFrame("type")` | `@MessageMapping` | Declares a text-frame route matched by the `frame` field |
| `@AprByteFrame(N)` | `@MessageMapping` (binary) | Declares a binary-frame route matched by the leading byte |
| `@AprPayload` | `@Valid @RequestBody` | Method parameter: Jackson deserialization + Bean Validation |

---

## Configuration

### Full `ApertureProperties` YAML

```yaml
aperture:
  enabled: true                       # Enable Aperture auto-scan; default true
  base-packages: com.example.demo     # Base packages to scan for @AprController; comma-separated
  path-prefix: /ac                    # Global WebSocket path prefix
  allowed-origins: "*"                # Default allowed origins (can be overridden by ApertureConfigurer.useCors)
  idle-timeout: 5m                    # Default handshake idle timeout
  interceptors:
    trace:
      enabled: true                   # Enable Trace interceptor
      key: X-Trace-Id                 # Request header name to read traceId from
    cors:
      enabled: true                   # Enable CORS interceptor
```

### `ApertureConfigurer` Fluent API

| Method | Description |
|---|---|
| `create(String name)` | Create a builder for the named channel |
| `pathPrefix(String)` | Global path prefix |
| `useTrace(ApertureProperties)` | Enable Trace interceptor based on YAML |
| `useTrace(boolean)` | Explicitly enable/disable Trace interceptor |
| `traceMdcKey(String)` | Customize the MDC key |
| `useCors(ApertureProperties, String...)` | Enable CORS interceptor based on YAML + business origins |
| `useCors(boolean, String...)` | Explicitly enable/disable CORS interceptor |
| `idleTimeout(Duration)` | Handshake idle timeout |
| `addInterceptors(HandshakeInterceptor...)` | Append custom interceptors |
| `build()` | Build an `ApertureControllerConfigurer` instance |

---

## Multi-Channel Setup

Aperture assumes your project may have multiple WebSocket channels, so each channel should declare its own `ApertureConfigurer` Bean:

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
            .pathPrefix("/ac")            // When sharing a prefix with another channel, the value must be identical
            .useTrace(props)
            .useCors(props, "*")
            .idleTimeout(Duration.ofMinutes(10))
            .build();
}
```

> ⚠️ The same global property (e.g., `path-prefix`) **must not be set to different values by multiple configurers**, or an `IllegalStateException` will be thrown at startup.

---

## Protocol & Error Codes

### Text Frame Protocol Format

```json
{
  "frame": "echo",
  "payload": { "text": "hello" }
}
```

- `frame`: the routing key, matched by `@AprTextFrame("echo")`.
- `payload`: the business payload, deserialized into the `@AprPayload` parameter.

### Binary Frame Protocol Format

The framework reads only the first byte as the `frameType` routing key; the remaining bytes are delivered unchanged to the business method:

```
[ frameType: 1 byte ][ payload: N bytes ]
```

### Error Responses

| Scenario | Behavior |
|---|---|
| Duplicate binding of the same `frame` | `IllegalStateException` at startup; service fails to start |
| `@AprPayload` validation failure | Automatically replies `error{4400, "..."}` |
| Unknown `frame` received | Automatically replies `error{4401, "unknown frame type X"}` |
| Conflicting global `path-prefix` across configurers | `IllegalStateException` at startup |

---

## Running the Demo

```bash
# Install core + starter + demo
mvn clean install -DskipTests

# Run the demo (default port 8099)
mvn -pl aperture-demo spring-boot:run
```

After startup:

- Browser debug page: [http://localhost:8099/](http://localhost:8099/)
- WebSocket endpoint: `ws://localhost:8099/ac/ws/demo`

Send from the browser console or any WebSocket client:

```js
const ws = new WebSocket("ws://localhost:8099/ac/ws/demo");
ws.onmessage = e => console.log(e.data);
ws.send(JSON.stringify({ frame: "echo", payload: { text: "hello" } }));
// Receives {"frame":"echo.reply","payload":{"echo":"hello"}}
```

---

## Building & Testing

```bash
# Full compilation + tests
mvn clean verify

# Compile only
mvn clean compile

# Local install without demo fat jar (default behavior)
mvn clean install -DskipTests
```

---

## Contributing

Issues and pull requests are welcome!

1. Fork this repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

Please make sure `mvn clean verify` passes and follow the existing code style.

---

## License

This project is open-sourced under the [Apache License 2.0](LICENSE).
