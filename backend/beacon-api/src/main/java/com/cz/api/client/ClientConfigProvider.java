package com.cz.api.client;

import com.cz.common.model.StandardSubmit;

/**
 * 客户配置与签名配置的来源。
 *
 * <p>校验链需要"这个 apikey 对应哪个客户、他的 IP 白名单是什么、他有哪些签名"这类信息。
 * 这些信息在正式架构中来自缓存，当前先由本地实现提供，使校验链可以独立运行与验证；
 * 后续接入缓存时新增一个实现类即可，校验链本身无需改动。</p>
 *
 * @author cz
 */
public interface ClientConfigProvider {

    /**
     * 按 apikey 载入客户基本信息。
     *
     * @param apikey 客户 apikey
     * @param submit 待填充的提交对象，实现方负责写入客户标识与 IP 白名单等技术字段
     * @return 是否查到了该客户
     */
    boolean fillClientInfo(String apikey, StandardSubmit submit);

    /**
     * 按短信签名匹配客户已启用的签名。
     *
     * <p>匹配成功后应把签名原文与签名标识写入提交对象。</p>
     *
     * @param clientId 客户标识
     * @param sign     从短信内容中解析出的签名文本
     * @param submit   待填充的提交对象
     * @return 是否匹配到可用签名
     */
    boolean matchSign(Long clientId, String sign, StandardSubmit submit);
}
