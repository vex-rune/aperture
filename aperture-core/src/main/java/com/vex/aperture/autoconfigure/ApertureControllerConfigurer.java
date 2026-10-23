package com.vex.aperture.autoconfigure;

import com.vex.aperture.channel.AprController;
import com.vex.aperture.channel.spi.AprControllerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Aperture 跨 controller 共享配置 —— Spring WebMvc 的 {@code WebMvcConfigurer} 视角等价物。
 *
 * <p>业务方实现本接口并加 {@code @Component};{@link AprControllerRegistry} 启动期拉所有
 * configurer bean,按依赖顺序触发回调。Configurer 可在两个层级配置:
 *
 * <ul>
 *   <li><b>registry-level</b>(影响所有 controller):路径前缀
 *       {@link GlobalContext#pathPrefix(String)};</li>
 *   <li><b>per-controller</b>(只影响当前 controller):拦截器链
 *       {@link PerControllerContext#addInterceptors(HandshakeInterceptor...)}
 *       / allowedOrigins / idleTimeout。</li>
 * </ul>
 *
 * <p><b>向后兼容</b>:不实现 {@code ApertureControllerConfigurer} 时,行为与 0.3.0 完全一致。
 * 已有 controller 的 {@link AprController#interceptors()} 仍按旧规则优先 ——
 * configurer 追加的拦截器排在 annotation 拦截器之后。
 *
 * <p>典型用法:
 * <pre>{@code
 * @Component
 * public class WsConfigurer implements ApertureControllerConfigurer {
 *     @Override
 *     public void configure(GlobalContext global) {
 *         global.pathPrefix("/api");   // 所有 controller 路径前都拼 /api
 *     }
 *
 *     @Override
 *     public void configureController(PerControllerContext ctx) {
 *         ctx.addInterceptors(new TraceInterceptor(), new JwtHandshakeInterceptor(...))
 *            .allowedOrigins("*")
 *            .idleTimeout(Duration.ofMinutes(5));
 *     }
 * }
 * }</pre>
 *
 * <p>挂到 {@code @ComponentScan} 路径下即可,不需要 {@code @Enable*} 触发。
 *
 * @since Aperture 0.4
 */
public interface ApertureControllerConfigurer {

    /**
     * 全局配置 —— registry 启动期只调一次,所有 controller 都生效。
     *
     * <p>默认 no-op。
     */
    default void configure(GlobalContext global) {
    }

    /**
     * 每个 controller 单独调一次。
     *
     * <p>默认 no-op。
     */
    default void configureController(PerControllerContext ctx) {
    }

    /**
     * Registry-level 上下文 —— 影响所有 controller。
     */
    final class GlobalContext {

        private final Set<String> seenPathPrefixes = new HashSet<>();
        private String pathPrefix = "";

        void setPathPrefix(String prefix) {
            String normalized = (prefix == null) ? "" : prefix;
            if (!normalized.isEmpty() && !normalized.startsWith("/")) {
                throw new IllegalArgumentException("path-prefix 必须以 / 开头或为空: " + normalized);
            }
            if (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            // 第一次设:直接落;之后再设只接受同值,不同值视作冲突。
            if (seenPathPrefixes.isEmpty()) {
                seenPathPrefixes.add(normalized);
                this.pathPrefix = normalized;
                return;
            }
            if (!normalized.equals(pathPrefix)) {
                throw new IllegalStateException(
                        "path-prefix 已被其它 configurer 设置为 \"" + pathPrefix
                                + "\",再次设为 \"" + normalized + "\" 视为冲突");
            }
        }

        public String pathPrefix() {
            return pathPrefix;
        }

        /**
         * 给所有 controller 路径加前缀;空字符串 / 多次调用不二次累加。
         */
        public GlobalContext pathPrefix(String prefix) {
            setPathPrefix(prefix);
            return this;
        }
    }

    /**
     * Per-controller 上下文 —— 当前 controller 注册期的可写钩子。
     */
    final class PerControllerContext {

        private final Class<?> controllerClass;
        private final AprController annotation;
        private final String effectivePath;
        private final WebSocketHandlerRegistration registration;

        private final Map<String, HandshakeInterceptor> interceptors = new LinkedHashMap<>();
        private String[] allowedOrigins;
        private Duration idleTimeout;

        public PerControllerContext(Class<?> controllerClass,
                                    AprController annotation,
                                    String effectivePath,
                                    WebSocketHandlerRegistration registration) {
            this.controllerClass = controllerClass;
            this.annotation = annotation;
            this.effectivePath = effectivePath;
            this.registration = registration;
        }

        public Class<?> controllerClass() {
            return controllerClass;
        }

        public AprController annotation() {
            return annotation;
        }

        /**
         * 此次实际暴露的 controller 路径(global path-prefix + annotation value)。
         */
        public String effectivePath() {
            return effectivePath;
        }

        /**
         * 追加拦截器;按 class 名去重(同一拦截器类不会重复添加)。
         */
        public PerControllerContext addInterceptors(HandshakeInterceptor... interceptors) {
            if (interceptors == null) return this;
            for (HandshakeInterceptor i : interceptors) {
                if (i == null) continue;
                this.interceptors.putIfAbsent(i.getClass().getName(), i);
            }
            return this;
        }

        public PerControllerContext allowedOrigins(String... origins) {
            this.allowedOrigins = origins;
            return this;
        }

        public PerControllerContext idleTimeout(Duration timeout) {
            this.idleTimeout = timeout;
            return this;
        }

        public HandshakeInterceptor[] resolvedInterceptors() {
            return this.interceptors.values().toArray(HandshakeInterceptor[]::new);
        }

        public String[] resolvedAllowedOrigins() {
            return allowedOrigins;
        }

        public Duration resolvedIdleTimeout() {
            return idleTimeout;
        }

        public WebSocketHandlerRegistration registration() {
            return registration;
        }
    }
}
