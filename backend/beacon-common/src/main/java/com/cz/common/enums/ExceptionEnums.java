package com.cz.common.enums;

import lombok.Getter;

/**
 * 业务错误码枚举。
 *
 * <p>平台对外返回的业务码集中定义于此。约定：<b>错误码一律为负数，且全表唯一</b> ——
 * 接入方依赖错误码区分失败原因，码值重复会导致提示张冠李戴。全表唯一性由单元测试守护。</p>
 *
 * <p>码段划分：{@code -1 ~ -99} 面向外部客户的业务校验失败；
 * {@code -100 ~ -199} 平台内部与后台错误；{@code -201 及以下} 缓存同步错误。</p>
 *
 * <p>常量随场景逐个添加：只登记当前已实现链路真正会抛出的错误码。</p>
 *
 * @author cz
 */
@Getter
public enum ExceptionEnums {

    /** 非法的 apikey。 */
    ERROR_APIKEY(-1, "非法的apikey"),

    /** 请求来源 IP 不在客户白名单内。 */
    IP_NOT_WHITE(-2, "请求的ip不在白名单内"),

    /** 无可用签名。 */
    ERROR_SIGN(-3, "无可用签名"),

    /** 手机号格式不正确。 */
    ERROR_MOBILE(-5, "手机号格式不正确"),

    /** 请求参数不合法。 */
    PARAMETER_ERROR(-10, "参数不合法"),

    /** 没有可用通道。 */
    NO_CHANNEL(-18, "没有可用通道"),

    /** 未归类的未知错误。 */
    UNKNOWN_ERROR(-100, "未知错误"),

    /** 内部调用令牌无效。 */
    INTERNAL_TOKEN_INVALID(-106, "内部调用令牌无效");

    /** 错误码，负数。 */
    private final Integer code;

    /** 错误提示文案。 */
    private final String msg;

    ExceptionEnums(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
