package com.cz.common.exception;

import com.cz.common.enums.ExceptionEnums;
import lombok.Getter;

/**
 * 业务异常基类。
 *
 * <p>用于承载"可预期的业务失败"（如 apikey 非法、无可用通道），
 * 与"技术故障"（如序列化失败、下游不可用）区分开。
 * 这个区分是消息消费分类处理的依据：业务异常意味着消息不必重投，技术故障则需要重投或告警。</p>
 *
 * <p>异常携带 {@code sequenceId} 作为链路上下文 —— 排障时"哪条短信出的问题"
 * 比调用堆栈更有定位价值。该字段通过链式的 {@link #withSequenceId(Long)} 后置注入，
 * 而非放进构造器：抛出点未必都持有消息对象，放进构造器会强迫每个 throw 处多传一个参数。</p>
 *
 * <p>不携带手机号等敏感信息，避免异常被打印时泄露客户数据。</p>
 *
 * @author cz
 */
@Getter
public abstract class BizException extends RuntimeException {

    /** 业务错误码，取值来自 {@link ExceptionEnums}  */
    private final Integer code;

    /** 链路上下文：出错的短信唯一标识，允许为空。 */
    private Long sequenceId;

    protected BizException(String message, Integer code) {
        super(message);
        this.code = code;
    }

    protected BizException(ExceptionEnums enums) {
        super(enums.getMsg());
        this.code = enums.getCode();
    }

    /**
     * 附加链路上下文（链式调用）。
     *
     * @param sequenceId 出错的短信唯一标识
     * @return 当前异常自身，便于链式书写
     */
    public BizException withSequenceId(Long sequenceId) {
        this.sequenceId = sequenceId;
        return this;
    }
}
