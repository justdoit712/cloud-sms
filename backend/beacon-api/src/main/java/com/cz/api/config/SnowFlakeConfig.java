package com.cz.api.config;

import com.cz.common.util.SnowFlakeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流水号生成器配置。
 *
 * <p>把配置读取与生成算法分开：算法本身不依赖容器，可独立测试；
 * 这里只负责按配置创建实例。机器标识与服务标识由配置下发，多实例部署时必须各不相同，
 * 否则不同节点会生成重复流水号。</p>
 *
 * @author cz
 */
@Slf4j
@Configuration
public class SnowFlakeConfig {

    /**
     * 创建流水号生成器。
     *
     * @param machineId 机器标识
     * @param serviceId 服务标识
     * @return 生成器实例
     */
    @Bean
    public SnowFlakeUtil snowFlakeUtil(@Value("${snowflake.machineId:0}") long machineId,
                                       @Value("${snowflake.serviceId:0}") long serviceId) {
        log.info("初始化流水号生成器, machineId={}, serviceId={}", machineId, serviceId);
        return new SnowFlakeUtil(machineId, serviceId);
    }
}
