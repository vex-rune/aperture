package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插件调度器 —— 通道插件的注册、会话登记与空闲驱动。
 *
 * <p>职责:
 * <ol>
 *   <li>持有容器里所有 {@link AprChannelPlugin} bean(Spring 按 {@code @Order} 注入,已排好序);</li>
 *   <li>维护活跃会话表,按固定周期计算每个会话的空闲时长并回调 {@code onIdle};</li>
 *   <li>把建连 / 入站 / 断连事件广播给插件链。</li>
 * </ol>
 *
 * <p>调度周期取服务端心跳的间隔(下限 1s);服务端心跳关闭时退化为 10s 一轮,
 * 仍足以判定客户端心跳超时。
 *
 * <p>插件之间互不感知 —— 某个插件被禁用或抛异常都不影响其他插件与业务路由。
 *
 * <p>按 {@link SmartLifecycle} 在容器 refresh 后启动,单测里不起线程(直接调 {@link #sweep()})。
 *
 * @since Aperture 0.5
 */
@Slf4j
@Component
public class AprPluginScheduler implements SmartLifecycle {

    private final List<AprChannelPlugin> plugins;
    private final ApertureChannelProperties props;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong tickCount = new AtomicLong();
    private volatile boolean running;
    private volatile Thread worker;

    public AprPluginScheduler(List<AprChannelPlugin> plugins,
                             ApertureChannelProperties props) {
        this.plugins = List.copyOf(plugins);
        this.props = props;
    }

    /// 每会话的运行时状态 —— 与 WebSocketSession 分开存放,避免污染业务属性
    private static final class SessionState {
        final WebSocketSession session;
        volatile Object channel;
        volatile long lastInboundAt = System.currentTimeMillis();

        SessionState(WebSocketSession session) {
            this.session = session;
        }
    }

    // ============ 会话登记 ============

    /// 建连:登记会话并广播 onOpen
    public void onOpen(WebSocketSession session, Object channel) {
        SessionState st = new SessionState(session);
        st.channel = channel;
        sessions.put(session.getId(), st);
        forEachEnabled(p -> p.onOpen(session, channel));
        log.info("[aperture] 会话建立 sid={} 启用插件={}", session.getId(), enabledNames());
    }

    /// 断连:广播 onClose 后注销
    public void onClose(WebSocketSession session, Object channel) {
        forEachEnabled(p -> p.onClose(session, channel));
        sessions.remove(session.getId());
    }

    /// 入站:广播插件链;任一插件返回 false 即拦截,返回是否放行给业务路由
    public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
        SessionState st = sessions.get(session.getId());
        if (st != null) {
            st.channel = channel;
            // 只业务帧(文本 / 二进制)算活跃 —— Ping/Pong 是容器层控制帧,
            // 不能让一个只回 Pong 的死连接无限续命
            if (message instanceof TextMessage || message instanceof BinaryMessage) {
                st.lastInboundAt = System.currentTimeMillis();
            }
        }
        for (AprChannelPlugin p : enabledList()) {
            try {
                if (!p.onInbound(session, channel, message)) {
                    log.info("[aperture] 帧被插件拦截 sid={} plugin={}",
                            session.getId(), p.name());
                    return false;
                }
            } catch (Exception ex) {
                // 插件异常不得穿透 —— 记录后继续下一个插件
                log.warn("[aperture] 插件 {} 处理入站帧异常 sid={} : {}",
                        p.name(), session.getId(), ex.getMessage());
            }
        }
        return true;
    }

    // ============ SmartLifecycle ============

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        long period = tickPeriodMs();
        worker = new Thread(this::loop, "apr-plugin-scheduler");
        worker.setDaemon(true);
        worker.start();
        log.info("[aperture] 插件调度启动: period={}ms 启用插件={}", period, enabledNames());
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        long period = tickPeriodMs();
        while (running) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            sweep();
        }
    }

    /// 调度周期 —— 服务端心跳开启时按其间隔,关闭时退化为 10s
    public long tickPeriodMs() {
        long period = 10_000L;
        if (props.getServerHeartbeat().isEnabled() && props.getServerHeartbeat().getIntervalMs() > 0) {
            period = props.getServerHeartbeat().getIntervalMs();
        }
        return Math.max(1000L, period);
    }

    // ============ 空闲扫描 ============

    /// 扫描一轮:对每个会话算空闲时长并回调 onIdle
    public void sweep() {
        tickCount.incrementAndGet();
        long now = System.currentTimeMillis();
        for (SessionState st : sessions.values()) {
            WebSocketSession s = st.session;
            if (!s.isOpen()) {
                sessions.remove(s.getId());
                continue;
            }
            long idle = now - st.lastInboundAt;
            for (AprChannelPlugin p : enabledList()) {
                try {
                    p.onIdle(s, st.channel, idle);
                } catch (Exception ex) {
                    log.warn("[aperture] 插件 {} 空闲回调异常 sid={} : {}",
                            p.name(), s.getId(), ex.getMessage());
                }
            }
            if (!s.isOpen()) {
                sessions.remove(s.getId());
            }
        }
    }

    // ============ 内部 ============

    private List<AprChannelPlugin> enabledList() {
        return plugins.stream()
                .filter(AprChannelPlugin::enabled)
                .toList();
    }

    private void forEachEnabled(java.util.function.Consumer<AprChannelPlugin> action) {
        for (AprChannelPlugin p : enabledList()) {
            try {
                action.accept(p);
            } catch (Exception ex) {
                log.warn("[aperture] 插件 {} 回调异常 : {}", p.name(), ex.getMessage());
            }
        }
    }

    /// 当前启用的插件名(逗号分隔),用于日志
    public String enabledNames() {
        return enabledList().stream().map(AprChannelPlugin::name)
                .reduce((a, b) -> a + "," + b).orElse("(none)");
    }

    /// 已扫描轮次
    public long tickCount() {
        return tickCount.get();
    }

    /// 当前登记的会话数
    public int sessionCount() {
        return sessions.size();
    }
}
