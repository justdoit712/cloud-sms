package com.cz.api.filter.impl;

import com.cz.api.client.ClientConfigProvider;
import com.cz.api.filter.CheckFilter;
import com.cz.common.enums.ExceptionEnums;
import com.cz.common.exception.ApiException;
import com.cz.common.model.StandardSubmit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 校验客户 apikey，并载入客户基本信息。
 *
 * <p>本环节是校验链的第一个环节：它写入的客户标识是后续环节的输入，
 * 因此顺序上必须排在首位（由上下文在启动期强制检查）。</p>
 *
 * @author cz
 */
@Slf4j
@Service("apikey")
public class ApiKeyCheckFilter implements CheckFilter {

    private final ClientConfigProvider clientConfigProvider;

    public ApiKeyCheckFilter(ClientConfigProvider clientConfigProvider) {
        this.clientConfigProvider = clientConfigProvider;
    }

    @Override
    public void check(StandardSubmit submit) {
        String apikey = submit.getApiKey();
        if (!clientConfigProvider.fillClientInfo(apikey, submit)) {
            log.info("受理校验-apikey 不通过");
            throw new ApiException(ExceptionEnums.ERROR_APIKEY);
        }
        log.info("受理校验-apikey 通过, clientId={}", submit.getClientId());
    }
}
