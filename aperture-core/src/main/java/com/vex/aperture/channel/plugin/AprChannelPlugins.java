package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 框架内置插件的 bean 装配。
 *
 * <p>每个插件都是独立 bean,因此各自可用 {@code aperture.channel.*} 单独开关 ——
 * 关掉某一个只会让它自己不被调度,其余插件与业务路由完全不受影响。
 *
 * <p>业务方实现 {@link AprChannelPlugin} 并声明为 bean 即自动纳入同一条插件链,
 * 无需在此登记。
 *
 * @since Aperture 0.5
 */
@Configuration(proxyBeanMethods = false)
public class AprChannelPlugins {

    /// 帧大小限制 —— 优先级最高,须在业务解析前完成拦截
    @Bean
    @Order(10)
    public AprFrameLimitPlugin aprFrameLimitPlugin(ApertureChannelProperties props) {
        return new AprFrameLimitPlugin(props);
    }

    /// 服务端主动心跳
    @Bean
    @Order(20)
    public AprServerHeartbeatPlugin aprServerHeartbeatPlugin(ApertureChannelProperties props) {
        return new AprServerHeartbeatPlugin(props);
    }

    /// 客户端主动心跳(默认关闭 —— 需客户端配合发 heartbeat.ping)
    @Bean
    @Order(30)
    public AprClientHeartbeatPlugin aprClientHeartbeatPlugin(ApertureChannelProperties props) {
        return new AprClientHeartbeatPlugin(props);
    }
}