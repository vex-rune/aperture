# Aperture Demo

跑得起来的最小示例 —— 演示 starter + `@AprController` 标准用法。

## 启动

```bash
cd aperture/
mvn -pl aperture-spring-boot-starter,aperture-demo -am install -DskipTests
mvn -pl aperture-demo spring-boot:run
```

启动后:

```
[INFO] 注册 controller path=/ac/ws/demo handler=com.vex.aperture.demo.EchoController
```

## 客户端示例

```js
const ws = new WebSocket("ws://localhost:8088/ac/ws/demo");
ws.send(JSON.stringify({ frame: "echo", payload: { text: "hello" } }));
//  → {"frame":"echo.reply","payload":{"echo":"hello"}}
```

## 浏览器测试页

启动后直接访问 [http://localhost:8088/](http://localhost:8088/),一个内置的
WebSocket 调试页会自动加载:

- 「连接」按钮建立 ws + 自动从 server 拉 traceId
- 「发送 echo」发文本帧
- 「发送 0x01」发二进制帧(首字节 0x01 + ASCII 字节)
- 消息日志双向实时打印

## 文件清单

```
aperture-demo/
├── pom.xml                            坐标 com.vex.aperture:aperture-demo
├── README.md
└── src/main/
    ├── java/com/vex/aperture/demo/
    │   ├── ApertureDemoApplication.java   启动入口 + 一份 demo 通道的 ApertureConfigurer Bean
    │   └── EchoController.java            @AprController("/ws/demo") + @AprTextFrame("echo") + @AprByteFrame(0x01)
    └── resources/
        ├── application.yml                aperture.* + logging.pattern 带 traceId
        └── static/index.html              浏览器调试页
```

## 打 fat jar(可选)

默认不打;需要时:

```bash
mvn -pl aperture-demo package -Ddemo.skip-fat=false
java -jar aperture-demo/target/aperture-demo-0.4.0.jar
```