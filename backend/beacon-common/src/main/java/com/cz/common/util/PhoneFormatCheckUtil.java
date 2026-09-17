package com.cz.common.util;

import java.util.regex.Pattern;

/**
 * 手机号格式校验工具。
 *
 * <p>用于受理校验与策略过滤等环节，判断给定号码是否为可下发的国内手机号。</p>
 *
 * <p>号段由各运营商持续放号，本类中的正则包含具体号段清单，
 * 因此会随放号情况变化 —— 调整时需评估对存量客户的影响：
 * 校验过严会把正常号码判为非法，过松则放过本不存在的号段。</p>
 *
 * @author cz
 */
public final class PhoneFormatCheckUtil {

    /**
     * 国内手机号正则。
     *
     * <p>覆盖 13 / 14 / 15 / 16 / 17 / 18 / 19 开头的已放号段，共 11 位。</p>
     */
    private static final Pattern CHINA_MOBILE_PATTERN = Pattern.compile(
            "^(13[0-9]|14[01456879]|15[0-35-9]|16[2567]|17[0-8]|18[0-9]|19[0-35-9])\\d{8}$");

    /** 工具类，禁止实例化。 */
    private PhoneFormatCheckUtil() {
    }

    /**
     * 判断是否为合法的国内手机号。
     *
     * @param mobile 待校验号码，允许为 null
     * @return 合法返回 true；为 null、为空或格式不符返回 false
     */
    public static boolean isChinaMobile(String mobile) {
        return mobile != null && CHINA_MOBILE_PATTERN.matcher(mobile).matches();
    }
}
