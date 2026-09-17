package com.cz.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * beacon-api 启动类。
 *
 * <p>接入层：受理外部客户与内部代发的短信提交请求，执行配置驱动的校验链后投递预发送消息。</p>
 *
 * @author cz
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class ApiStarterApp {

    public static void main(String[] args) {
        SpringApplication.run(ApiStarterApp.class, args);
    }
}
