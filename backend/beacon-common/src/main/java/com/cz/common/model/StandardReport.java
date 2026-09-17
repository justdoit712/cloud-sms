package com.cz.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回执更新与客户回调共用的统一报告对象。
 *
 * <p>网关收到运营商状态报告后，会把它<b>同时投递到两条支路</b>：
 * 一条是"状态更新链"（经 TTL + 死信延迟后由日志服务更新检索库），
 * 另一条是"客户回调链"（由回调服务投递给客户）。
 * 两条支路所需字段高度重叠，故共用一个载体，避免网关侧写两份装配逻辑。</p>
 *
 * <p>与发送载体 {@link StandardSubmit} 的区别：本对象<b>只承载"结果"</b>
 * —— 不含内容、签名、通道等下发期字段，只带客户回调所需的信息。</p>
 *
 * <p>刻意设计为可变类（{@code @Data} 而非 {@code record}）：网关侧需先按消息 id
 * 取回中间态，再补 {@code isCallback} / {@code callbackUrl}（来自客户配置），最后才投递；
 * 且该对象经 RabbitMQ 传输，反序列化需要无参构造。</p>
 *
 * @author cz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StandardReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 客户 apiKey，用于查询回调配置等扩展信息。 */
    private String apiKey;

    /** 短信唯一标识，同时作为检索库中文档的主键。 */
    private Long sequenceId;

    /** 客户 ID。 */
    private Long clientId;

    /** 客户业务侧请求 ID，回调时原样回传给客户。 */
    private String uid;

    /** 目标手机号。 */
    private String mobile;

    /** 短信发送时间。 */
    private LocalDateTime sendTime;

    /** 短信发送状态：0-等待，1-成功，2-失败。 */
    private int reportState;

    /** 短信发送失败时的错误信息。 */
    private String errorMsg;

    /** 客户回调开关：0-不回调，1-回调。对应客户配置中的 tinyint 列，故用 Integer 保持与存储层一致。 */
    private Integer isCallback;

    /** 客户接收状态报告的回调地址。 */
    private String callbackUrl;

    /** 客户回调已重试次数，由回调服务在每次失败后递增。 */
    private Integer resendCount = 0;

    /**
     * 是否为"二次更新"投递。
     *
     * <p>状态更新链走"延迟后更新"：首次更新时检索库里可能还没有对应文档，
     * 此时置为 true 把消息重投一轮，给写日志链路留出时间。</p>
     */
    private Boolean reUpdate = false;
}
