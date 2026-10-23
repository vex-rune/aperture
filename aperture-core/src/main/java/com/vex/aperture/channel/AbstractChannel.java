package com.vex.aperture.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * {@link Channel} 抽象基类;为各通道专用实现提供"会话属性 / 报文下发"等通用机制。
 * <p>
 * 抽象类 <b>不</b>代表"通用 Channel" —— 它只是把协议骨架(报文组装、同步写会话) 抽到一处,避免每条通道重复实现。各业务下行(ASR / Agent / Device)由各包内的专用
 * Channel 子类自己提供。
 */
@Slf4j
public abstract class AbstractChannel implements Channel {

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
    public void sendObject(String type, Object payload) {
        try {
            String json = (payload == null) ? "{}" : jsonMapper.writeValueAsString(payload);
            sendText(type, json);
        } catch (Exception ex) {
            log.warn("[{}] 下发失败 sid={} type={} : {}", getClass().getSimpleName(), session.getId(), type, ex.getMessage());
        }
    }

    @Override
    public void sendText(String type, String text) {
        if (type == null || type.isBlank() || type.indexOf('\n') >= 0) {
            log.warn("[{}] 报文类型非法,已丢弃 sid={} type={}", getClass().getSimpleName(), session.getId(), type);
            return;
        }
        // 分隔符固定为 \n:JSON 规范禁止字符串内出现裸换行,故不会与载荷内容冲突
        write(session, type + '\n' + (text == null ? "" : text), type);
    }

    @Override
    public void sendBinary(int frameType, byte[] payload) {
        if (frameType < 0 || frameType > 0xFF) {
            log.warn("[{}] frameType 越界(0~255),已丢弃 sid={} frameType={}",
                    getClass().getSimpleName(), session.getId(), frameType);
            return;
        }
        int len = (payload == null) ? 0 : payload.length;
        ByteBuffer buf = ByteBuffer.allocate(1 + len);
        buf.put((byte) frameType);
        if (len > 0) {
            buf.put(payload);
        }
        buf.flip();
        try {
            synchronized (session) {
                if (!session.isOpen()) {
                    return;
                }
                session.sendMessage(new BinaryMessage(buf));
            }
        } catch (Exception ex) {
            log.warn("[{}] 二进制下发失败 sid={} frameType={} : {}",
                    getClass().getSimpleName(), session.getId(), frameType, ex.getMessage());
        }
    }

    /// 同步写文本帧;会话已关闭则静默丢弃
    private void write(WebSocketSession s, String frame, String type) {
        try {
            synchronized (s) {
                if (!s.isOpen()) {
                    return;
                }
                s.sendMessage(new TextMessage(frame));
            }
        } catch (Exception ex) {
            log.warn("[{}] 下发失败 sid={} type={} : {}", getClass().getSimpleName(), s.getId(), type, ex.getMessage());
        }
    }
}
