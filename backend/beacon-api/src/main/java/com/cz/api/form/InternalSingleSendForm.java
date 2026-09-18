package com.cz.api.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 内部单条短信发送请求。
 *
 * <p>由平台内部服务（如运营后台代发）调用，不经过对外校验链，
 * 但需携带客户 apikey 以确定归属客户。</p>
 *
 * @param apikey 客户 apikey
 * @param mobile 目标手机号
 * @param text   短信内容
 * @param uid    调用方业务侧请求标识，可为空
 * @param state  短信类型：0 验证码 / 1 通知 / 2 营销
 * @author cz
 */
public record InternalSingleSendForm(

        @JsonProperty("apikey")
        @NotBlank(message = "apikey不能为空")
        String apikey,

        @JsonProperty("mobile")
        @NotBlank(message = "手机号不能为空")
        String mobile,

        @JsonProperty("text")
        @NotBlank(message = "短信内容不能为空")
        String text,

        @JsonProperty("uid")
        String uid,

        @JsonProperty("state")
        @NotNull(message = "短信类型不能为空")
        Integer state) {
}
