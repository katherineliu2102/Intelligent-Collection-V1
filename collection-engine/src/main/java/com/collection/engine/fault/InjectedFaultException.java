package com.collection.engine.fault;

/**
 * 注入的瞬态失败。
 *
 * <p>单独一个类型是为了让证据可分辨：DLQ 的 {@code failure_reason} 与日志里能一眼区分「演练注入的失败」 和「真实故障」，否则事后翻记录时两者混在一起，T5-R
 * 的结论就不可信。
 */
public class InjectedFaultException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InjectedFaultException(EngineFaultInjector.Position position, String eventId) {
        super("injected fault at " + position + " for eventId=" + eventId);
    }
}
