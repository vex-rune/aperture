package com.vex.aperture.example;

import com.vex.aperture.channel.Channel;
import com.vex.aperture.channel.Response;
import com.vex.aperture.channel.plugin.AprChannelPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/// 这不是业务插件 —— 它只做一件事:证明 README「自定义插件」小节里的示例代码能原样编译。
/// README 改了这里的代码也要跟着改,两者必须一致。
@Component
@Order(5)
class ReadmeSampleCompileTest implements AprChannelPlugin {

    /// 会话属性键 —— "ok" 表示已放行
    private static final String ATTR_AUTH = "apr.auth";

    private final boolean enabled;

    ReadmeSampleCompileTest(@Value("${aperture.channel.auth.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "token-auth";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
        if (!enabled() || "ok".equals(session.getAttributes().get(ATTR_AUTH))) {
            return true;
        }
        if (isAuthFrame(message) && verifyToken(readToken(message))) {
            session.getAttributes().put(ATTR_AUTH, "ok");
            return true;
        }
        if (channel instanceof Channel ch) {
            ch.sendObject("error", Response.error("unauthorized", "鉴权失败"));
        }
        closeQuietly(session);
        return false;
    }

    @Override
    public void onClose(WebSocketSession session, Object channel) {
    }

    /// 会话可能已被对端关闭,关不掉就算了
    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception ignored) {
        }
    }

    /// 首行是否为 auth.token
    private static boolean isAuthFrame(WebSocketMessage<?> message) {
        return message instanceof TextMessage t && type(t.getPayload()).equals("auth.token");
    }

    private static String readToken(WebSocketMessage<?> message) {
        int nl = ((TextMessage) message).getPayload().indexOf('\n');
        return nl < 0 ? "" : ((TextMessage) message).getPayload().substring(nl + 1);
    }

    private static String type(String payload) {
        int nl = payload.indexOf('\n');
        return (nl < 0) ? payload : payload.substring(0, nl);
    }

    private boolean verifyToken(String rawJson) {
        return false;
    }
}