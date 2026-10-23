package com.vex.aperture.channel.spi;

import com.vex.aperture.autoconfigure.ApertureControllerConfigurer;
import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.AprController;
import com.vex.aperture.channel.plugin.AprPluginScheduler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aperture 全自动注册器 —— 启动期把 {@code @AprController} 业务聚合类绑到 Spring 的 {@code WebSocketHandlerMapping},使任意 Spring Web 工程的
 * {@code @AprController("/path")} 暴露为可访问的 ws endpoint。
 *
 * <p><b>为什么不再依赖 {@code WebSocketConfigurer}</b>:Spring Boot 4.x 的 servlet stack
 * <b>不</b>提供 {@code WebSocketAutoConfiguration},即
 * {@code spring-boot-starter-websocket} 装上后
 * {@link org.springframework.web.socket.config.annotation.WebSocketConfigurer} bean 也不会被 Spring 自动调起 —— 我们必须主动注册
 * {@code WebSocketHandlerMapping} 才能让 endpoint 生效。
 *
 * <p>本类做的事:
 * <ol>
 *   <li>{@link PostConstruct} 阶段扫描所有 {@link AbstractChannelHandler} 子类(取其
 *       {@link AprController} 注解);</li>
 *   <li>对每个 controller 构造 {@link WebSocketHttpRequestHandler} + 拦截器 / CORS / 共享配置;</li>
 *   <li>把 ({@code path → handler}) map 投到 {@link WebSocketHandlerMapping}
 *       ({@code @Bean} 由本类的 {@link #apertureWebSocketHandlerMapping()} 方法注册),</li>
 *   <li>Spring MVC 启动时识别 {@code HandlerMapping} bean,自动把请求路由到对应 ws handler。</li>
 * </ol>
 *
 * <p>设计要点:
 * <ul>
 *   <li>同一 path 被两个 bean 占用 → 启动期抛 {@code IllegalStateException};</li>
 *   <li>{@link ApertureControllerConfigurer} 跨 controller 共享路径前缀 / 拦截器 / CORS / 空闲超时;</li>
 *   <li>configurer 未提供时,行为与 0.3.0 完全一致(零破坏)。</li>
 * </ul>
 *
 * @since Aperture 0.3(0.5 起改为 {@code WebSocketHandlerMapping} 自注册模式)
 */
@Slf4j
@Component
public class AprControllerRegistry implements BeanFactoryAware {

    private final ObjectProvider<AbstractChannelHandler<?>> handlers;
    private final ObjectProvider<ApertureControllerConfigurer> configurerProvider;
    private final AprPluginScheduler scheduler;
    private BeanFactory beanFactory;

    public AprControllerRegistry(
            ObjectProvider<AbstractChannelHandler<?>> handlers,
            ObjectProvider<ApertureControllerConfigurer> configurerProvider,
            AprPluginScheduler scheduler) {
        this.handlers = handlers;
        this.configurerProvider = configurerProvider;
        this.scheduler = scheduler;
    }

    private static String concatPath(String prefix, String value) {
        String p = (prefix == null) ? "" : prefix;
        if (p.isEmpty() || "/".equals(p)) {
            return value;
        }
        if (value == null || value.isEmpty()) {
            return p;
        }
        if (value.startsWith("/")) {
            return p + value;
        }
        return p + "/" + value;
    }

    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 启动期扫所有 {@code @AprController} bean,投到 {@link WebSocketHandlerMapping}。 暴露为 {@code @Bean} 让 Spring MVC 在启动期发现并接入
     * servlet 路由表。
     */
    @Bean
    public WebSocketHandlerMapping apertureWebSocketHandlerMapping() {
        List<ApertureControllerConfigurer> configurers = collectConfigurers();
        ApertureControllerConfigurer.GlobalContext global =
                new ApertureControllerConfigurer.GlobalContext();
        for (ApertureControllerConfigurer c : configurers) {
            c.configure(global);
        }

        Map<String, AbstractChannelHandler<?>> indexed = indexByController();
        String globalPrefix = global.pathPrefix();

        Map<String, HttpRequestHandler> urlMap = new LinkedHashMap<>();
        indexed.forEach((path, handler) -> {
            AprController ann = handler.getClass().getAnnotation(AprController.class);
            String effectivePath = concatPath(globalPrefix, path);
            WebSocketHandler wsHandler = new AprWebSocketAdapter(handler, scheduler);

            // 1) annotation 自带拦截器
            List<HandshakeInterceptor> interceptorList = new ArrayList<>();
            for (Class<? extends HandshakeInterceptor> interceptorClass : ann.interceptors()) {
                interceptorList.add(beanFactory.getBean(interceptorClass));
            }

            // 2) configurer 追加 —— 用 no-op WebSocketHandlerRegistration 收集拦截器
            CollectingRegistration collecting = new CollectingRegistration(wsHandler, effectivePath);
            ApertureControllerConfigurer.PerControllerContext ctx =
                    new ApertureControllerConfigurer.PerControllerContext(
                            handler.getClass(), ann, effectivePath, collecting);
            for (ApertureControllerConfigurer c : configurers) {
                c.configureController(ctx);
            }
            interceptorList.addAll(java.util.Arrays.asList(ctx.resolvedInterceptors()));
            if (collecting.shouldStop()) {
                // configurer 显式返回 false —— 跳过该 controller
                log.warn("[aperture] controller path={} 被 configurer 拒绝注册", effectivePath);
                return;
            }

            WebSocketHttpRequestHandler httpHandler = new WebSocketHttpRequestHandler(
                    wsHandler, new DefaultHandshakeHandler());
            if (!interceptorList.isEmpty()) {
                httpHandler.setHandshakeInterceptors(interceptorList);
            }
            urlMap.put(effectivePath, httpHandler);
            log.info("[aperture] 注册 controller path={} handler={} interceptors={}",
                    effectivePath, handler.getClass().getName(), interceptorList.size());
        });

        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(urlMap);
        mapping.setOrder(0);  // 高优先级 —— 优先于普通 RequestMapping
        return mapping;
    }

    private @NonNull List<ApertureControllerConfigurer> collectConfigurers() {
        List<ApertureControllerConfigurer> list = new ArrayList<>();
        configurerProvider.forEach(list::add);
        return list;
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
            if (path == null) {
                throw new IllegalStateException("@AprController value 不能为 null: " + h.getClass().getName());
            }
            if (!path.isEmpty() && !path.startsWith("/")) {
                throw new IllegalStateException(
                        "@AprController value 必须以 / 开头(或留空由 configurer 提供): " + h.getClass().getName());
            }
            AbstractChannelHandler<?> prev = map.put(path, h);
            if (prev != null) {
                throw new IllegalStateException("path " + path + " 重复绑定: "
                        + prev.getClass().getName() + " 与 " + h.getClass().getName());
            }
        }
        return map;
    }

    /**
     * 内部用 no-op {@link WebSocketHandlerRegistration} —— 仅收集 configurer 写入的拦截器 与开关,不实际调用其 setter(因为我们直接 new 造
     * {@link WebSocketHttpRequestHandler})。
     */
    private static final class CollectingRegistration implements WebSocketHandlerRegistration {
        private final WebSocketHandler handler;
        private final String path;
        private final List<HandshakeInterceptor> interceptors = new ArrayList<>();
        private String[] allowedOrigins;
        private boolean stop;

        CollectingRegistration(WebSocketHandler handler, String path) {
            this.handler = handler;
            this.path = path;
        }

        boolean shouldStop() {
            return stop;
        }

        @Override
        public WebSocketHandlerRegistration addInterceptors(HandshakeInterceptor... ints) {
            if (ints != null) for (HandshakeInterceptor i : ints) {
                if (i != null) interceptors.add(i);
            }
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOrigins(String... origins) {
            this.allowedOrigins = origins;
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOriginPatterns(String... patterns) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setHandshakeHandler(org.springframework.web.socket.server.HandshakeHandler handler) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler wsHandler, String... paths) {
            throw new UnsupportedOperationException();
        }

        // SockJS 不使用
        @Override
        public org.springframework.web.socket.config.annotation.SockJsServiceRegistration withSockJS() {
            this.stop = true;
            return new org.springframework.web.socket.config.annotation.SockJsServiceRegistration();
        }
    }
}