package com.vex.aperture.starter.configurer;

import com.vex.aperture.autoconfigure.ApertureControllerConfigurer;
import com.vex.aperture.starter.ApertureProperties;
import com.vex.aperture.starter.interceptor.CorsHandshakeInterceptor;
import com.vex.aperture.starter.interceptor.TraceHandshakeInterceptor;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * starter 给业务方的"一键配置类" —— 一个 configurer 实例 = 一组跨 controller 共享行为。
 *
 * <p>典型用法(每通道一份):
 * <pre>{@code
 * @Configuration
 * public class WsConfig {
 *     @Bean
 *     public ApertureControllerConfigurer asrConfigurer(ApertureProperties props) {
 *         return ApertureConfigurer.create("asr")
 *                 .pathPrefix("/ac")
 *                 .useTrace(props)        // 启/关由 yaml 决定
 *                 .useCors(props, "*.example.com")
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * <p>一个工程可放多份(每通道一份),Aperture 框架
 * {@link com.vex.aperture.channel.spi.AprControllerRegistry} 会按 {@code ObjectProvider} 顺序
 * 收集全部 configurer。**注意**:同一份全局属性(如 {@code path-prefix})被多个 configurer
 * 同时设置会被 framework 视为冲突 —— {@link ApertureControllerConfigurer.GlobalContext#pathPrefix(String)}
 * 会抛 {@code IllegalStateException},这正是设计意图。
 *
 * <p>设计取舍:
 * <ul>
 *   <li>本类<b>不是</b> {@code @ConfigurationProperties} 绑定类 —— 多通道场景下每个
 *       configurer 要按通道区分,所以由业务方显式 new;</li>
 *   <li>拦截器实例不共享:每次 {@code useTrace/build} 都 new 一份,避免多通道串扰;</li>
 *   <li>框架核心(纯 framework) <b>不</b>依赖本 starter;starter 仅是"开箱即用的便捷
 *       实现",业务方不引入 starter 也可自己实现 {@link ApertureControllerConfigurer}。</li>
 * </ul>
 *
 * @since Aperture 0.4
 */
public final class ApertureConfigurer {

    private final String name;
    private String pathPrefix;
    private boolean traceEnabled = false;
    private String traceHeaderKey = "X-Trace-Id";
    private String traceMdcKey = TraceHandshakeInterceptor.DEFAULT_MDC_KEY;
    private boolean corsEnabled = false;
    private String[] corsOrigins = new String[]{"*"};
    private Duration idleTimeout;
    private final List<HandshakeInterceptor> extraInterceptors = new ArrayList<>();

    private ApertureConfigurer(String name) {
        this.name = (name == null || name.isBlank()) ? "default" : name;
    }

    public static ApertureConfigurer create() {
        return new ApertureConfigurer("default");
    }

    public static ApertureConfigurer create(String name) {
        return new ApertureConfigurer(name);
    }

    public ApertureConfigurer pathPrefix(String prefix) {
        this.pathPrefix = prefix;
        return this;
    }

    /** 从 yaml 配置里读取 trace 开关与 key。 */
    public ApertureConfigurer useTrace(ApertureProperties props) {
        if (props == null) return this;
        this.traceEnabled = props.getInterceptors().getTrace().isEnabled();
        this.traceHeaderKey = props.getInterceptors().getTrace().getKey();
        return this;
    }

    /** 显式 trace 开关。 */
    public ApertureConfigurer useTrace(boolean enabled) {
        this.traceEnabled = enabled;
        return this;
    }

    public ApertureConfigurer traceMdcKey(String key) {
        this.traceMdcKey = key;
        return this;
    }

    /** 从 yaml + 业务方给的 origins 列表装配 CORS 拦截器。 */
    public ApertureConfigurer useCors(ApertureProperties props, String... origins) {
        if (props == null) {
            this.corsEnabled = true;
            this.corsOrigins = (origins == null || origins.length == 0) ? new String[]{"*"} : origins;
            return this;
        }
        this.corsEnabled = props.getInterceptors().getCors().isEnabled();
        if (origins != null && origins.length > 0) {
            this.corsOrigins = origins;
        } else {
            this.corsOrigins = new String[]{props.getAllowedOrigins()};
        }
        return this;
    }

    public ApertureConfigurer useCors(boolean enabled, String... origins) {
        this.corsEnabled = enabled;
        if (origins != null && origins.length > 0) {
            this.corsOrigins = origins;
        }
        return this;
    }

    public ApertureConfigurer idleTimeout(Duration timeout) {
        this.idleTimeout = timeout;
        return this;
    }

    public ApertureConfigurer addInterceptors(HandshakeInterceptor... interceptors) {
        if (interceptors != null) {
            for (HandshakeInterceptor i : interceptors) {
                if (i != null) extraInterceptors.add(i);
            }
        }
        return this;
    }

    public String name() {
        return name;
    }

    /**
     * 装配出 {@link ApertureControllerConfigurer} 实例 —— 挂到 {@code @Configuration} 里当 {@code @Bean}。
     */
    public ApertureControllerConfigurer build() {
        return new Built(name, pathPrefix, traceEnabled, traceHeaderKey, traceMdcKey,
                corsEnabled, corsOrigins, idleTimeout, List.copyOf(extraInterceptors));
    }

    /**
     * 把当前 builder 的所有拦截器展开,按顺序返回。
     */
    private List<HandshakeInterceptor> resolveInterceptors() {
        List<HandshakeInterceptor> all = new ArrayList<>();
        if (traceEnabled) {
            all.add(new TraceHandshakeInterceptor(traceHeaderKey, traceMdcKey));
        }
        if (corsEnabled) {
            all.add(new CorsHandshakeInterceptor(corsOrigins));
        }
        all.addAll(extraInterceptors);
        return all;
    }

    // --- inner -----------------------------------------------------------

    private static final class Built implements ApertureControllerConfigurer {

        private final String name;
        private final String pathPrefix;
        private final List<HandshakeInterceptor> interceptors;
        private final String[] allowedOrigins;
        private final Duration idleTimeout;

        Built(String name, String pathPrefix,
              boolean traceEnabled, String traceHeaderKey, String traceMdcKey,
              boolean corsEnabled, String[] corsOrigins,
              Duration idleTimeout, List<HandshakeInterceptor> extra) {
            this.name = name;
            this.pathPrefix = pathPrefix;
            this.interceptors = compose(traceEnabled, traceHeaderKey, traceMdcKey,
                    corsEnabled, corsOrigins, extra);
            this.allowedOrigins = corsEnabled ? corsOrigins : null;
            this.idleTimeout = idleTimeout;
        }

        private static List<HandshakeInterceptor> compose(
                boolean traceEnabled, String traceHeaderKey, String traceMdcKey,
                boolean corsEnabled, String[] corsOrigins,
                List<HandshakeInterceptor> extra) {
            List<HandshakeInterceptor> list = new ArrayList<>();
            if (traceEnabled) {
                list.add(new TraceHandshakeInterceptor(traceHeaderKey, traceMdcKey));
            }
            if (corsEnabled) {
                list.add(new CorsHandshakeInterceptor(corsOrigins));
            }
            list.addAll(extra);
            return list;
        }

        @Override
        public void configure(GlobalContext global) {
            if (pathPrefix != null && !pathPrefix.isEmpty()) {
                global.pathPrefix(pathPrefix);
            }
        }

        @Override
        public void configureController(PerControllerContext ctx) {
            ctx.addInterceptors(interceptors.toArray(HandshakeInterceptor[]::new));
            if (allowedOrigins != null) {
                ctx.allowedOrigins(allowedOrigins);
            }
            if (idleTimeout != null) {
                ctx.idleTimeout(idleTimeout);
            }
        }

        @Override
        public String toString() {
            return "ApertureConfigurer[" + name + "]";
        }
    }
}