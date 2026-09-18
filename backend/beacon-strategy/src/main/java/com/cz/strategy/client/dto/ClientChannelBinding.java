package com.cz.strategy.client.dto;

/**
 * 客户与通道的绑定关系快照。
 *
 * <p>表示"某客户可以在某个通道上发短信"，并携带该绑定关系自身的路由属性。
 * 刻意设计为 {@code record}：它是从外部读入的只读快照。</p>
 *
 * @param channelId              通道标识
 * @param weight                 路由权重，值越大越优先
 * @param isAvailable            绑定是否可用：1 可用 / 0 不可用
 * @param clientChannelNumber    客户在该通道上的扩展号，参与下行源号码拼接
 * @author cz
 */
public record ClientChannelBinding(Long channelId,
                                   Integer weight,
                                   Integer isAvailable,
                                   String clientChannelNumber) {

    /** 绑定可用时的取值。 */
    private static final int AVAILABLE = 1;

    /**
     * 该绑定是否可用于路由。
     *
     * <p>取值语义与数据表保持一致：1 表示可用。未配置时视为不可用，
     * 避免"字段缺失即放行"。</p>
     *
     * @return 可用返回 true
     */
    public boolean isAvailableForRoute() {
        return isAvailable != null && isAvailable == AVAILABLE;
    }
}
