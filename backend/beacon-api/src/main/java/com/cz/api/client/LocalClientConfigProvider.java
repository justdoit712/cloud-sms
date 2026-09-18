package com.cz.api.client;

import com.cz.common.model.StandardSubmit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于本地配置的客户信息来源。
 *
 * <p>当前从配置文件读取一个测试客户的配置，用于在没有缓存服务的情况下让受理链路完整可运行。
 * 接入缓存后由缓存实现替代，调用方代码不变。</p>
 *
 * @author cz
 */
@Slf4j
@Component
public class LocalClientConfigProvider implements ClientConfigProvider {

    /** 测试客户标识。 */
    private final Long clientId;

    /** 测试客户的 IP 白名单，逗号分隔；IP 校验在名单为空时拒绝请求。 */
    private final String ipWhiteList;

    /** 测试客户的签名标识。 */
    private final Long signId;

    /** 测试客户已启用的签名，逗号分隔。 */
    private final String signs;

    public LocalClientConfigProvider(@Value("${local.test-client.client-id:1}") Long clientId,
                                     @Value("${local.test-client.ip-white-list:}") String ipWhiteList,
                                     @Value("${local.test-client.sign-id:1}") Long signId,
                                     @Value("${local.test-client.signs:}") String signs) {
        this.clientId = clientId;
        this.ipWhiteList = ipWhiteList;
        this.signId = signId;
        this.signs = signs;
    }

    @Override
    public boolean fillClientInfo(String apikey, StandardSubmit submit) {
        if (apikey == null || apikey.isBlank()) {
            return false;
        }
        submit.setClientId(clientId);
        submit.setIp(parseList(ipWhiteList));
        log.debug("载入本地测试客户配置, clientId={}", clientId);
        return true;
    }

    @Override
    public boolean matchSign(Long clientId, String sign, StandardSubmit submit) {
        if (sign == null || sign.isBlank()) {
            return false;
        }
        for (String candidate : parseList(signs)) {
            if (candidate.equals(sign)) {
                submit.setSign(sign);
                submit.setSignId(signId);
                return true;
            }
        }
        return false;
    }

    /** 把逗号分隔的配置项拆成列表，忽略空白项。 */
    private List<String> parseList(String value) {
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
