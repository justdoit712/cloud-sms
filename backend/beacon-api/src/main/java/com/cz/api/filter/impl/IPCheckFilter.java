package com.cz.api.filter.impl;

import com.cz.api.filter.CheckFilter;
import com.cz.common.enums.ExceptionEnums;
import com.cz.common.exception.ApiException;
import com.cz.common.model.StandardSubmit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 校验请求来源 IP 是否在客户白名单内。
 *
 * <p><b>白名单为空时拒绝请求</b>：空名单意味着"尚未配置允许的来源"，
 * 此时放行等于对任意来源开放。若确需不限制来源，应显式配置而不是依赖留空。
 * 白名单内容由 apikey 校验环节载入。</p>
 *
 * @author cz
 */
@Slf4j
@Service("ip")
public class IPCheckFilter implements CheckFilter {

    @Override
    public void check(StandardSubmit submit) {
        List<String> whiteList = submit.getIp();
        String realIp = submit.getRealIp();

        if (whiteList == null || whiteList.isEmpty()) {
            log.warn("受理校验-ip 不通过: 客户未配置 IP 白名单, clientId={}", submit.getClientId());
            throw new ApiException(ExceptionEnums.IP_NOT_WHITE);
        }

        if (!whiteList.contains(realIp)) {
            log.info("受理校验-ip 不通过: 来源不在白名单内, clientId={}", submit.getClientId());
            throw new ApiException(ExceptionEnums.IP_NOT_WHITE);
        }

        log.info("受理校验-ip 通过, clientId={}", submit.getClientId());
    }
}
