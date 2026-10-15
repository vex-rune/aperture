package com.xinli.aperture.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明方法级 {@link AprTextFrame} / {@link AprByteFrame} 路由方法的"载荷形参" —
 * 形参必须是 <b>非 String 的 record / POJO</b>(带 JSR-303 注解的请求 DTO)。
 *
 * <p>基类反射前:
 * <ol>
 *   <li>将 JSON 载荷反序列化到该 record;</li>
 *   <li>跑 Bean Validation({@code @NotNull} / {@code @Min} / ...);</li>
 *   <li>失败回 {@code error{4400, ...}};<strong>不</strong>关闭连接;</li>
 *   <li>成功调用方法,record 直接作为形参传入。</li>
 * </ol>
 *
 * <p>与"原始 {@code String payloadJson}"二选一 — 同方法不能同时声明。
 *
 * <p>范式见 spec §4.2。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AprPayload {
}
