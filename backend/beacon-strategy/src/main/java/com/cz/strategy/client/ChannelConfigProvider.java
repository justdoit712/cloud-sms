package com.cz.strategy.client;

import com.cz.strategy.client.dto.ChannelInfo;
import com.cz.strategy.client.dto.ClientChannelBinding;

import java.util.List;

/**
 * 路由所需配置的来源。
 *
 * <p>路由要回答两个问题："这个客户有多少条可用通道"与"这些通道各自能否承接这条短信"。
 * 这些信息在正式架构中来自缓存，当前先由本地实现提供，使策略链可以独立运行与验证；
 * 后续接入缓存时新增一个实现类即可，路由逻辑无需改动。</p>
 *
 * @author cz
 */
public interface ChannelConfigProvider {

    /**
     * 查询客户绑定的通道。
     *
     * @param clientId 客户标识
     * @return 绑定列表；无绑定时返回空列表，不返回 null
     */
    List<ClientChannelBinding> listBindings(Long clientId);

    /**
     * 查询通道详情。
     *
     * @param channelId 通道标识
     * @return 通道详情；不存在时返回 null
     */
    ChannelInfo findChannel(Long channelId);

    /**
     * 查询客户配置的策略链。
     *
     * @param apikey 客户 apikey
     * @return 逗号分隔的策略链；未配置时返回 null
     */
    String findClientFilters(String apikey);
}
