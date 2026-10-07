package com.datasync.core.mapper;

/**
 * 值转换失败（不可安全转换）。
 *
 * <p>这是一个「坏行」信号：引擎捕获它后按 {@code errorPolicy} 决定跳过落 sync_error 还是整体失败，
 * 消息里必须能看出是哪个值、什么类型的问题。
 */
public class ValueConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValueConversionException(String message) {
        super(message);
    }

    public ValueConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
