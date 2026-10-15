package com.xinli.aperture.channel.spi;

import com.xinli.aperture.channel.AbstractChannelHandler;
import com.xinli.aperture.channel.AprController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aperture 全自动注册器 —— 实现 {@link WebSocketConfigurer},启动期把 {@code @AprController}
 * 业务聚合类绑到 Spring 的 {@code WebSocketHandlerRegistry}。
 *
 * <p>设计要点:
 * <ul>
 *   <li>{@code @AprController} 业务聚合类自身就继承 {@link AbstractChannelHandler};本注册器
 *       <b>复用同一个 bean 实例</b>,适配层只在 WebSocket 协议层需要时介入;</li>
 *   <li>{@link WebSocketConfigurer#registerWebSocketHandlers(WebSocketHandlerRegistry)} 由 Spring
 *       WebSocket 自动调用,正好在所有其它 bean 都初始化完毕时执行,可安全扫描;</li>
 *   <li>同一 path 被两个 bean 占用,启动期抛 {@code IllegalStateException}。</li>
 * </ul>
 *
 * @since Aperture 0.3
 */
@Slf4j
@Component
public class AprControllerRegistry implements WebSocketConfigurer, BeanFactoryAware {

    private final ObjectProvider<AbstractChannelHandler<?>> handlers;
    private BeanFactory beanFactory;

    public AprControllerRegistry(ObjectProvider<AbstractChannelHandler<?>> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        Map<String, AbstractChannelHandler<?>> indexed = indexByController();

        indexed.forEach((path, handler) -> {
            AprController ann = handler.getClass().getAnnotation(AprController.class);
            WebSocketHandler springHandler = new AprWebSocketAdapter(handler);
            WebSocketHandlerRegistration registration = registry.addHandler(springHandler, path);
            for (Class<? extends HandshakeInterceptor> interceptorClass : ann.interceptors()) {
                HandshakeInterceptor interceptor = beanFactory.getBean(interceptorClass);
                registration.addInterceptors(interceptor);
            }
            registration.setAllowedOrigins("*");
            log.info("[aperture] 注册 controller path={} handler={}", path, handler.getClass().getName());
        });
    }

    private @NonNull Map<String, AbstractChannelHandler<?>> indexByController() {
        Map<String, AbstractChannelHandler<?>> map = new LinkedHashMap<>();
        for (AbstractChannelHandler<?> h : handlers) {
            AprController ann = h.getClass().getAnnotation(AprController.class);
            if (ann == null) {
                throw new IllegalStateException(
                        "AbstractChannelHandler 子类必须挂 @AprController:" + h.getClass().getName());
            }
            String path = ann.value();
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                throw new IllegalStateException("@AprController value 必须以 / 开头: " + h.getClass().getName());
            }
            AbstractChannelHandler<?> prev = map.put(path, h);
            if (prev != null) {
                throw new IllegalStateException("path " + path + " 重复绑定: "
                        + prev.getClass().getName() + " 与 " + h.getClass().getName());
            }
        }
        return map;
    }
}
