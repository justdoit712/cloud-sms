package com.cz.api.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单条短信发送请求。
 *
 * <p>刻意设计为 {@code record}：这是"构造后不再修改"的请求载体，
 * 与链路中需要逐段填充的可变对象（如提交对象本身）不同。
 * 显式标注 {@code @JsonProperty} 是为了让参数名不依赖编译期的参数名保留设置。</p>
 *
 * @param apikey 客户 apikey
 * @param mobile 目标手机号
 * @param text   短信内容，需以签名标记开头
 * @param uid    客户业务侧请求标识，可为空
 * @param state  短信类型：0 验证码 / 1 通知 / 2 营销
 * @author cz
 */
public record SingleSendForm(

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
        @Min(value = 0, message = "短信类型只能是0-2")
        @Max(value = 2, message = "短信类型只能是0-2")
        Integer state) {
}
