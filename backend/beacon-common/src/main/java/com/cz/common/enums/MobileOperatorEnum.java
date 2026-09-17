package com.cz.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 手机号运营商枚举。
 *
 * <p>短信链路中运营商以数字形式流转（消息体的 {@code operatorId} 字段），
 * 但配置与日志里用的是中文名称，故需要本枚举做双向映射。</p>
 *
 * <p>反查方法统一返回 {@link Optional} 或带兜底的默认值，
 * <b>绝不返回 null 也不抛异常</b> —— 号段库覆盖不全时反查失败是常态，
 * 不应让主链路因此中断。</p>
 *
 * @author cz
 */
@Getter
public enum MobileOperatorEnum {

    /** 移动。 */
    CHINA_MOBILE(1, "移动"),

    /** 联通。 */
    CHINA_UNICOM(2, "联通"),

    /** 电信。 */
    CHINA_TELECOM(3, "电信"),

    /** 未识别运营商，作为反查兜底值。 */
    UNKNOWN(0, "未知");

    /** 运营商编号，与消息体的 operatorId 字段一致。 */
    private final Integer operatorId;

    /** 运营商中文名称。 */
    private final String operatorName;

    /** 名称到枚举的反查表，构建后不可变。 */
    private static final Map<String, MobileOperatorEnum> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    MobileOperatorEnum::getOperatorName,
                    Function.identity()));

    MobileOperatorEnum(Integer operatorId, String operatorName) {
        this.operatorId = operatorId;
        this.operatorName = operatorName;
    }

    /**
     * 按运营商名称反查枚举。
     *
     * @param operatorName 运营商中文名称，可为 null
     * @return 匹配到的枚举；名称为 null 或无法识别时返回空 Optional
     */
    public static Optional<MobileOperatorEnum> ofName(String operatorName) {
        return Optional.ofNullable(operatorName).map(BY_NAME::get);
    }

    /**
     * 按运营商名称反查编号。
     *
     * <p>反查失败时返回 {@link #UNKNOWN} 的编号而非 null，
     * 避免调用方在号段库缺失时拿到空值。</p>
     *
     * @param operatorName 运营商中文名称，可为 null
     * @return 运营商编号，无法识别时为 {@link #UNKNOWN} 的编号
     */
    public static Integer operatorIdByName(String operatorName) {
        return ofName(operatorName)
                .map(MobileOperatorEnum::getOperatorId)
                .orElse(UNKNOWN.getOperatorId());
    }
}
