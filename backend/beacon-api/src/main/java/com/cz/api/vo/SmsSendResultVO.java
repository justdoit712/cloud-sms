package com.cz.api.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 短信发送接口响应。
 *
 * <p>字段与既有接入契约一致：对外仅返回业务码、提示、客户业务标识与平台流水号。
 * 刻意不含计费与条数等统计字段 —— 受理阶段无法给出这些值，返回空值只会给调用方造成困惑。</p>
 *
 * @param code 业务码，0 表示受理成功
 * @param msg  提示信息
 * @param uid  客户业务侧请求标识
 * @param sid  平台生成的短信流水号
 * @author cz
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmsSendResultVO(

        @JsonProperty("code")
        Integer code,

        @JsonProperty("msg")
        String msg,

        @JsonProperty("uid")
        String uid,

        @JsonProperty("sid")
        String sid) {

    /**
     * 受理成功。
     *
     * @param uid 客户业务侧请求标识
     * @param sid 平台生成的短信流水号
     * @return 成功响应
     */
    public static SmsSendResultVO ok(String uid, String sid) {
        return new SmsSendResultVO(0, "接收成功", uid, sid);
    }

    /**
     * 受理失败。
     *
     * @param code 业务错误码
     * @param msg  失败提示
     * @return 失败响应
     */
    public static SmsSendResultVO fail(Integer code, String msg) {
        return new SmsSendResultVO(code, msg, null, null);
    }
}
