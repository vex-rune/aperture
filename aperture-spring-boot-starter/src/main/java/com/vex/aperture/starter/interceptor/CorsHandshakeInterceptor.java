package com.vex.aperture.starter.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 最小 CORS 握手拦截器 —— 写 {@code Access-Control-Allow-Origin / -Headers / -Methods} 三个响应头。
 *
 * <p>WebSocket 握手本质是 HTTP GET + 一些特殊头;浏览器在跨域升级前会先发 OPTIONS 预检。
 * 本拦截器在 beforeHandshake 同时处理:
 * <ul>
 *   <li>OPTIONS 请求 → 直接回 200 放行;</li>
 *   <li>其它 → 写 CORS 响应头后放行。</li>
 * </ul>
 *
 * <p>配置来自 {@link CorsHandshakeInterceptor#CorsHandshakeInterceptor(String[])}
 * 或 {@link CorsHandshakeInterceptor#CorsHandshakeInterceptor(CorsConfiguration)},
 * 由 {@link com.vex.aperture.starter.configurer.ApertureConfigurer} 在构造期装配。
 *
 * @since Aperture 0.4
 */
public class CorsHandshakeInterceptor implements HandshakeInterceptor {

    private final CorsConfiguration config;

    public CorsHandshakeInterceptor(String... origins) {
        CorsConfiguration c = new CorsConfiguration();
        if (origins != null && origins.length > 0) {
            c.setAllowedOrigins(java.util.Arrays.asList(origins));
        } else {
            c.addAllowedOrigin("*");
        }
        c.addAllowedMethod("*");
        c.addAllowedHeader("*");
        this.config = c;
    }

    public CorsHandshakeInterceptor(CorsConfiguration config) {
        this.config = (config == null) ? defaultConfig() : config;
    }

    private static CorsConfiguration defaultConfig() {
        CorsConfiguration c = new CorsConfiguration();
        c.addAllowedOrigin("*");
        c.addAllowedMethod("*");
        c.addAllowedHeader("*");
        return c;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        boolean preflight = false;
        if (request instanceof ServletServerHttpRequest ssr) {
            HttpServletRequest raw = ssr.getServletRequest();
            if (HttpMethod.OPTIONS.matches(raw.getMethod())) {
                preflight = true;
                attributes.put("aperture.cors.preflight", Boolean.TRUE);
            }
        }

        String origin = firstHeader(request, "Origin");
        if (origin != null) {
            writeIfPresent(response, "Access-Control-Allow-Origin", resolveAllowOrigin(origin));
        }
        writeIfPresent(response, "Access-Control-Allow-Methods", String.join(", ", config.getAllowedMethods().stream().map(Object::toString).toList()));
        writeIfPresent(response, "Access-Control-Allow-Headers", String.join(", ", config.getAllowedHeaders()));
        writeIfPresent(response, "Access-Control-Max-Age", "3600");

        if (preflight) {
            // OPTIONS 预检直接 200,不进入 ws 升级。
            response.setStatusCode(HttpStatus.OK);
        }
        return !preflight;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private static String firstHeader(ServerHttpRequest request, String name) {
        var v = request.getHeaders().get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private String resolveAllowOrigin(String origin) {
        if (config.getAllowedOrigins() == null || config.getAllowedOrigins().isEmpty()) {
            return origin;
        }
        if (config.getAllowedOrigins().contains("*")) {
            return "*";
        }
        if (config.getAllowedOrigins().contains(origin)) {
            return origin;
        }
        return config.getAllowedOrigins().get(0);
    }

    private static void writeIfPresent(ServerHttpResponse response, String name, String value) {
        if (value == null || value.isEmpty()) return;
        if (response.getHeaders().getFirst(name) == null) {
            response.getHeaders().add(name, value);
        }
    }
}