# Aperture

Annotation-driven WebSocket 帧路由框架。 **无业务**, **无 starter 依赖**。

## 模块结构

```
aperture/
├── pom.xml                     aperture-parent(聚合 pom,自身不打 jar)
├── aperture-core/              框架核心 —— 注解、基类、反射路由、适配层
└── aperture-example/           可运行示例 —— mvn -pl aperture-example spring-boot:run
```

## 坐标

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>1.1.0-SNAPSHOT</version>   <!-- 当前开发快照;稳定版用 1.0.0 -->
</dependency>
```

## 快速上手

```java
@SpringBootApplication(scanBasePackages = {
        "com.your.biz",              // 你的 @AprController 所在包
        "com.vex.aperture.channel"})  // 必须显式纳入,否则端点不注册
public class YourApplication { }
```

> `com.vex.aperture.channel` 一个包就够 —— `spi`（端点注册）、`plugin`（通道插件）、
> `ApertureChannelProperties` 都在它或其子包下。

```java
@Component
@AprController("/ws/echo")                     // 端点路径
public class EchoController extends AbstractChannelHandler<Channel> {

    public EchoController(JsonMapper mapper, Validator validator) {
        super(mapper, validator, List.of());  // 第二个参数是"其他 handler bean 集合"
    }

    @Override protected String channel() { return "ws.echo"; }

    @Override protected Channel newChannel(WebSocketSession session) {
        return new EchoChannel(session, jsonMapper);   // 继承 AbstractChannel 即可
    }
}
```

完整可跑示例见 [`aperture-example/`](aperture-example/)。

## 核心包结构

```
com.vex.aperture.channel/
├── AprController.java                  注解:controller 类声明路径 + 拦截器
├── AprTextFrame.java                   注解
├── AprByteFrame.java                   注解
├── AprPayload.java                     形参注解(方法签名形参)
├── Channel.java                        通道会话接口
├── AbstractChannel.java                通用基类
├── AbstractChannelHandler.java         入口基类 + 反射路由 + Bean Validation
├── BinaryFrame.java                    二进制帧 POJO(框架仅取首字节作路由,其余原样交付业务)
├── ApertureChannelProperties.java      通道配置(每类能力一个独立开关)
├── plugin/                             通道插件 —— 横切能力,可插拔可单独开关
│   ├── AprChannelPlugin.java           插件 SPI(onOpen / onInbound / onIdle / onClose)
│   ├── AprChannelPlugins.java          内置插件的 bean 装配
│   ├── AprPluginScheduler.java         插件链调度:会话登记 + 空闲驱动
│   ├── AprFrameLimitPlugin.java        帧大小限制
│   ├── AprServerHeartbeatPlugin.java   服务端主动心跳(发 Ping)
│   └── AprClientHeartbeatPlugin.java   客户端主动心跳(收 heartbeat.ping)
└── spi/
    ├── AprWebSocketAdapter.java        Spring WebSocketHandler → AbstractChannelHandler 适配
    └── AprControllerRegistry.java      @AprController 启动期注册
```

## 文本帧协议

首行是路由键,换行之后是载荷 JSON —— 上下行同格式:

```
audio.opening
{"sampleRate":16000,"codec":"pcm_s16le"}
    ↑ 路由键，对应 @AprTextFrame("audio.opening")
                ↑ 载荷，对应 @AprPayload DTO 或原始 String 形参
```

分隔符固定为 `\n`:JSON 规范禁止字符串内出现裸换行,故不会与载荷内容冲突。
框架只切**第一个**换行,格式化过的多行 JSON 可正常工作。

框架**不解析任何协议头** —— 上行没有 `header` / `seq` / `v` / `ts`,下行也不回填。
需要真实会话 id 请用 `Channel#id()`。

**二进制帧** —— 首字节作 `frameType`,其余字节原样交付业务:

```
[0x00] [业务字节...]
   ↑ 匹配 @AprByteFrame(0x00)
```

业务方法恰有一个载荷形参,二选一:

```java
@AprTextFrame("audio.opening")
public void onOpening(Channel c,
                      @AprPayload AudioOpeningRequest req)  // DTO(自动反序列化 + JSR-303 校验)

// 或
@AprTextFrame("audio.raw")
public void onRaw(Channel c, String payloadJson)           // 载荷 JSON 原文
```

## 下行三种方式

`Channel` 只提供三个下发方法,没有别的:

