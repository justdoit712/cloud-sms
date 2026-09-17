package com.cz.common.exception;

import com.cz.common.enums.ExceptionEnums;

/**
 * 接入层业务异常。
 *
 * <p>由短信受理入口及其校验链抛出，表示请求在进入主链路之前就被判定为不可受理。</p>
 *
 * @author cz
 */
public class ApiException extends BizException {

    public ApiException(String message, Integer code) {
        super(message, code);
    }

    public ApiException(ExceptionEnums enums) {
        super(enums);
    }
}
