package com.vex.aperture.starter.configurer;

import com.vex.aperture.autoconfigure.ApertureControllerConfigurer;
import com.vex.aperture.starter.ApertureProperties;
import com.vex.aperture.starter.interceptor.CorsHandshakeInterceptor;
import com.vex.aperture.starter.interceptor.TraceHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApertureConfigurerTest {

    @Test
    void builder_sets_global_path_prefix_on_configure() {
        ApertureControllerConfigurer cfg = ApertureConfigurer.create("demo")
                .pathPrefix("/api")
                .build();

        ApertureControllerConfigurer.GlobalContext global =
                new ApertureControllerConfigurer.GlobalContext();
        cfg.configure(global);
        assertEquals("/api", global.pathPrefix());
    }

    @Test
    void builder_passes_trace_and_cors_into_addInterceptors_chain() {
        WebSocketHandlerRegistration reg = mock(WebSocketHandlerRegistration.class);
        when(reg.addInterceptors(any())).thenReturn(reg);
        ApertureProperties props = new ApertureProperties();
        props.setBasePackages("com.example");
        ApertureControllerConfigurer.PerControllerContext ctx =
                new ApertureControllerConfigurer.PerControllerContext(
                        getClass(), null, "/x", reg);

        ApertureControllerConfigurer cfg = ApertureConfigurer.create("demo")
                .useTrace(props)
                .useCors(props, "*.example.com")
                .build();
        cfg.configureController(ctx);

        // ctx 自身收集拦截器(由 registry 端拉走并交付 spring)。检查 ctx 包含 2 个。
        HandshakeInterceptor[] resolved = ctx.resolvedInterceptors();
        assertEquals(2, resolved.length);
        assertInstanceOf(TraceHandshakeInterceptor.class, resolved[0]);
        assertInstanceOf(CorsHandshakeInterceptor.class, resolved[1]);
        // registry 不在本测试覆盖范围。
    }

    @Test
    void trace_disabled_skips_trace_interceptor() {
        WebSocketHandlerRegistration reg = mock(WebSocketHandlerRegistration.class);
        when(reg.addInterceptors(any())).thenReturn(reg);
        ApertureProperties props = new ApertureProperties();
        props.getInterceptors().getTrace().setEnabled(false);
        props.getInterceptors().getCors().setEnabled(true);

        ApertureControllerConfigurer.PerControllerContext ctx =
                new ApertureControllerConfigurer.PerControllerContext(
                        getClass(), null, "/x", reg);
        ApertureControllerConfigurer cfg = ApertureConfigurer.create("demo")
                .useTrace(props)
                .useCors(props, "*")
                .build();
        cfg.configureController(ctx);

        HandshakeInterceptor[] resolved = ctx.resolvedInterceptors();
        assertEquals(1, resolved.length);
        assertInstanceOf(CorsHandshakeInterceptor.class, resolved[0]);
    }

    @Test
    void both_disabled_emits_no_interceptor() {
        WebSocketHandlerRegistration reg = mock(WebSocketHandlerRegistration.class);
        when(reg.addInterceptors(any())).thenReturn(reg);
        ApertureControllerConfigurer.PerControllerContext ctx =
                new ApertureControllerConfigurer.PerControllerContext(
                        getClass(), null, "/x", reg);

        ApertureControllerConfigurer cfg = ApertureConfigurer.create("demo")
                .useTrace(false)
                .useCors(false)
                .build();
        cfg.configureController(ctx);

        assertEquals(0, ctx.resolvedInterceptors().length);
    }

    @Test
    void name_holds() {
        ApertureControllerConfigurer cfg = ApertureConfigurer.create("asr").build();
        assertEquals("ApertureConfigurer[asr]", cfg.toString());
    }

    @Test
    void allowed_origins_propagate_to_context() {
        WebSocketHandlerRegistration reg = mock(WebSocketHandlerRegistration.class);
        when(reg.addInterceptors(any())).thenReturn(reg);
        ApertureProperties props = new ApertureProperties();
        ApertureControllerConfigurer.PerControllerContext ctx =
                new ApertureControllerConfigurer.PerControllerContext(
                        getClass(), null, "/x", reg);
        ApertureControllerConfigurer cfg = ApertureConfigurer.create("demo")
                .useCors(props, "a.example.com", "c.example.com")
                .build();
        cfg.configureController(ctx);
        assertArrayEquals(new String[]{"a.example.com", "c.example.com"}, ctx.resolvedAllowedOrigins());
    }
}