# Aperture

**Annotation-driven WebSocket frame routing framework for Spring Boot 4.**

类比 Spring `@MessageMapping` —— 用方法级注解声明帧路由,
`@AprController(path)` 声明 WebSocket 端点,启动期自动注册到 Spring `WebSocketHandlerRegistry`,
0 配置。

---

## 安装(从 GitHub Packages)

仓库:[vex-rune/aperture](https://github.com/vex-rune/aperture) · Maven path = `maven`

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>ghp_xxx (PAT, scope: read:packages) OR a personal access token with `read:packages`</password>
    </server>
  </servers>
</settings>
```

```xml
<!-- pom.xml -->
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/vex-rune/aperture</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-spring-boot-starter</artifactId>
    <version>1.0.0</version>
  </dependency>
</dependencies>
```

> core 与 starter 由 [vex-rune/aperture](https://github.com/vex-rune/aperture) 在 push `v*.*.*` tag 时
> 由 GitHub Actions 自动发布到 [`maven.pkg.github.com/vex-rune/aperture`](https://github.com/vex-rune/aperture/packages?ecosystem=maven)。

```
@AprController("/api")
public class ChatController extends AbstractChannelHandler<ChatSession> {

    @AprTextFrame("chat.send")
    public void onSend(ChatSession ch, @AprPayload ChatSendRequest req) {
        // 业务:req.text / req.targetUserId ...
    }

    @AprByteFrame(0x01)
    public void onAudio(ChatSession ch, BinaryFrame frame) {
        // 帧 payload 直接读 byte[]
    }
}
```

---

## 模块组成

```
aperture/                                      ← 独立 maven 多模块
├── aperture-core/                             ← 框架核心,坐标 com.vex.aperture:aperture-core
├── aperture-spring-boot-starter/              ← 配置类 + 内置拦截器(Trace / Cors)
└── aperture-demo/                              ← 可运行 demo
```

| 模块 | 作用 | 是否发布 |
|---|---|---|
| `aperture-core` | 注解 / 基类 / 反射路由 / 适配层;无业务 | ✅ |
| `aperture-spring-boot-starter` | 一键开箱 —— `ApertureConfigurer` 工厂 + 内置 Trace/Cors 拦截器 | ✅ |
| `aperture-demo` | 跑得起来的最小示例,默认不打 fat jar | ✅(参考) |

## 坐标

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>0.4.0</version>
</dependency>

<!-- 如果想要开箱即用(配置类 + 内置拦截器),再加 starter -->
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

---

## 快速上手(3 步)

### 1) 引入依赖 + 写业务 controller

```java
@AprController("/ws/demo")
public class EchoController extends AbstractChannelHandler<EchoController.Session> {

    @AprTextFrame("echo")
    public void onEcho(@AprPayload EchoBody body, Session session) {
        session.sendText("echo.reply", new EchoReply(body.text()));
    }

    @Override
    protected Session newSession(String sessionId) { return new Session(sessionId); }

    public static final class Session implements com.vex.aperture.channel.Channel {
        public Session(String id) { this.id = id; }
        public String id() { return id; }
        private final String id;
    }

    public record EchoBody(@NotBlank String text) {}
    public record EchoReply(String echo) {}
}
```

### 2) 装配 starter(每通道一份 configurer)

```java
@Configuration
@EnableConfigurationProperties(ApertureProperties.class)
public class WsConfig {
    @Bean
    public ApertureControllerConfigurer demoChannelConfigurer(ApertureProperties props) {
        return ApertureConfigurer.create("demo")
                .pathPrefix(props.getPathPrefix())
                .useTrace(props)                       // 启/关由 yaml 决定
                .useCors(props, "*")
                .idleTimeout(Duration.ofMinutes(5))
                .build();
    }
}
```

> 多通道场景每条 new 一份:`create("asr")` / `create("agent")` / `create("device")`,
> 各自一份 path-prefix / 拦截器 / CORS。
>
> ⚠️ 全局属性(如 `pathPrefix`)**同一份只允许被一个 configurer 设置一次**,
> 否则框架启动期抛 `IllegalStateException` —— 这是设计意图,避免多通道 prefix 互相覆盖。

### 3) yaml 开关

```yaml
aperture:
  enabled: true
  base-packages: com.vex.aperture.demo
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

启动后访问 `ws://localhost:8080/ac/ws/demo`,发 `{"frame":"echo","payload":{"text":"hi"}}` → 服务端回 `{"frame":"echo.reply","payload":{"echo":"hi"}}`,日志带 `traceId`。

---

## 核心注解

| 注解 | 类比 Spring | 作用 |
|---|---|---|
| `@AprController(path)` | `@RestController` | 声明 WebSocket 端点 URL |
| `@AprTextFrame("type")` | `@MessageMapping` | 声明文本帧路由(帧头 = type) |
| `@AprByteFrame(N)` | `@MessageMapping` (binary) | 声明二进制帧路由 |
| `@AprPayload` | `@Valid @RequestBody` | 形参:Jackson 反序列化 + Bean Validation |

## 内置拦截器(starter)

| 拦截器 | 开关 | 说明 |
|---|---|---|
| `TraceHandshakeInterceptor` | `aperture.interceptors.trace.enabled` | 握手期读 `X-Trace-Id` 写 MDC,日志 `traceId` 贯穿会话 |
| `CorsHandshakeInterceptor` | `aperture.interceptors.cors.enabled` | OPTIONS 预检 + 写 `Access-Control-Allow-*` 头 |

## 跨 controller 共享 — `ApertureControllerConfigurer`

starter 是"开箱即用的便捷实现";不想用 starter 时,业务方可直接实现 core 里的
`ApertureControllerConfigurer` 接口,加 `@Component` 即可:

```java
@Component
public class WsConfigurer implements ApertureControllerConfigurer {
    @Override
    public void configure(GlobalContext global) {
        global.pathPrefix("/api");   // 所有 controller 路径加 /api
    }

    @Override
    public void configureController(PerControllerContext ctx) {
        ctx.addInterceptors(new MyInterceptor())
           .allowedOrigins("*")
           .idleTimeout(Duration.ofMinutes(5));
    }
}
```

> 不实现本接口时,行为完全一致(零破坏)。

---

## Demo 模块

```bash
cd aperture/
mvn -pl aperture-spring-boot-starter,aperture-demo -am install -DskipTests
mvn -pl aperture-demo spring-boot:run
```

启动后 `ws://localhost:8088/ac/ws/demo` 可连。

---

## 协议不变量

- 同一 `@AprTextFrame("type")` / `@AprByteFrame(N)` 重复绑定 → 启动期 `IllegalStateException`
- `@AprPayload` 形参校验失败 → 反射前 Bean Validation,自动回 `error{4400}`
- 未知报文类型 → `error{4401, "未知报文类型 X"}`
- 同一 `path-prefix` 被多 configurer 异值覆盖 → 启动期 `IllegalStateException`

## License

Apache License 2.0