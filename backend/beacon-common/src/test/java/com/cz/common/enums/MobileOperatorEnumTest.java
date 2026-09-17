package com.cz.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobileOperatorEnum} 单元测试。
 *
 * @author cz
 */
class MobileOperatorEnumTest {

    @Test
    @DisplayName("按名称反查应命中正确枚举")
    void shouldFindEnumByName() {
        assertEquals(Optional.of(MobileOperatorEnum.CHINA_MOBILE), MobileOperatorEnum.ofName("移动"));
        assertEquals(Optional.of(MobileOperatorEnum.CHINA_UNICOM), MobileOperatorEnum.ofName("联通"));
        assertEquals(Optional.of(MobileOperatorEnum.CHINA_TELECOM), MobileOperatorEnum.ofName("电信"));
    }

    @Test
    @DisplayName("未知名称反查应返回空 Optional，不得抛异常")
    void shouldReturnEmptyForUnknownName() {
        assertTrue(MobileOperatorEnum.ofName("广电").isEmpty());
        assertTrue(MobileOperatorEnum.ofName("").isEmpty());
    }

    @Test
    @DisplayName("名称为 null 时反查应返回空 Optional，不得抛异常")
    void shouldNotThrowOnNullName() {
        assertTrue(MobileOperatorEnum.ofName(null).isEmpty());
    }

    @Test
    @DisplayName("按名称反查编号应命中正确值")
    void shouldFindOperatorIdByName() {
        assertEquals(1, MobileOperatorEnum.operatorIdByName("移动"));
        assertEquals(2, MobileOperatorEnum.operatorIdByName("联通"));
        assertEquals(3, MobileOperatorEnum.operatorIdByName("电信"));
    }

    @Test
    @DisplayName("反查编号失败应兜底为 UNKNOWN 的编号，而非 null")
    void shouldFallbackToUnknownId() {
        assertEquals(MobileOperatorEnum.UNKNOWN.getOperatorId(), MobileOperatorEnum.operatorIdByName("广电"));
        assertEquals(MobileOperatorEnum.UNKNOWN.getOperatorId(), MobileOperatorEnum.operatorIdByName(null));
        assertNotNull(MobileOperatorEnum.operatorIdByName("不存在的运营商"));
    }

    @Test
    @DisplayName("运营商编号不得重复")
    void operatorIdsShouldBeUnique() {
        Set<Integer> ids = Arrays.stream(MobileOperatorEnum.values())
                .map(MobileOperatorEnum::getOperatorId)
                .collect(Collectors.toSet());
        assertEquals(MobileOperatorEnum.values().length, ids.size());
    }

    @Test
    @DisplayName("运营商名称不得重复，否则反查表构建会失败")
    void operatorNamesShouldBeUnique() {
        Set<String> names = Arrays.stream(MobileOperatorEnum.values())
                .map(MobileOperatorEnum::getOperatorName)
                .collect(Collectors.toSet());
        assertEquals(MobileOperatorEnum.values().length, names.size());
    }

    @Test
    @DisplayName("每个枚举的编号与名称都不得为空")
    void fieldsShouldNotBeNull() {
        for (MobileOperatorEnum operator : MobileOperatorEnum.values()) {
            assertNotNull(operator.getOperatorId(), operator.name() + " 的编号为空");
            assertNotNull(operator.getOperatorName(), operator.name() + " 的名称为空");
        }
    }

    @Test
    @DisplayName("UNKNOWN 的编号应为 0，作为反查兜底值")
    void unknownIdShouldBeZero() {
        assertEquals(0, MobileOperatorEnum.UNKNOWN.getOperatorId());
    }
}
