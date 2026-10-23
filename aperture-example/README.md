# Aperture Example

可运行示例 —— 演示 `@AprController` 端点声明与全部路由能力。

**本文所有报文都由 [`EchoControllerTest`](src/test/java/com/vex/aperture/example/EchoControllerTest.java) 逐条断言**，可直接照抄发包验证。

## 启动

```bash
mvn -pl aperture-example -am spring-boot:run
```

端点:`ws://localhost:8080/ws/echo`

## 帧格式

**文本帧**

```json
{"header":{"type":"<路由键>","sessionId":"<会话id>","seq":<客户端序号>},"payload":{ }}
```

- `header.type` — 路由键，对应 `@AprTextFrame("...")`；下行由服务端回填
- `payload` — 业务载荷，对应 `@AprPayload` 或原始 `String` 形参

**二进制帧**

```
[0x00] [剩余字节原样交付业务]
   ↑ frameType，对应 @AprByteFrame(0x00)
```

## 建连下行（服务端主动下发，无需上行触发）

`echo.hello` —— `onOpen` 钩子：

```json
{"header":{"type":"echo.hello","sessionId":"s-test","seq":1},"payload":{"sessionId":"s-test","path":"/ws/echo"}}
```

`session.ready` — 紧随其后：

```json
{"header":{"type":"session.ready","sessionId":"s-test","seq":2},"payload":{"serverCaps":["binary_header_v1"],"heartbeat":{"intervalMs":15000,"timeoutMs":45000},"limits":{"maxFrameBytes":4096}}}
```

## 上行报文样例

### 1. `echo.raw` —— 原始载荷直传

上行：

```json
{"header":{"type":"echo.raw","sessionId":"s-test","seq":1},"payload":{"text":"hi"}}
```

下行：

```json
{"header":{"type":"echo.raw.done","sessionId":"s-test","seq":1},"payload":{"received":"{\"text\":\"hi\"}"}}
```

> `String` 形参收到的是 `payload` 重新序列化后的 JSON，故在 `received` 里是字符串而非嵌套对象。

### 2. `echo.dto` —— DTO 载荷 + 协议头注入

上行：

```json
{"header":{"type":"echo.dto","sessionId":"s-test","seq":5},"payload":{"text":"ab","repeat":3}}
```

下行：

```json
{"header":{"type":"echo.dto.done","sessionId":"s-test","seq":1},"payload":{"sid":"s-test","seq":5,"text":"ababab"}}
```

三个形参分别拿到：`@AprHeader("sessionId")` → `s-test`；`@AprHeaders` → 整个头（含上行 `seq=5`）；`@AprPayload` → `text="ab", repeat=3`。

### 3. 校验失败 —— `text` 为空触发 `@NotBlank`

上行：

```json
{"header":{"type":"echo.dto","sessionId":"s-test","seq":6},"payload":{"text":"","repeat":3}}
```

下行（业务方法**不执行**）：

```json
{"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"text 不能为空","fatal":false}}
```

### 4. 校验失败 —— `repeat` 越界触发 `@Max(100)`

上行：

```json
{"header":{"type":"echo.dto","sessionId":"s-test","seq":7},"payload":{"text":"ab","repeat":999}}
```

下行：

```json
{"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"repeat 最大不能超过100","fatal":false}}
```

### 5. 二进制帧 —— 首字节作 frameType

上行字节：`0x01 0x7F 0x00 0x80`（首字节 `0x01` 命中 `@AprByteFrame(0x01)`，其余 3 字节透传）

下行：

```json
{"header":{"type":"echo.binary.done","sessionId":"s-test","seq":1},"payload":{"frameType":1,"bytes":3,"sessionId":"s-test"}}
```

### 6. 未知报文类型 → `4401`

上行：

```json
{"header":{"type":"echo.not-exist"},"payload":{}}
```

下行：

```json
{"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4401,"message":"未知报文类型 echo.not-exist","fatal":false}}
```

### 7. `header` 缺失 → `4400`

上行（无 `header` 段，取不到路由键）：

```json
{"payload":{"text":"hi"}}
```

下行：

```json
{"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"JSON 解析失败","fatal":false}}
```

## 错误码

| code | 触发条件 |
|---|---|
| `4400` | JSON 非法、`header` 缺失、`@AprPayload` 校验失败、业务方法抛异常、二进制 `frameType` 未注册 |
| `4401` | `header.type` 未注册到任何 `@AprTextFrame` |

## 演示矩阵

| 报文 type | 形参形态 | 演示点 |
|---|---|---|
| `echo.raw` | `String` | 原始载荷 JSON 直传 |
| `echo.dto` | `@AprPayload` + `@AprHeader` + `@AprHeaders` | DTO 校验 / 单头字段 / 整个协议头 |
| binary `0x01` | `BinaryFrame` | 首字节路由 + 剩余字节透传 |

## 命令行发包

浏览器控制台（`ws://localhost:8080/ws/echo`）：

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/echo');
ws.onmessage = (e) => console.log('↓', JSON.parse(e.data).header.type, e.data);
ws.onopen = () => ws.send(JSON.stringify({
  header: { type: 'echo.dto', sessionId: 'my-client', seq: 1 },
  payload: { text: 'ab', repeat: 3 }
}));
```