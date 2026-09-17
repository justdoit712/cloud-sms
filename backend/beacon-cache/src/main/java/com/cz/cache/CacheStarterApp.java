package com.cz.cache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * beacon-cache 启动类。
 *
 * <p>本服务是全平台<b>唯一</b>直连 Redis 的节点，对外提供带命名空间与 HMAC 签名鉴权的缓存 HTTP 能力；
 * 其余业务服务一律经本服务接口访问缓存，不直连 Redis。</p>
 *
 * @author cz
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CacheStarterApp {

    public static void main(String[] args) {
        SpringApplication.run(CacheStarterApp.class, args);
    }
}
