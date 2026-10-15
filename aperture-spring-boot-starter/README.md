# Aperture Spring Boot Starter

> 不开 `AutoConfiguration.imports` —— Aperture 假定你的工程会**有多个通道**(ASR / Agent / Device ...),
> 每条通道各自 new 一个 `ApertureConfigurer`,所以 starter 只给"配置类 + 内置拦截器",不强制装配。

## 坐标

```xml
<dependency>
    <groupId>com.vex.aperture</groupId>
    <artifactId>aperture-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

## 包结构

```
com.vex.aperture.starter/
├── ApertureProperties.java                       @ConfigurationProperties("aperture")
├── configurer/
│   └── ApertureConfigurer.java                   工厂类:create("asr").useTrace(...).useCors(...)
└── interceptor/
    ├── TraceHandshakeInterceptor.java           MDC + SLF4J traceId
    └── CorsHandshakeInterceptor.java             OPTIONS 预检 + CORS 响应头
```

## 标准用法

```java
@Configuration
@EnableConfigurationProperties(ApertureProperties.class)
public class WsConfig {
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
                .pathPrefix("/ac")           // 与 asr 共用 prefix 必须用同一值
                .useTrace(props)
                .useCors(props, "*")
                .idleTimeout(Duration.ofMinutes(10))
                .build();
    }
}
```

yaml:

```yaml
aperture:
  base-packages: com.xinli.agent.api.ws
  interceptors:
    trace:
      enabled: true
      key: X-Trace-Id
    cors:
      enabled: true
```

## 内置拦截器

### `TraceHandshakeInterceptor`
- 握手期从 `X-Trace-Id` 读 traceId,缺省 `UUID` 生成
- 写 SLF4J `MDC` → 业务日志 `%X{traceId}` 自动贯穿整个会话
- 响应头回写 `X-Trace-Id`

### `CorsHandshakeInterceptor`
- OPTIONS 预检 → 直接回 200
- 写 `Access-Control-Allow-Origin / -Headers / -Methods / -Max-Age`

## `ApertureConfigurer` API

| 方法 | 说明 |
|---|---|
| `create(String name)` / `create()` | 工厂 |
| `pathPrefix(String)` | 全局路径前缀,会被 core framework 拿去 `global.pathPrefix()` |
| `useTrace(props)` / `useTrace(boolean)` | 启用 Trace 拦截器 |
| `traceMdcKey(String)` | 自定义 MDC key |
| `useCors(props, origins...)` / `useCors(boolean, origins...)` | 启用 CORS 拦截器 |
| `idleTimeout(Duration)` | 握手空闲超时 |
| `addInterceptors(HandshakeInterceptor...)` | 追加自定义拦截器 |
| `build()` | 装配出 `ApertureControllerConfigurer`(挂 `@Bean` 即可) |

> ⚠️ **不要**让多个 `ApertureConfigurer` 设置**不同**的 `pathPrefix` —— 启动期会抛 `IllegalStateException`。