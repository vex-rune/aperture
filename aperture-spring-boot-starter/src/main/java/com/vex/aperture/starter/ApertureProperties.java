package com.vex.aperture.starter;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code aperture.*} 在 application.yml 的全部配置项。
 *
 * <pre>{@code
 * aperture:
 *   enabled: true
 *   base-packages: com.acme.ws           # 必填,扫这些包内的 @AprController
 *   path-prefix: /api                   # 可选;跨 controller 共享前缀
 *   allowed-origins: "*"                # 可选;默认 "*"
 *   idle-timeout: 5m                    # 可选;握手后空闲超时
 *   interceptors:
 *     trace:
 *       enabled: true                   # 写 traceId 到 MDC,贯穿 websession
 *     cors:
 *       enabled: true                   # 简单 CORS,Origin 白名单
 * }</pre>
 *
 * @since Aperture 0.4
 */
@Validated
@ConfigurationProperties(prefix = "aperture")
public class ApertureProperties {

    /** 是否启用 starter 自动配置;设为 false 完全跳过。 */
    private boolean enabled = true;

    /** 起始包列表,逗号分隔,yaml 也可写数组。 */
    @NotBlank(message = "aperture.base-packages 不能为空")
    private String basePackages = "";

    /** 全局路径前缀;为空表示无。 */
    private String pathPrefix = "";

    /** Cross-origin 允许列表;Spring 允许通配符。 */
    private String allowedOrigins = "*";

    /** 会话空闲超时;Time-based,如 30s / 5m / 1h。 */
    private Duration idleTimeout = Duration.ofMinutes(5);

    /** 内置拦截器链启用配置。 */
    private Interceptors interceptors = new Interceptors();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(String basePackages) {
        this.basePackages = basePackages;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Interceptors getInterceptors() {
        return interceptors;
    }

    public void setInterceptors(Interceptors interceptors) {
        this.interceptors = interceptors;
    }

    public static class Interceptors {

        private Trace trace = new Trace();
        private Cors cors = new Cors();

        public Trace getTrace() {
            return trace;
        }

        public void setTrace(Trace trace) {
            this.trace = trace;
        }

        public Cors getCors() {
            return cors;
        }

        public void setCors(Cors cors) {
            this.cors = cors;
        }

        public static class Trace {
            private boolean enabled = true;
            private String key = "X-Trace-Id";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getKey() {
                return key;
            }

            public void setKey(String key) {
                this.key = key;
            }
        }

        public static class Cors {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
