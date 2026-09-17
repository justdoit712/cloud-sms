package com.cz.smsgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * beacon-smsgateway 启动类。
 *
 * <p>网关层：消费通道队列，组装 CMPP 2.0 报文经 Netty 长连接下发给运营商 ISMG，
 * 并处理 SubmitResp 与 Deliver 两类应答。</p>
 *
 * <p><b>注意</b>：本模块是<b>运营商协议网关</b>，不是 HTTP 网关。</p>
 *
 * @author cz
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class SmsGatewayStarterApp {

    public static void main(String[] args) {
        SpringApplication.run(SmsGatewayStarterApp.class, args);
    }
}
