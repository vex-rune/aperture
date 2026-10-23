package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import com.vex.aperture.channel.Channel;
import com.vex.aperture.channel.Response;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 帧大小限制插件 —— 超限即拒,先判大小再进业务链。
 *
 * <p>顺序很关键:超限帧**不会**进入 Jackson 反序列化,因此一个超大载荷不会先吃掉内存
 * 才被拒绝。
 *
 * <p>超限时回 {@code error{frame_too_large}} 并以 {@code 1009}(消息体过大,RFC 6455)关闭连接。
 *
 * @since Aperture 0.5
 */
public class AprFrameLimitPlugin implements AprChannelPlugin {

    /// 超限关闭码 —— 1009;Spring 未预置,手工构造
    public static final CloseStatus TOO_BIG = new CloseStatus(1009, "frame too large");

    private final ApertureChannelProperties props;

    public AprFrameLimitPlugin(ApertureChannelProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "frame-limit";
    }

    @Override
    public boolean enabled() {
        return props.getFrameLimit().isEnabled();
    }

    @Override
    public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
        if (!enabled()) {
            return true;
        }
        int limit;
        int actual;
        String kind;
        if (message instanceof TextMessage text) {
            kind = "文本帧";
            actual = text.getPayload().length();
            limit = props.getFrameLimit().getMaxTextBytes();
        } else if (message instanceof BinaryMessage binary) {
            kind = "二进制帧";
            actual = binary.getPayload().remaining();
            limit = props.getFrameLimit().getMaxBinaryBytes();
        } else {
            return true;
        }
        if (limit <= 0 || actual <= limit) {
            return true;
        }
        reject(channel, session, kind, actual, limit);
        return false;
    }

    private void reject(Object channel, WebSocketSession session, String kind, int actual, int limit) {
        try {
            if (channel instanceof Channel ch) {
                ch.sendObject("error", Response.error(
                        Response.FRAME_TOO_LARGE, kind + "超限: " + actual + " > " + limit));
            }
            if (session.isOpen()) {
                session.close(TOO_BIG);
            }
        } catch (Exception ignored) {
            // 连接可能已断,不影响拦截结果
        }
    }
}