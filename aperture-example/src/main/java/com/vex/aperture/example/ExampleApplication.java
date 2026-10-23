package com.vex.aperture.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用入口 —— 启动后 {@code ws://localhost:8080/ws/echo} 即为可用的 Aperture 端点。
 *
 * <p>不需要实现 {@code WebSocketConfigurer}:{@code AprControllerRegistry} 启动期自动
 * 扫到 {@code @AprController} bean并注册到 {@code WebSocketHandlerMapping}。
 *
 * <p>注意 {@code scanBasePackages}:框架组件都在 {@code com.vex.aperture.channel}
 * 及其子包下(端点注册、通道插件、通道配置),业务包名与它不同,须显式纳入扫描范围,
 * 否则端点不会注册。
 */
@SpringBootApplication(scanBasePackages = {
        "com.vex.aperture.example",
        "com.vex.aperture.channel"})
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}