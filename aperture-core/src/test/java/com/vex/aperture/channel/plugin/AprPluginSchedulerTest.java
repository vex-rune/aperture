package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// 插件调度器单测 —— 插件链的开关隔离、拦截语义、异常降级、会话登记。
class AprPluginSchedulerTest {

    private static WebSocketSession openSession(String sid) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(sid);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return s;
    }

    /// 计数型测试插件
    static class CountingPlugin implements AprChannelPlugin {

        final String id;
        final boolean on;
        final boolean pass;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger inbounds = new AtomicInteger();
        final AtomicInteger idles = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        RuntimeException boom;

        CountingPlugin(String id, boolean on, boolean pass) {
            this.id = id;
            this.on = on;
            this.pass = pass;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public void onOpen(WebSocketSession session, Object channel) {
            opens.incrementAndGet();
            if (boom != null) {
                throw boom;
            }
        }

        @Override
        public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
            inbounds.incrementAndGet();
            if (boom != null) {
                throw boom;
            }
            return pass;
        }

        @Override
        public void onIdle(WebSocketSession session, Object channel, long idleMs) {
            idles.incrementAndGet();
        }

        @Override
        public void onClose(WebSocketSession session, Object channel) {
            closes.incrementAndGet();
        }
    }

    // ============ 开关隔离 ============

    @Test
    @DisplayName("禁用的插件完全不参与回调,启用的照常执行")
    void disabledPluginIsSkipped() {
        CountingPlugin on = new CountingPlugin("on", true, true);
        CountingPlugin off = new CountingPlugin("off", false, true);
        AprPluginScheduler scheduler = new AprPluginScheduler(List.of(on, off), new ApertureChannelProperties());

        WebSocketSession s = openSession("s1");
        scheduler.onOpen(s, null);
        scheduler.onInbound(s, null, new TextMessage("t\n{}"));
        scheduler.sweep();
        scheduler.onClose(s, null);

        assertThat(on.opens.get()).isEqualTo(1);
        assertThat(on.inbounds.get()).isEqualTo(1);
        assertThat(on.idles.get()).isEqualTo(1);
        assertThat(on.closes.get()).isEqualTo(1);

        assertThat(off.opens.get()).isZero();
        assertThat(off.inbounds.get()).isZero();
        assertThat(off.idles.get()).isZero();
        assertThat(off.closes.get()).isZero();
    }

    @Test
    @DisplayName("enabledNames —— 只列启用中的插件")
    void enabledNamesListsOnlyEnabled() {
        AprPluginScheduler scheduler = new AprPluginScheduler(
                List.of(new CountingPlugin("a", true, true), new CountingPlugin("b", false, true)),
                new ApertureChannelProperties());

        assertThat(scheduler.enabledNames()).isEqualTo("a");
    }

    // ============ 拦截语义 ============

    @Test
    @DisplayName("任一插件返回 false → 拦截该帧,后续插件不再被调用")
    void interceptStopsChain() {
        CountingPlugin first = new CountingPlugin("first", true, false);
        CountingPlugin second = new CountingPlugin("second", true, true);
        AprPluginScheduler scheduler = new AprPluginScheduler(List.of(first, second), new ApertureChannelProperties());

        WebSocketSession s = openSession("s2");
        boolean pass = scheduler.onInbound(s, null, new TextMessage("t\n{}"));

        assertThat(pass).isFalse();
        assertThat(first.inbounds.get()).isEqualTo(1);
        assertThat(second.inbounds.get()).as("被拦截后不再往后传").isZero();
    }

    @Test
    @DisplayName("全部插件放行 → 放行给业务路由")
    void allPassLetsFrameThrough() {
        AprPluginScheduler scheduler = new AprPluginScheduler(
                List.of(new CountingPlugin("a", true, true), new CountingPlugin("b", true, true)),
                new ApertureChannelProperties());

        WebSocketSession s = openSession("s3");
        assertThat(scheduler.onInbound(s, null, new TextMessage("t\n{}"))).isTrue();
    }

    // ============ 异常降级 ============

    @Test
    @DisplayName("插件抛异常 → 记录日志后继续,不穿透到业务路由")
    void pluginExceptionDoesNotBreakChain() {
        CountingPlugin boom = new CountingPlugin("boom", true, true);
        boom.boom = new IllegalStateException("boom");
        CountingPlugin after = new CountingPlugin("after", true, true);
        AprPluginScheduler scheduler = new AprPluginScheduler(List.of(boom, after), new ApertureChannelProperties());

        WebSocketSession s = openSession("s4");
        boolean pass = scheduler.onInbound(s, null, new TextMessage("t\n{}"));

        assertThat(pass).as("异常不应导致帧被吞").isTrue();
        assertThat(after.inbounds.get()).as("后续插件仍应执行").isEqualTo(1);
    }

    // ============ 会话登记 ============

    @Test
    @DisplayName("建连登记会话,断连注销")
    void sessionRegistration() {
        CountingPlugin p = new CountingPlugin("p", true, true);
        AprPluginScheduler scheduler = new AprPluginScheduler(List.of(p), new ApertureChannelProperties());

        WebSocketSession s = openSession("s5");
        scheduler.onOpen(s, null);
        assertThat(scheduler.sessionCount()).isEqualTo(1);

        scheduler.onClose(s, null);
        assertThat(scheduler.sessionCount()).isZero();
    }

    @Test
    @DisplayName("sweep —— 只对已登记会话回调 onIdle,并累计轮次")
    void sweepDrivesIdle() {
        CountingPlugin p = new CountingPlugin("p", true, true);
        AprPluginScheduler scheduler = new AprPluginScheduler(List.of(p), new ApertureChannelProperties());

        scheduler.sweep();
        assertThat(p.idles.get()).isZero();
        assertThat(scheduler.tickCount()).isEqualTo(1);

        scheduler.onOpen(openSession("s6"), null);
        scheduler.sweep();
        assertThat(p.idles.get()).isEqualTo(1);
        assertThat(scheduler.tickCount()).isEqualTo(2);
    }

    // ============ 调度周期 ============

    @Test
    @DisplayName("tickPeriodMs —— 服务端心跳开启时取其间隔,关闭时退化为 10s")
    void tickPeriodFollowsConfig() {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(5000);
        assertThat(new AprPluginScheduler(List.of(), props).tickPeriodMs()).isEqualTo(5000);

        props.getServerHeartbeat().setEnabled(false);
        assertThat(new AprPluginScheduler(List.of(), props).tickPeriodMs()).isEqualTo(10_000);
    }
}