| 方法 | 用途 |
|---|---|
| `sendObject(type, payload)` | 载荷是任意**可 JSON 序列化的对象**(record / POJO / Map / 列表),自动序列化 |
| `sendText(type, text)` | 载荷**已是字符串**(纯文本 / Base64),原样拼接,**不做 JSON 转义** |
| `sendBinary(frameType, bytes)` | 二进制零开销通道,首字节作 `frameType`,不走 JSON / Base64 |

```java
c.sendObject("audio.done", new Opening(16000, "pcm_s16le"));  // → audio.done\n{"sampleRate":16000,"codec":"pcm_s16le"}
c.sendText("audio.text", "hello");                             // → audio.text\nhello
c.sendBinary(0x01, pcmBytes);                                  // → [0x01][pcmBytes...]
```

## 报文样例

以 [`aperture-example`](aperture-example/) 的回声端点为例，全部报文由测试逐条断言。

**建连** —— 服务端主动下发 `session.ready`，报的是**实际生效**的插件能力与边界：

```
session.ready
{"serverCaps":["text_frame_v1","binary_first_byte_v1","heartbeat_v1","frame_limit_v1"],"serverHeartbeat":{"intervalMs":30000,"timeoutMs":90000},"limits":{"maxTextFrameBytes":65536,"maxBinaryFrameBytes":1048576}}
```

被关掉的插件，其能力位与对应段**一律不出现** —— 客户端据此得知服务端真实边界。

**正常交互** —— 上行 `echo.dto`：

```
echo.dto
{"text":"ab","repeat":3}
```

下行 `echo.dto.done`：

```
echo.dto.done
{"sessionId":"s1","text":"ababab"}
```

**校验失败** —— `@NotBlank` 不通过，业务方法不执行：

```
error
{"code":"bad_request","message":"text 不能为空","data":null}
```

## 统一响应结构

所有下行报文共用一个外壳（[Response](aperture-core/src/main/java/com/vex/aperture/channel/Response.java)）：

```java
public record Response<T>(String code, String message, T data) {}
```

`code` 是**语义化字符串**而非数字 —— 客户端不需要维护一张数字码表，
日志、埋点、前端提示里直接可读。框架内置的：

| code | 触发条件 |
|---|---|
| `ok` | 成功（`Response.ok(data)`） |
| `bad_request` | 缺换行分隔符 / 首行为空、载荷 JSON 非法、`@AprPayload` 校验失败 |
| `not_found` | 首行路由键未注册到任何 `@AprTextFrame`；二进制 `frameType` 未注册 |
| `internal` | 业务方法抛异常 |
| `frame_too_large` | 帧超限（随后以 `1009` 关闭连接） |
| `heartbeat_timeout` | 客户端心跳超时（随后以 `1001` 关闭连接） |

