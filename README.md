# Aperture

Annotation-driven WebSocket frame routing framework for Spring Boot.

> 类比 Spring `spring-messaging` 的 `@MessageMapping`:用方法级注解声明帧路由,`@AprController(path)` 声明 WebSocket 端点。
> 控制器业务方法拿到形参是 `(Channel, payload)` 或 `(Channel, AudioFrame)` —— 像 `@RestController` 写 HTTP 一样写 WebSocket。

## 快速上手

```java
@Component
@AprController("/ws/chat")
public class ChatController extends AbstractChannelHandler<ChatChannel> {

    @Override protected String channel() { return "ws.chat"; }
    @Override protected ChatChannel newChannel(WebSocketSession s) {
        return new ChatChannel(s, jsonMapper);
    }

    @PostConstruct
    void scanSelf() { super.registerSelf(this); }

    @AprTextFrame("chat.send")
    public void onSend(Channel ch, @AprPayload ChatSendRequest req) {
        // 业务:req.text / req.targetUserId ...
    }

    @AprByteFrame(0x01)
    public void onAudio(Channel ch, AudioFrame frame) {
        // 业务:frame.payload()
    }
}
```

启动后 Spring 自动把 `/ws/chat` 绑到这个 controller —— 无需 `WebSocketConfigurer`,无需 `XML`。

## 核心概念

| 注解 | 类比 Spring | 作用 |
|---|---|---|
| `@AprController("/path")` | `@RestController` + `@RequestMapping` | 声明 WebSocket 端点 URL + 拦截器 |
| `@AprTextFrame("type")` | `@MessageMapping("/dest")` | 声明文本帧路由(JSON 信封 type) |
| `@AprByteFrame(N)` | `@MessageMapping` + binary | 声明二进制帧路由(frameType) |
| `@AprPayload` | `@Valid @RequestBody` | 形参:走 Jackson 反序列化 + Bean Validation |

## 框架分层

```
aperture-core        ← 本模块,只含框架核心
xinli_agent 子模块     ← 业务(controller 继承 AbstractChannelHandler)
```

业务类(controller 继承 + 路由注解)留在使用方模块内,Aperture 不耦合任何业务。

## 协议不变量(框架承担)

- 同一 `@AprTextFrame("type")` / `@AprByteFrame(N)` 重复绑定 → 启动期 `IllegalStateException`
- `@AprPayload` 形参校验失败 → 反射前拦截,自动回 `error{4400}`
- 未知报文类型 → `error{4401, "未知报文类型 X"}`

## 模块坐标

```xml
<dependency>
    <groupId>com.xinli.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

## License

Apache License 2.0
