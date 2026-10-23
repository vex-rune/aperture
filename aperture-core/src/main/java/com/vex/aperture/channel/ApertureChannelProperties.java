package com.vex.aperture.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通道配置 —— 前缀 {@code aperture.channel}。
 *
 * <p>每类横切能力都是一个独立开关,互不影响:
 * <pre>{@code
 * aperture:
 *   channel:
 *     frame-limit:
 *       enabled: true          # 帧大小限制
 *       max-text-bytes: 65536
 *       max-binary-bytes: 1048576
 *     server-heartbeat:         # 服务端主动 Ping
 *       enabled: true
 *       interval-ms: 30000
 *       timeout-ms: 90000
 *     client-heartbeat:         # 客户端主动 heartbeat.ping
 *       enabled: true
 *       timeout-ms: 120000      # 多久没收到客户端心跳就判定异常
 * }</pre>
 *
 * <p>客户端心跳与服务端心跳<b>相互独立</b>:客户端不发,服务端照发 Ping、照判超时;
 * 服务端心跳关掉,客户端心跳仍可维持会话。
 *
 * @since Aperture 0.5
 */
@Component
@ConfigurationProperties(prefix = "aperture.channel")
public class ApertureChannelProperties {

    private final FrameLimit frameLimit = new FrameLimit();
    private final ServerHeartbeat serverHeartbeat = new ServerHeartbeat();
    private final ClientHeartbeat clientHeartbeat = new ClientHeartbeat();

    public FrameLimit getFrameLimit() {
        return frameLimit;
    }

    public ServerHeartbeat getServerHeartbeat() {
        return serverHeartbeat;
    }

    public ClientHeartbeat getClientHeartbeat() {
        return clientHeartbeat;
    }

    /// 帧大小限制插件
    public static class FrameLimit {

        /**
         * 是否启用帧大小限制
         */
        private boolean enabled = true;

        /**
         * 单个文本帧最大字节数(含首行路由键);0 = 不限制
         */
        private int maxTextBytes = 64 * 1024;

        /**
         * 单个二进制帧最大字节数(含首字节 frameType);0 = 不限制
         */
        private int maxBinaryBytes = 1024 * 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxTextBytes() {
            return maxTextBytes;
        }

        public void setMaxTextBytes(int maxTextBytes) {
            this.maxTextBytes = maxTextBytes;
        }

        public int getMaxBinaryBytes() {
            return maxBinaryBytes;
        }

        public void setMaxBinaryBytes(int maxBinaryBytes) {
            this.maxBinaryBytes = maxBinaryBytes;
        }
    }

    /// 服务端主动心跳插件 —— 服务端发 WebSocket Ping,空闲超时即关闭
    public static class ServerHeartbeat {

        /**
         * 是否启用服务端心跳
         */
        private boolean enabled = true;

        /**
         * 空闲达到该值时服务端下发 Ping;0 = 每轮都发
         */
        private long intervalMs = 30_000L;

        /**
         * 空闲超过该值(未收到任何入站帧)则关闭连接
         */
        private long timeoutMs = 90_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /// 客户端主动心跳插件 —— 客户端发 {@code heartbeat.ping},服务端回 {@code heartbeat.pong}
    public static class ClientHeartbeat {

        /**
         * 是否启用客户端心跳(须客户端配合发 {@code heartbeat.ping})
         */
        private boolean enabled = false;

        /**
         * 多久没收到 {@code heartbeat.ping} 就关闭连接;0 = 只记录不断连
         */
        private long timeoutMs = 120_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}