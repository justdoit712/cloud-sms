package com.cz.common.exception;

import com.cz.common.enums.ExceptionEnums;

/**
 * 策略层业务异常。
 *
 * <p>由策略链的各过滤器抛出，表示短信被业务规则拒绝（如命中黑名单、无可用通道）。
 * 消息消费侧捕获本异常时应视为<b>确定性失败</b>：消息不必重投，
 * 而应转向旁路补偿（记录结果并通知客户）。</p>
 *
 * @author cz
 */
public class StrategyException extends BizException {

    public StrategyException(String message, Integer code) {
        super(message, code);
    }

    public StrategyException(ExceptionEnums enums) {
        super(enums);
    }
}
