package com.cz.strategy.client.dto;

/**
 * 通道详情快照。
 *
 * <p>表示某个短信通道自身的属性，用于判断"能否用它下发这条短信"。
 * 刻意设计为 {@code record}：它是从外部读入的只读快照。</p>
 *
 * @param id             通道标识
 * @param isAvailable    通道是否可用：1 可用 / 0 不可用
 * @param channelType    通道类型：0 表示通用通道，其它取值表示仅支持对应运营商
 * @param channelNumber  通道接入号，参与下行源号码拼接
 * @author cz
 */
public record ChannelInfo(Long id,
                          Integer isAvailable,
                          Integer channelType,
                          String channelNumber) {

    /** 通道可用时的取值。 */
    private static final int AVAILABLE = 1;

    /** 通用通道的类型取值：不限定运营商。 */
    private static final int GENERIC_TYPE = 0;

    /**
     * 该通道是否可用于路由。
     *
     * @return 可用返回 true
     */
    public boolean isAvailableForRoute() {
        return isAvailable != null && isAvailable == AVAILABLE;
    }

    /**
     * 该通道是否支持指定运营商。
     *
     * <p>通用通道支持任意运营商；专用通道要求运营商与通道类型一致。
     * 目标运营商未知时不匹配专用通道，避免把号码投给错误的通道。</p>
     *
     * @param operatorId 目标运营商标识，可为空
     * @return 支持返回 true
     */
    public boolean supportsOperator(Integer operatorId) {
        if (channelType != null && channelType == GENERIC_TYPE) {
            return true;
        }
        return operatorId != null && operatorId.equals(channelType);
    }
}
