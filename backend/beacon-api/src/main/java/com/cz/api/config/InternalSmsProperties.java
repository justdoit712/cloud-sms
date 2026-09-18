package com.cz.api.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 内部接口调用配置。
 *
 * <p>内部接口仅允许平台内其它服务调用，靠调用令牌区分。令牌<b>必须配置</b>：
 * 留空意味着任何知道接口地址的一方都能调用，因此在启动期就拒绝空值，
 * 而不是等到运行期才发现校验被跳过。</p>
 *
 * @author cz
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "internal.sms")
public class InternalSmsProperties {

    /** 内部接口调用令牌。 */
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 启动期校验令牌已配置。
     *
     * @throws IllegalStateException 令牌为空时抛出
     */
    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "内部接口令牌未配置: internal.sms.token 必须为非空值，禁止留空放行");
        }
        log.info("内部接口令牌已配置");
    }
}
