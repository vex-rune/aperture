package com.vex.aperture.channel.plugin;

import org.springframework.web.socket.WebSocketSession;

/**
 * 通道插件 —— 建连 / 入站 / 空闲 / 断连四个时机的可插拔钩子。
 *
 * <p>框架自带三个插件(心跳、帧限制),各自可用配置<b>单独开关</b>;业务也可实现本接口
 * 注册自己的插件(鉴权、限流、审计……),只要声明为 Spring bean 即被自动纳入调度。
 *
 * <p>约定:
 * <ul>
 *   <li>所有钩子<b>永不抛异常穿透</b> —— 插件抛出的异常由调度器捕获并降级为日志,
 *       避免一个插件的 bug 拖垮整条连接;</li>
 *   <li>{@link #onInbound} 返回 {@code false} 表示"拦截该帧",调度器随即停止后续插件
 *       且不再进入业务路由(用于限流 / 校验这类需要拒绝的场景);</li>
 *   <li>插件只做<b>横切</b>关注点,不要在这里做业务编排 —— 那是 {@code @AprTextFrame} 的职责。</li>
 * </ul>
 *
 * <p>实现类须为 {@code @Component};建议无状态(框架不保证单例外的并发安全由你自理)。
 *
 * @since Aperture 0.5
 */
public interface AprChannelPlugin {

    /**
     * 插件名 —— 用于日志与配置开关查找,默认取实现类 simpleName
     *
     * @return 插件名
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 该插件当前是否启用
     *
     * @return true=启用
     */
    boolean enabled();

    /**
     * 建连 —— 握手通过、路由就绪之后
     *
     * @param session 会话
     *
     * @param channel 通道
     */
    default void onOpen(WebSocketSession session, Object channel) {
    }

    /**
     * 入站帧到达 —— 在框架自身的帧校验之后、业务路由之前
     *
     * @param session 会话
     * @param channel 通道
     * @param message 帧
     *
     * @return false=拦截该帧,不再进入业务路由
     */
    default boolean onInbound(WebSocketSession session, Object channel,
                              org.springframework.web.socket.WebSocketMessage<?> message) {
        return true;
    }

    /**
     * 空闲回调 —— 由调度器按各自间隔驱动
     *
     * @param session 会话
     * @param channel 通道
     * @param idleMs  距上次入站帧的毫秒数
     */
    default void onIdle(WebSocketSession session, Object channel, long idleMs) {
    }

    /**
     * 断连
     *
     * @param session 会话
     * @param channel 通道
     */
    default void onClose(WebSocketSession session, Object channel) {
    }
}