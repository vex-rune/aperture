package com.xinli.aperture.channel;

import org.springframework.web.socket.server.HandshakeInterceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Aperture 控制器 —— 标记一个 {@code @Component} 类为一条 WebSocket 端点的拥有者。
 *
 * <p>业务聚合类({@link AprSessionHandlers} 这种)直接继承 {@link AbstractChannelHandler},
 * 在类上挂 {@code @AprController("/path")}+ {@code @Component} 即可声明一个端点。{@link com.xinli.aperture.channel.spi.AprControllerRegistry}
 * 启动期扫所有标此注解的 bean,并按 {@link #value()} 注册到 Spring 的 {@code WebSocketHandlerRegistry}。
 *
 * <p>类比 Spring {@code @RestController}/Spring {@code @RequestMapping} 的关系 —— 这里是 WebSocket 视角的对应物。
 *
 * @since Aperture 0.3
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AprController {

    /**
     * WebSocket 路径,如 {@code "/ac/admin/asr"};必须以 {@code /} 开头。
     */
    String value();

    /**
     * 握手拦截器类(可多个);对应 Spring {@code HandshakeInterceptor},在 upgrade 前/后钩子。
     */
    Class<? extends HandshakeInterceptor>[] interceptors() default {};
}
