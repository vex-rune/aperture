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
    <version>1.0.0</version>
</dependency>
```

## 快速上手

```java
@SpringBootApplication(scanBasePackages = {
        "com.your.biz",              // 你的 @AprController 所在包
        "com.vex.aperture.channel.spi"})  // 必须显式纳入,否则端点不注册
public class YourApplication { }
```

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
├── AprHeader.java                      形参注解:取单个协议头字段
├── AprHeaders.java                     形参注解:取整个协议头
├── AprFrame.java                       文本帧信封 record(header + payload)
├── AprFrameHeader.java                 协议头 record(type / sessionId / seq)
├── Channel.java                        通道会话接口
├── AbstractChannel.java                通用基类
├── AbstractChannelHandler.java         入口基类 + 反射路由 + Bean Validation
├── BinaryFrame.java                    二进制帧 POJO(框架仅取首字节作路由,其余原样交付业务)
└── spi/
    ├── AprWebSocketAdapter.java        Spring WebSocketHandler → AbstractChannelHandler 适配
    └── AprControllerRegistry.java      @AprController 启动期注册
```

## 文本帧协议

```json
{
  "header": { "type": "audio.opening", "sessionId": "s1", "seq": 1 },
  "payload": { "sampleRate": 16000, "codec": "pcm_s16le" }
}
```

`header.type` 是上下行路由键;`seq` 下行由框架在锁内自增(序号顺序 == 发送顺序)。

**二进制帧** —— 首字节作 `frameType`,其余字节原样交付业务:

```
[0x00] [业务字节...]
   ↑ 匹配 @AprByteFrame(0x00)
```

业务方法可按需取协议头形参,任意组合:

```java
@AprTextFrame("audio.opening")
public void onOpening(Channel c,
                      @AprHeader("sessionId") String sid,  // 单个字段
                      @AprHeaders AprFrameHeader header,     // 整个协议头
                      @AprPayload AudioOpeningRequest req)  // 载荷
```

## 报文样例

以 [`aperture-example`](aperture-example/) 的回声端点为例，全部报文由测试逐条断言。

**建连** —— 服务端主动下发 `echo.hello` + `session.ready`：

```json
{"header":{"type":"session.ready","sessionId":"s1","seq":1},"payload":{"serverCaps":["binary_header_v1"],"heartbeat":{"intervalMs":15000,"timeoutMs":45000},"limits":{"maxFrameBytes":4096}}}
```

**正常交互** —— 上行 `echo.dto`：

```json
{"header":{"type":"echo.dto","sessionId":"s1","seq":5},"payload":{"text":"ab","repeat":3}}
```

下行 `echo.dto.done`：

```json
{"header":{"type":"echo.dto.done","sessionId":"s1","seq":1},"payload":{"sid":"s1","seq":5,"text":"ababab"}}
```

**校验失败** —— `@NotBlank` 不通过，业务方法不执行：

```json
{"header":{"type":"error","sessionId":"s1","seq":2},"payload":{"code":4400,"message":"text 不能为空","fatal":false}}
```

## 错误码

| code | 触发条件 |
|---|---|
| `4400` | JSON 非法、`header` 缺失、`@AprPayload` 校验失败、业务方法抛异常 |
| `4400` | 二进制帧为空 / `frameType` 未注册 |
| `4401` | `header.type` 未注册到任何 `@AprTextFrame` |

## 反射路由 (`AbstractChannelHandler`)

启动期 (构造期)扫描本类内所有带 `@AprTextFrame` / `@AprByteFrame` 的方法,
按 (frameName → Method) 索引。`beforeHandle` 阶段:

1. 解析 `@AprPayload` 形参 → Jackson 反序列化 → Bean Validation (`@Validated` + JSR-303);
2. 注入 `@AprHeader` / `@AprHeaders` 形参;
3. 业务方法调用 → 返回值由 `Channel.sendText/sendBinary` 序列化下发;
4. 异常按 `error{code, message}` 回客户端。

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