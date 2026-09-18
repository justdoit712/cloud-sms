package com.cz.api.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求来源 IP 解析工具。
 *
 * <p>请求经过反向代理时，真实来源地址在转发头中；但转发头可被客户端伪造，
 * 因此只有在<b>直连方本身就是可信代理</b>时才采信转发头，
 * 否则一律使用连接的对端地址。</p>
 *
 * <p>采信转发头时从右向左取第一个非可信代理的地址 —— 右侧是靠近服务端、
 * 由可信代理追加的部分，左侧可被客户端任意伪造。</p>
 *
 * @author cz
 */
@Slf4j
@Component
public class ClientIpResolver {

    /** 转发头名称。 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** 可信代理地址清单，为空表示不采信任何转发头。 */
    private final List<String> trustedProxies;

    public ClientIpResolver(@Value("${api.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = parse(trustedProxies);
    }

    /**
     * 解析请求的真实来源 IP。
     *
     * @param request 当前请求
     * @return 来源 IP；无法解析时返回空串
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr == null ? "" : remoteAddr;
        }

        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr == null ? "" : remoteAddr;
        }

        List<String> chain = parse(forwarded);
        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i);
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return chain.isEmpty() ? "" : chain.get(0);
    }

    /** 判断某地址是否为可信代理。 */
    private boolean isTrustedProxy(String address) {
        return address != null && trustedProxies.contains(address);
    }

    /** 把逗号分隔的地址串拆成列表，忽略空白项。 */
    private List<String> parse(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
