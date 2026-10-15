package com.vex.aperture.starter.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * 把请求头里的 traceId 同步到 SLF4J MDC 的握手期拦截器。
 *
 * <p>握手通过后 {@code MDC} 已就位 —— 所有后续 {@code log.info(...)} 都会带上该 key,
 * 直到 {@link #afterHandshake} 清理。
 *
 * <p>traceId 来源:
 * <ol>
 *   <li>请求头(默认 {@code X-Trace-Id},可在 yaml 改 {@code aperture.interceptors.trace.key});</li>
 *   <li>缺省时 {@link UUID#randomUUID()} 生成。</li>
 * </ol>
 *
 * <p>启用:yaml 写 {@code aperture.interceptors.trace.enabled: true}。本拦截器由
 * {@link com.vex.aperture.starter.configurer.ApertureConfigurer} 在构造期注入,
 * 而不是 Spring bean —— 这样 starter 不依赖 Spring bean 注入,只走
 * {@link HandshakeInterceptor} 接口。
 *
 * @since Aperture 0.4
 */
public class TraceHandshakeInterceptor implements HandshakeInterceptor {

    /** MDC 默认 key。 */
    public static final String DEFAULT_MDC_KEY = "traceId";

    private final String headerKey;
    private final String mdcKey;

    public TraceHandshakeInterceptor(String headerKey) {
        this(headerKey, DEFAULT_MDC_KEY);
    }

    public TraceHandshakeInterceptor(String headerKey, String mdcKey) {
        this.headerKey = (headerKey == null || headerKey.isBlank()) ? "X-Trace-Id" : headerKey;
        this.mdcKey = (mdcKey == null || mdcKey.isBlank()) ? DEFAULT_MDC_KEY : mdcKey;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String traceId = extractHeader(request);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        attributes.put(mdcKey, traceId);
        MDC.put(mdcKey, traceId);
        if (response instanceof ServletServerHttpResponse sshr) {
            HttpServletResponse raw = sshr.getServletResponse();
            raw.setHeader(this.headerKey, traceId);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        MDC.remove(mdcKey);
    }

    private String extractHeader(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest ssr) {
            HttpServletRequest raw = ssr.getServletRequest();
            String v = raw.getHeader(this.headerKey);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        // fallback: 标准 ServerHttpRequest 头
        var headers = request.getHeaders().get(this.headerKey);
        if (headers != null && !headers.isEmpty()) {
            return headers.get(0);
        }
        return null;
    }
}