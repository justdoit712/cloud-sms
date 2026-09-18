package com.cz.api.filter.impl;

import com.cz.api.client.ClientConfigProvider;
import com.cz.api.filter.CheckFilter;
import com.cz.common.constant.ApiConstant;
import com.cz.common.enums.ExceptionEnums;
import com.cz.common.exception.ApiException;
import com.cz.common.model.StandardSubmit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 校验短信签名。
 *
 * <p>约定短信内容以签名标记开头，本环节从中解析签名文本并核对客户是否启用。
 * 核对通过后把签名原文与签名标识写回提交对象，供后续链路使用。</p>
 *
 * @author cz
 */
@Slf4j
@Service("sign")
public class SignCheckFilter implements CheckFilter {

    /** 签名文本在标记内的起始下标。 */
    private static final int SIGN_START_INDEX = 1;

    private final ClientConfigProvider clientConfigProvider;

    public SignCheckFilter(ClientConfigProvider clientConfigProvider) {
        this.clientConfigProvider = clientConfigProvider;
    }

    @Override
    public void check(StandardSubmit submit) {
        String text = submit.getText();
        if (text == null || !text.startsWith(ApiConstant.SIGN_PREFIX)) {
            log.info("受理校验-签名 不通过: 内容未以签名标记开头, clientId={}", submit.getClientId());
            throw new ApiException(ExceptionEnums.ERROR_SIGN);
        }

        int endIndex = text.indexOf(ApiConstant.SIGN_SUFFIX);
        if (endIndex <= SIGN_START_INDEX) {
            log.info("受理校验-签名 不通过: 签名标记不完整, clientId={}", submit.getClientId());
            throw new ApiException(ExceptionEnums.ERROR_SIGN);
        }

        String sign = text.substring(SIGN_START_INDEX, endIndex);
        if (!clientConfigProvider.matchSign(submit.getClientId(), sign, submit)) {
            log.info("受理校验-签名 不通过: 客户未启用该签名, clientId={}", submit.getClientId());
            throw new ApiException(ExceptionEnums.ERROR_SIGN);
        }

        log.info("受理校验-签名 通过, clientId={}, signId={}", submit.getClientId(), submit.getSignId());
    }
}