**错误帧的载荷结构由业务决定** —— 覆写 `errorPayload` 即可换成自己的 record / POJO / Map，
框架不强制 `Response` 形态（见下文[自定义插件](#自定义插件)）。

## 通道插件(可插拔 · 可单独开关)

横切能力(心跳、帧限制、未来的鉴权限流审计)全部做成**插件**,每一类一个独立 bean,
一个独立配置段 —— **想要就配,不要就关**,互不影响。

### 内置插件与配置

配置前缀 `aperture.channel`，见 [`ApertureChannelProperties`](aperture-core/src/main/java/com/vex/aperture/channel/ApertureChannelProperties.java)：

| 插件 | 配置段 | 默认 | 说明 |
|---|---|---|---|
| 帧大小限制 | `frame-limit.enabled` | `true` | 关闭后不做任何大小判定 |
| | `frame-limit.max-text-bytes` | `65536` | 文本帧上限；配 `0` 不限制 |
| | `frame-limit.max-binary-bytes` | `1048576` | 二进制帧上限；配 `0` 不限制 |
| 服务端心跳 | `server-heartbeat.enabled` | `true` | 空闲发 WebSocket Ping |
| | `server-heartbeat.interval-ms` | `30000` | 空闲达该值发一次 Ping |
| | `server-heartbeat.timeout-ms` | `90000` | 空闲超该值以 `1001` 关闭 |
| 客户端心跳 | `client-heartbeat.enabled` | `false` | 需客户端配合发 `heartbeat.ping` |
| | `client-heartbeat.timeout-ms` | `120000` | 多久没收到客户端心跳就以 `1001` 关闭 |

```yaml
aperture:
  channel:
    frame-limit:
      enabled: true
      max-text-bytes: 65536
      max-binary-bytes: 1048576
    server-heartbeat:
      enabled: true
      interval-ms: 30000
      timeout-ms: 90000
    client-heartbeat:
      enabled: false
      timeout-ms: 120000
```

### 两种心跳互相独立

- **服务端心跳**：只看"最近一次入站帧"，无论那帧是业务报文还是客户端心跳。
  客户端**不发**心跳时，服务端**照发** Ping、**照判**超时。
- **客户端心跳**：只认 `heartbeat.ping` 这一种报文。服务端心跳关掉时，客户端心跳仍可单独维持会话。

```
客户端 → 服务端   heartbeat.ping\n{}          # 客户端主动心跳
服务端 → 客户端   heartbeat.pong\n{}          # 框架内置路由自动回执,不占业务路由表
```

### 帧大小限制

超限帧**不会**进入 Jackson 反序列化 —— 一个超大载荷不会先吃掉内存才被拒绝。
超限时回 `error{frame_too_large}` 并以 `1009`(消息体过大)关闭连接。

```
error
{"code":"frame_too_large","message":"文本帧超限: 128 > 32","data":null}
```

### 自定义插件

实现 `AprChannelPlugin` 并声明为 Spring bean 即自动纳入同一条插件链，**无需在任何地方登记**。

SPI 全貌 —— 只有 `enabled()` 是抽象方法，其余四个钩子都有默认空实现：

| 方法 | 时机 | 默认行为 |
|---|---|---|
| `boolean enabled()` | —— | **必须实现**：返回该插件是否启用 |
| `void onOpen(session, channel)` | 握手通过、业务 `onOpen` 之后 | 空 |
| `boolean onInbound(session, channel, message)` | 帧校验之后、业务路由之前 | 放行 |
| `void onIdle(session, channel, idleMs)` | 由调度器按周期驱动 | 空 |
| `void onClose(session, channel)` | 断连 | 空 |

`channel` 声明为 `Object`，需要下行报文时转成 `Channel` 即可：

```java
	if (channel instanceof Channel ch) {
	    ch.sendObject("error", Response.error("unauthorized", "鉴权失败"));
	}
```

**完整示例** —— 首帧 token 鉴权，未通过就断连：

```java
package com.your.biz.ws;

import com.vex.aperture.channel.Channel;
import com.vex.aperture.channel.Response;
import com.vex.aperture.channel.plugin.AprChannelPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
@Order(5)                                          // 排在内置帧限制(10)之前
public class TokenAuthPlugin implements AprChannelPlugin {

    /// 会话属性键 —— "ok" 表示已放行
    private static final String ATTR_AUTH = "apr.auth";

    private final boolean enabled;

    public TokenAuthPlugin(@Value("${aperture.channel.auth.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "token-auth"; }

    @Override public boolean enabled() { return enabled; }

    @Override
    public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
        if (!enabled() || "ok".equals(session.getAttributes().get(ATTR_AUTH))) {
            return true;                            // 已放行 / 插件关闭
        }
        if (isAuthFrame(message) && verifyToken(readToken(message))) {
            session.getAttributes().put(ATTR_AUTH, "ok");
            return true;
        }
        // 没带 token 或 token 无效 —— 立刻断,后续业务报文一律不放行
        if (channel instanceof Channel ch) {
            ch.sendObject("error", Response.error("unauthorized", "鉴权失败"));
        }
        closeQuietly(session);
        return false;                               // 拦截:业务路由不会被调用
    }

    @Override
    public void onClose(WebSocketSession session, Object channel) {
        // 归还资源、记审计日志……
    }

    /// SPI 的钩子不能抛受检异常,而 WebSocketSession#close 抛 IOException —— 必须自己吞掉
    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception ignored) {
            // 会话可能已被对端关闭
        }
    }

    /// 首行是否为 auth.token
    private static boolean isAuthFrame(WebSocketMessage<?> message) {
        return message instanceof TextMessage t && type(t.getPayload()).equals("auth.token");
    }

    private static String readToken(WebSocketMessage<?> message) {
        int nl = ((TextMessage) message).getPayload().indexOf('\n');
        return nl < 0 ? "" : ((TextMessage) message).getPayload().substring(nl + 1);
    }

    private static String type(String payload) {
        int nl = payload.indexOf('\n');
        return (nl < 0) ? payload : payload.substring(0, nl);
    }

    private boolean verifyToken(String rawJson) {
        // 换成你自己的校验逻辑（查 session / 解 JWT / 调远程鉴权……）
        return false;
    }
}
```

配套客户端报文与配置：

```
客户端 → 服务端   auth.token\n{"token":"xxx"}
```

```yaml
aperture:
  channel:
    auth:
      enabled: true          # 想关就改 false，其余插件不受影响
```

### 插件执行顺序与异常

- **顺序**：按 `@Order` 升序，内置插件是帧限制 `10` → 服务端心跳 `20` → 客户端心跳 `30`。
  自定义插件不给 `@Order` 时排在最后。
- **拦截**：`onInbound` 返回 `false` 即拦下该帧 —— 后续插件不再执行，
  业务路由也不会被调用。适合限流、鉴权、格式预校验这类"不合法就别进业务"的场景。
- **异常**：插件抛出的异常被调度器捕获并降级为日志，**不穿透**到业务路由，
  也不会拖垮整条连接；后续插件照常执行。
- **业务编排不放在这里**：插件只做横切关注点。真正的方法调用、DTO 反序列化、
  参数校验是 `@AprTextFrame` 的职责。
- **钩子不能抛受检异常**：`WebSocketSession#close` 抛 `IOException`，而 SPI 的钩子没有
  `throws` 声明 —— 调 `close` 必须自己 try/catch 或包一层 `closeQuietly`。
- **前提**：插件事件由 [`AprWebSocketAdapter`](aperture-core/src/main/java/com/vex/aperture/channel/spi/AprWebSocketAdapter.java)
  广播。单测里若直接调 `handler.handleFrame(...)` 而不经过适配层，插件不会执行。

> 上面的示例代码原样放在
> [`ReadmeSampleCompileTest`](aperture-example/src/test/java/com/vex/aperture/example/ReadmeSampleCompileTest.java)
> 里参与编译 —— README 与可编译代码不会走散。

### 调度

`AprPluginScheduler` 按 `SmartLifecycle` 在容器 refresh 后启动，单测里不起线程
（直接调 `sweep()`）。空闲计时以**入站业务帧**为准 —— Ping/Pong 是容器层控制帧，
不让一个只回 Pong 的死连接无限续命；单向上行流（持续推音频）也不会被误踢。

## 反射路由 (`AbstractChannelHandler`)

启动期 (构造期)扫描本类内所有带 `@AprTextFrame` / `@AprByteFrame` 的方法,
按 (frameName → Method) 索引。`beforeHandle` 阶段:

1. 切分首行(路由键)与载荷;
2. 解析 `@AprPayload` 形参 → Jackson 反序列化 → Bean Validation (`@Validated` + JSR-303);
3. 业务方法调用 → 返回值由 `Channel.sendObject` 序列化下发;
4. 异常按 `error{code, message}` 回客户端。

## 通道配置

`AbstractChannelHandler` 的 3 参构造器用默认上限；**Spring bean 请用 4 参重载**
传入 `ApertureChannelProperties`，否则改`application.yml` 不生效：

```java
public MyController(JsonMapper jsonMapper, Validator validator, ApertureChannelProperties props) {
    super(jsonMapper, validator, List.of(), props);
}
```

## `AprControllerRegistry`(全自动化)

启动期把 `@AprController` bean 绑到自建的 `WebSocketHandlerMapping`(`order=0`,优先于
普通 `RequestMapping`)。不依赖 `WebSocketConfigurer` —— Spring Boot 4 的 servlet stack 不
提供 `WebSocketAutoConfiguration`,即使装了 `spring-boot-starter-websocket` 也不会自动调起
`WebSocketConfigurer` bean,所以框架主动注册 `HandlerMapping`。

它是 `com.vex.aperture.channel.spi` 包下的 `@Component`,**业务包名与之不同时必须显式
纳入组件扫描**(见上文"快速上手")。

## `ApertureControllerConfigurer`(跨 controller 共享)

类比 `WebMvcConfigurer`,业务方实现接口并加 `@Component` 即可配置:

- `global.pathPrefix("/api")` —— 所有 controller 路径加 `/api`
- `ctx.addInterceptors(...)` / `allowedOrigins(...)` / `idleTimeout(...)`

不实现本接口时,行为与 0.3.0 完全一致 (零破坏)。