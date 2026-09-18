package com.cz.api.advice;

import com.cz.api.vo.SmsSendResultVO;
import com.cz.common.enums.ExceptionEnums;
import com.cz.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 接入层统一异常处理。
 *
 * <p>对外契约：<b>HTTP 状态码保持 200，失败原因由响应体中的业务码表达</b>，
 * 以兼容既有接入方对状态码的处理方式。</p>
 *
 * @author cz
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 处理业务异常：直接使用异常携带的业务码与提示。
     *
     * @param ex 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BizException.class)
    public SmsSendResultVO handleBizException(BizException ex) {
        log.info("受理失败, code={}, msg={}, sequenceId={}", ex.getCode(), ex.getMessage(), ex.getSequenceId());
        return SmsSendResultVO.fail(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理请求参数校验失败：取首个字段错误信息作为提示。
     *
     * @param ex 参数校验异常
     * @return 失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public SmsSendResultVO handleValidationException(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? ExceptionEnums.PARAMETER_ERROR.getMsg() : fieldError.getDefaultMessage();
        log.info("受理失败: 参数校验不通过, message={}", message);
        return SmsSendResultVO.fail(ExceptionEnums.PARAMETER_ERROR.getCode(), message);
    }

    /**
     * 处理请求体无法解析。
     *
     * @param ex 解析异常
     * @return 失败响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public SmsSendResultVO handleUnreadableException(HttpMessageNotReadableException ex) {
        log.info("受理失败: 请求体无法解析");
        return SmsSendResultVO.fail(ExceptionEnums.PARAMETER_ERROR.getCode(), ExceptionEnums.PARAMETER_ERROR.getMsg());
    }

    /**
     * 兜底处理未预期异常：对外只返回通用错误，细节仅记录在服务端。
     *
     * @param ex 未预期异常
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    public SmsSendResultVO handleUnknownException(Exception ex) {
        log.error("受理失败: 未预期异常", ex);
        return SmsSendResultVO.fail(ExceptionEnums.UNKNOWN_ERROR.getCode(), ExceptionEnums.UNKNOWN_ERROR.getMsg());
    }
}
