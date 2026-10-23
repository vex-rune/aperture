package com.vex.aperture.autoconfigure;

import com.vex.aperture.autoconfigure.ApertureControllerConfigurer.GlobalContext;
import com.vex.aperture.autoconfigure.ApertureControllerConfigurer.PerControllerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 纯 POJO 测试 —— 不接 Spring,只验证 setter 与 normalization 行为。
class ApertureControllerConfigurerTest {

    private static PerControllerContext makeCtx() {
        WebSocketHandlerRegistration reg = stubRegistration();
        com.vex.aperture.channel.AprController mockAnn = new com.vex.aperture.channel.AprController() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return com.vex.aperture.channel.AprController.class;
            }

            @Override
            public String value() {
                return "/chat";
            }

            @Override
            public Class<? extends HandshakeInterceptor>[] interceptors() {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends HandshakeInterceptor>[] empty = new Class[0];
                return empty;
            }
        };
        return new PerControllerContext(ApertureControllerConfigurerTest.class, mockAnn, "/api/chat", reg);
    }

    /**
     * 不接 Spring 的 stub,只让 ctor 通过。
     */
    private static WebSocketHandlerRegistration stubRegistration() {
        return org.mockito.Mockito.mock(WebSocketHandlerRegistration.class);
    }

    @Test
    @DisplayName("GlobalContext.pathPrefix 标准化:首尾斜杠 / 多个空值")
    void globalPathPrefixNormalize() {
        GlobalContext g = new GlobalContext();
        assertThat(g.pathPrefix()).isEmpty();

        GlobalContext g1 = new GlobalContext();
        g1.pathPrefix("/api");
        assertThat(g1.pathPrefix()).isEqualTo("/api");

        GlobalContext g2 = new GlobalContext();
        g2.pathPrefix("/api/");
        assertThat(g2.pathPrefix()).isEqualTo("/api");

        GlobalContext g3 = new GlobalContext();
        g3.pathPrefix("/");
        assertThat(g3.pathPrefix()).isEmpty();
    }

    @Test
    @DisplayName("pathPrefix 多次设同一个值 —— 允许")
    void globalPathPrefixDedup() {
        GlobalContext g = new GlobalContext();
        g.pathPrefix("/api");
        g.pathPrefix("/api");
        assertThat(g.pathPrefix()).isEqualTo("/api");
    }

    @Test
    @DisplayName("pathPrefix 非法格式 —— 启动期抛 IllegalArgumentException")
    void globalPathPrefixInvalid() {
        GlobalContext g = new GlobalContext();
        assertThatThrownBy(() -> g.pathPrefix("api"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pathPrefix 设置不同值 —— 抛 IllegalStateException")
    void globalPathPrefixConflict() {
        GlobalContext g = new GlobalContext();
        g.pathPrefix("/api");
        assertThatThrownBy(() -> g.pathPrefix("/ws"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PerControllerContext 拦截器按 class 名去重")
    void perControllerInterceptorDedup() {
        PerControllerContext ctx = makeCtx();
        HandshakeInterceptor i1 = new UniqueInterceptor();
        ctx.addInterceptors(i1, i1);
        assertThat(ctx.resolvedInterceptors()).hasSize(1);
    }

    @Test
    @DisplayName("PerControllerContext effectivePath 与 controllerClass 透传")
    void perControllerPassThrough() {
        PerControllerContext ctx = makeCtx();
        assertThat(ctx.effectivePath()).isEqualTo("/api/chat");
        assertThat(ctx.controllerClass().getSimpleName())
                .isEqualTo("ApertureControllerConfigurerTest");
    }

    /**
     * 标志性 class —— 同一 class 名多次添加时去重。
     */
    private static final class UniqueInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       java.util.Map<String, Object> attributes) {
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
