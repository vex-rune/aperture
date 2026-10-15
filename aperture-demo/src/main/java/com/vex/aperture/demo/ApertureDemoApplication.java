package com.vex.aperture.demo;

import com.vex.aperture.starter.ApertureProperties;
import com.vex.aperture.starter.configurer.ApertureConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Demo 启动入口 —— 装配一个 {@code ApertureConfigurer} Bean,
 * 把 yaml 里的 {@code aperture.*} 翻译成跨 controller 共享行为。
 *
 * <p>实际工程里,每条通道(ASR / Agent / Device ...)都 new 一个
 * {@link ApertureConfigurer#create(String)} 实例,各自一套 path-prefix / 拦截器 / CORS。
 *
 * @since Aperture 0.4
 */
@SpringBootApplication
@EnableConfigurationProperties(ApertureProperties.class)
public class ApertureDemoApplication {

    private static final Logger log = LoggerFactory.getLogger(ApertureDemoApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ApertureDemoApplication.class);
        app.run(args);
    }

    /**
     * 单通道示例:本 demo 只有一条通道 {@code demo};真实工程会拆多份,
     * 各自 {@code create("asr")} / {@code create("agent")} / {@code create("device")}。
     */
    @Bean
    public com.vex.aperture.autoconfigure.ApertureControllerConfigurer demoChannelConfigurer(ApertureProperties props) {
        log.info("装配 demo 通道 configurer:pathPrefix={} allowedOrigins={} trace.enabled={} cors.enabled={} idleTimeout={}",
                props.getPathPrefix(),
                props.getAllowedOrigins(),
                props.getInterceptors().getTrace().isEnabled(),
                props.getInterceptors().getCors().isEnabled(),
                props.getIdleTimeout());

        return ApertureConfigurer.create("demo")
                .pathPrefix(props.getPathPrefix())
                .useTrace(props)
                .useCors(props, "*")
                .idleTimeout(Duration.ofMinutes(5))
                .build();
    }
}