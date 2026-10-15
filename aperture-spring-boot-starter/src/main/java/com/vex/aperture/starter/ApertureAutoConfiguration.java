package com.vex.aperture.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Aperture Spring Boot 自动配置 —— 在 starter jar 上声明后,所有引用 starter 的 Spring Boot
 * 工程都会自动启用 Aperture 框架:<ul>
 *   <li>{@code com.vex.aperture.channel} —— {@code @AprController} 注解 / 路由抽象
 *       {@code AbstractChannelHandler} / 自动注册器 {@code AprControllerRegistry};</li>
 *   <li>{@code com.vex.aperture.autoconfigure} —— {@link com.vex.aperture.autoconfigure.ApertureControllerConfigurer}
 *       跨 controller 共享配置接口;</li>
 *   <li>{@code com.vex.aperture.starter.configurer} —— {@link com.vex.aperture.starter.configurer.ApertureConfigurer}
 *       工厂类(用户通过它组装 {@code ApertureControllerConfigurer});</li>
 *   <li>{@code com.vex.aperture.starter.interceptor} —— 内置 Trace / CORS 握手拦截器。</li>
 * </ul>
 *
 * <p>{@link ComponentScan} 显式声明包路径;{@code @AutoConfiguration} 让 Spring Boot 4
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 自动装载本配置类。
 *
 * <p>不使用 {@code META-INF/spring.factories}(那是 Spring Boot 2 时代的机制),完全 0 配置接入。
 *
 * @since Aperture 0.4
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.vex.aperture.channel",
        "com.vex.aperture.autoconfigure",
        "com.vex.aperture.starter.configurer",
        "com.vex.aperture.starter.interceptor"
})
public class ApertureAutoConfiguration {
}