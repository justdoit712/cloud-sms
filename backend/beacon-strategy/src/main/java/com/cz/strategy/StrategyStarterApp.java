package com.cz.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * beacon-strategy 启动类。
 *
 * <p>策略层：消费预发送消息，按客户配置的策略链依次执行黑名单、敏感词、限流、扣费、
 * 号段补齐、携号转网与路由，最终把短信投递到目标通道的网关队列。</p>
 *
 * @author cz
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class StrategyStarterApp {

    public static void main(String[] args) {
        SpringApplication.run(StrategyStarterApp.class, args);
    }
}
