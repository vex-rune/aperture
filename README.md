# Aperture Core

框架核心 —— 注解、基类、反射路由、Spring `WebSocketHandlerRegistry` 适配层。 **无业务**, **无 starter 依赖**。

## 坐标

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-core</artifactId>
    <version>0.4.0</version>
</dependency>
```

## 包结构

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
└── spi/
    ├── AprWebSocketAdapter.java        Spring WebSocketHandler → AbstractChannelHandler 适配
    └── AprControllerRegistry.java      @AprController 启动期注册
```

## 反射路由 (`AbstractChannelHandler`)

启动期 (构造期)扫描本类内所有带 `@AprTextFrame` / `@AprByteFrame` 的方法,
按 (frameName → Method) 索引。`beforeHandle` 阶段:

1. 解析 `@AprPayload` 形参 → Jackson 反序列化 → Bean Validation (`@Validated` + JSR-303);
2. 业务方法调用 → 返回值由 `Channel.sendText/sendBinary` 序列化下发;
3. 异常按 `error{code, message}` 回客户端。

## `AprControllerRegistry`(全自动化)

实现 Spring `WebSocketConfigurer`,启动期把 `@AprController` 业务聚合类绑到
`WebSocketHandlerRegistry`。复用同一个 bean 实例,适配层只在协议层介入。

## `ApertureControllerConfigurer`(跨 controller 共享)

类比 `WebMvcConfigurer`,业务方实现接口并加 `@Component` 即可配置:

- `global.pathPrefix("/api")` —— 所有 controller 路径加 `/api`
- `ctx.addInterceptors(...)` / `allowedOrigins(...)` / `idleTimeout(...)`

不实现本接口时,行为与 0.3.0 完全一致 (零破坏)。

---

> 📌 需要"开箱即用"的 Trace + Cors 拦截器 / `ApertureProperties` / `ApertureConfigurer`
> 配置类?加 [aperture-spring-boot-starter](../aperture-spring-boot-starter/) 即可。