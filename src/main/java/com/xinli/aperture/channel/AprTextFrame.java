package com.xinli.aperture.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Aperture 框架 — 声明处理 WebSocket 文本帧的路由;按 type 字段路由。
///
/// <p>前缀 {@code Apr} = Aperture 缩写,避免与未来通用 {@code @Ws*} 注解命名冲突,
/// 同一类可同时存在多种前缀的路由声明(虽然不推荐)。
///
/// <p>用法:
/// <ul>
///   <li><b>类级</b>(1 类 1 type,旧风格):handler 类上加 {@code @AprTextFrame("audio.input.end")},基类把
///   整个类的 {@code handle(...)} 路由给该 type;</li>
///   <li><b>方法级</b>(1 类多 type,推荐):handler 类上无注解,业务方法上加
///   {@code @AprTextFrame("audio.opening")},基类反射逐方法建路由。类比 Spring 的
///   {@link org.springframework.web.bind.annotation.GetMapping} 标在 controller 方法上。</li>
/// </ul>
///
/// <p>两种风格不可混用:同一个类要么走类级(整类 1 type),要么走方法级(逐方法 type)。
///
/// @since Aperture 0.1
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AprTextFrame {
    /// 报文 type,对应 {@code JSON {type: "..."}} 里的 type 字段
    String value();
}
