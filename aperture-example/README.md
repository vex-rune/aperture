# Aperture Example

可运行示例 —— 演示 `@AprController` 端点声明与全部路由能力。

**本文所有报文都由 [`EchoControllerTest`](src/test/java/com/vex/aperture/example/EchoControllerTest.java)逐条断言**，可直接照抄发包验证。

## 启动

```bash
mvn -pl aperture-example -am spring-boot:run
```

端点:`ws://localhost:8080/ws/echo`

## 帧格式

**文本帧** —— 首行是路由键，换行之后是载荷 JSON：

```
audio.output\n{"sampleRate":16000}
    ↑ 路由键，对应 @AprTextFrame("audio.output")
                ↑ 载荷，对应 @AprPayload DTO 或原始 String 形参
```

- 分隔符固定为 `\n`（JSON 规范禁止字符串内出现裸换行，故不会与载荷内容冲突）
- 路由键必须与 `@AprTextFrame("...")` 的value 完全一致
- 上行载荷由业务自定，框架不解析任何协议头（无 `header` / `seq` / `v` / `ts`）
- 需要真实会话 id 请用 `Channel#id()`，而非报文内容

**二进制帧**

```
[0x00] [剩余字节原样交付业务]
   ↑ frameType，对应 @AprByteFrame(0x00)
```

## 建连下行（服务端主动下发，无需上行触发）

`echo.hello` —— `onOpen` 钩子：

```
echo.hello
{"sessionId":"s-test","path":"/ws/echo"}
```

`session.ready` — 紧随其后，报的是**实际生效**的插件能力与边界（被关掉的插件，其能力位与对应段一律不出现）：

```
session.ready
{"serverCaps":["text_frame_v1","binary_first_byte_v1","heartbeat_v1","frame_limit_v1"],"serverHeartbeat":{"intervalMs":30000,"timeoutMs":90000},"limits":{"maxTextFrameBytes":65536,"maxBinaryFrameBytes":1048576}}
```

## 通道插件配置

心跳与帧大小上限都是**独立插件**，各自一个配置段，见 [`application.yml`](src/main/resources/application.yml)：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `frame-limit.enabled` | `true` | 帧大小限制总开关，关掉后不做任何大小判定 |
| `frame-limit.max-text-bytes` | `65536` | 文本帧上限；配 `0` 不限制 |
| `frame-limit.max-binary-bytes` | `1048576` | 二进制帧上限；配 `0` 不限制 |
| `server-heartbeat.enabled` | `true` | 服务端主动心跳（发 WebSocket Ping） |
| `server-heartbeat.interval-ms` | `30000` | 空闲达该值发一次 Ping |
| `server-heartbeat.timeout-ms` | `90000` | 空闲超该值以 `1001` 关闭 |
| `client-heartbeat.enabled` | `false` | 客户端主动心跳（需客户端发 `heartbeat.ping`） |
| `client-heartbeat.timeout-ms` | `120000` | 多久没收到客户端心跳就以 `1001` 关闭 |

**两种心跳互相独立**：客户端不发心跳，服务端照样发 Ping、照样判超时。

### 客户端心跳报文

```
客户端 → 服务端   heartbeat.ping\n{}
服务端 → 客户端   heartbeat.pong\n{}          # 框架内置路由自动回执
```

### 帧超限

超限回 `error{frame_too_large}` 并以 `1009` 关闭连接：

```
error
{"code":"frame_too_large","message":"文本帧超限: 128 > 32","data":null}
```

### 错误码

错误帧载荷默认是 [`Response`](../../aperture-core/src/main/java/com/vex/aperture/channel/Response.java)
`{code, message, data}`，`code` 为语义化字符串：

| code | 触发条件 |
|---|---|
| `bad_request` | 缺换行分隔符 / 首行为空、载荷 JSON 非法、`@AprPayload` 校验失败 |
| `not_found` | 报文类型 / 二进制 `frameType` 未注册 |
| `internal` | 业务方法抛异常 |
| `frame_too_large` | 帧超限 |
| `heartbeat_timeout` | 客户端心跳超时 |
