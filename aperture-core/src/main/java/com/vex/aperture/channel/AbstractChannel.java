package com.vex.aperture.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Channel} 抽象基类;为各通道专用实现提供"会话属性 / 序号 / JSON 下行"等通用机制。
 * <p>
 * 抽象类 <b>不</b>代表"通用 Channel" —— 它只是把协议骨架(JSON 信封组装、同步写会话、序号自增) 抽到一处,避免每条通道重复实现。各业务下行(ASR / Agent / Device)由各包内的专用
 * Channel 子类自己提供。
 */
@Slf4j
public abstract class AbstractChannel implements Channel {

    /**
     * 会话属性键 — 下行序号计数器
     */
    private static final String ATTR_DOWN_SEQ = "downSeq";

    private final WebSocketSession session;
    private final JsonMapper jsonMapper;

    /**
     * 构造通道基类
     *
     * @param session    会话
     * @param jsonMapper JSON 序列化器
     */
    protected AbstractChannel(WebSocketSession session, JsonMapper jsonMapper) {
        this.session = session;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String id() {
        return session.getId();
    }

    @Override
    public Object attr(String key) {
        return session.getAttributes().get(key);
    }

    @Override
    public void setAttr(String key, Object value) {
        session.getAttributes().put(key, value);
    }

    @Override
    public boolean open() {
        return session.isOpen();
    }

    @Override
    public void close(CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ex) {
            log.warn("[{}] 关闭会话失败 sid={} : {}", getClass().getSimpleName(), id(), ex.getMessage());
        }
    }

    @Override
    public void sendReady() {
        java.util.List<String> serverCaps = new java.util.ArrayList<>();
        serverCaps.add("binary_header_v1");
        send("session.ready", Channel.payload(
                "serverCaps", serverCaps,
                "heartbeat", Channel.payload("intervalMs", 15000, "timeoutMs", 45000),
                "limits", Channel.payload("maxFrameBytes", 4096)));
    }

    @Override
    public void sendReject(int code, String message) {
        send("session.reject", Channel.payload("code", code, "message", message));
    }

    @Override
    public void sendError(int code, String message, boolean fatal) {
        send("error", Channel.payload("code", code, "message", message, "fatal", fatal));
    }

    @Override
    public void send(String type, Map<String, Object> payload) {
        try {
            synchronized (session) {
                if (!session.isOpen()) {
                    return;
                }
                // seq 在锁内自增 —— 保证"序号顺序 == 发送顺序"
                String json = jsonMapper.writeValueAsString(
                        new AprFrame(new AprFrameHeader(type, session.getId(), nextSeq()), payload));
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ex) {
            log.warn("[{}] 下发失败 sid={} type={} : {}", getClass().getSimpleName(), session.getId(), type, ex.getMessage());
        }
    }

    /**
     * 取下行序号并自增 —— <b>必须在 {@code synchronized (session)} 内调用</b>,
     * 保证"序号顺序 == 发送顺序"。
     *
     * @return 序号
     */
    private long nextSeq() {
        Object counter = session.getAttributes().computeIfAbsent(ATTR_DOWN_SEQ, key -> new AtomicLong());
        return ((AtomicLong) counter).incrementAndGet();
    }
}
