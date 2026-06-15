package com.collection.engine.spi;

/**
 * SPI 实现抛出了非运行时异常（受检异常）时的包装。
 *
 * <p>SPI 方法签名不声明受检异常，正常不会触发；仅为 {@link SpiInvoker} 在跨线程执行时
 * 兜底极端情况，避免吞掉根因。运行时异常会被原样上抛，不经此类。
 */
public class SpiInvocationException extends RuntimeException {

    public SpiInvocationException(String spiName, Throwable cause) {
        super("SPI [" + spiName + "] invocation failed", cause);
    }
}
