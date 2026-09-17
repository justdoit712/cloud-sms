package com.cz.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 定长比较工具。
 *
 * <p>校验密钥、令牌、签名等敏感值时应使用定长比较：普通字符串比较在遇到首个不同字符时即返回，
 * 比较耗时随"前多少位相同"变化，攻击者可据此逐位试探出正确取值。</p>
 *
 * <p>JDK 提供的实现已考虑长度差异与提前返回问题，直接复用而不自行实现。</p>
 *
 * @author cz
 */
public final class SecureEqualsUtil {

    /** 工具类，禁止实例化。 */
    private SecureEqualsUtil() {
    }

    /**
     * 以定长方式比较两个字符串。
     *
     * @param expected 期望值，不可为空
     * @param actual   实际值，允许为空
     * @return 完全一致返回 true；任一为空或不一致返回 false
     */
    public static boolean equals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
