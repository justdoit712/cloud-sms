package com.cz.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 短信发送主链路中的统一提交对象。
 *
 * <p>该对象贯穿 API、策略、网关等核心模块，承载一条短信从受理到下发前的全部公共字段。</p>
 *
 * <p><b>刻意设计为可变类（{@code @Data} 而非 {@code record}）</b>：链路中各过滤器会依次写入不同字段
 * —— phase 过滤器补 {@code operatorId}、route 过滤器填 {@code channelId} / {@code srcNumber}
 * —— 若用 record 则每一步都要 copy 出新对象；且该对象经 RabbitMQ 传输，
 * 反序列化需要无参构造。</p>
 *
 * @author cz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StandardSubmit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 针对当前短信的唯一标识（雪花 ID）。 */
    private Long sequenceId;

    /** 客户 ID（由 apikey 解析得到）。 */
    private Long clientId;

    /** 客户 IP 白名单快照，来自 {@code client_business} 缓存域的 {@code ipAddress} 字段。 */
    private List<String> ip;

    /** 客户业务侧请求 ID，用于客户自行关联请求与回执。 */
    private String uid;

    /** 目标手机号。 */
    private String mobile;

    /** 短信签名（形如"【签名】"的原文）。 */
    private String sign;

    /** 短信内容。 */
    private String text;

    /** 短信发送时间。 */
    private LocalDateTime sendTime;

    /** 本条短信的费用，<b>单位：厘</b>（50 厘 = 0.05 元）。用整数避免浮点误差。 */
    private Long fee;

    /** 目标手机号的运营商，由 phase 过滤器补齐。 */
    private Integer operatorId;

    /** 目标手机号的归属地区号。 */
    private Integer areaCode;

    /** 目标手机号的归属地。 */
    private String area;

    /** 通道下发时使用的源号码，由 route 过滤器按"通道号 + 客户通道扩展号"拼接。 */
    private String srcNumber;

    /** 选定的通道 ID，由 route 过滤器填充。 */
    private Long channelId;

    /** 短信发送状态：0-等待，1-成功，2-失败。 */
    private int reportState;

    /** 短信发送失败时的错误信息。 */
    private String errorMsg;

    /** 真实请求 IP（经可信代理头解析后的结果）。 */
    private String realIp;

    /** 客户端请求携带的 apiKey。 */
    private String apiKey;

    /** 短信类型：0-验证码，1-通知，2-营销。 */
    private int state;

    /** 短信签名 ID。 */
    private Long signId;

    /** 是否为携号转网场景。 */
    private Boolean isTransfer = false;

    /**
     * 一小时限流规则使用的时间戳。
     *
     * <p>⚠️ 该字段在旧实现中是<b>死字段</b>（全仓无读写，却进了 ES 索引字段清单）。
     * 重写版暂时保留以避免改动跨版本消息结构，<b>待限流逻辑重新设计后评估删除</b>。</p>
     */
    private Long oneHourLimitMilli;
}
