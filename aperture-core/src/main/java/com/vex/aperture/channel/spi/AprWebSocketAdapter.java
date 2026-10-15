package com.vex.aperture.channel.spi;

import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.Channel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 一层适配 —— 把 Spring 协议的 {@link WebSocketHandler} 回调转交给具体的
 * {@link AbstractChannelHandler} 实例。这样 {@code AbstractChannelHandler} 不再
 * {@code implements WebSocketHandler},职责更内聚。
 *
 * <p>委托对象本身可以是任何 Spring bean,但通常是同一 controller 类本身 —— 业务聚合类直接
 * 继承 {@link AbstractChannelHandler} 时,本适配类**复用同一个 bean 实例**(不是再 wrap)。
 *
 * @since Aperture 0.3
 */
public final class AprWebSocketAdapter<C extends Channel> implements WebSocketHandler {

    private final AbstractChannelHandler<C> delegate;

    public AprWebSocketAdapter(AbstractChannelHandler<C> delegate) {
        this.delegate = delegate;
    }

    public AbstractChannelHandler<C> delegate() {
        return delegate;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        delegate.afterEstablished(session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        delegate.handleFrame(session, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        delegate.afterClosed(session, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        delegate.handleTransportError(session, error);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
