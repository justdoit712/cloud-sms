package com.cz.common.constant;

/**
 * 系统内使用的 RabbitMQ 拓扑名称常量。
 *
 * <p>本接口是<b>跨模块 MQ 契约的唯一定义处</b>：所有模块一律引用这里的常量，
 * 禁止在业务代码里裸写队列名或交换机名字符串。</p>
 *
 * <p>⚠️ 命名沿用了历史约定，<b>后缀不可作为类型判断依据</b>：
 * {@code *_topic} 结尾的<b>其实是队列名</b>（通过 default exchange 以队列名作 routing key 直投，
 * 并非 TopicExchange）。因此每个常量都必须在注释里明确标注它是"队列"还是"交换机"，
 * 以及生产者与消费者分别是谁。</p>
 *
 * <p>常量<b>随场景逐个添加</b>：只声明当前已实现链路真正用到的名称，避免提前固化未落地的拓扑。</p>
 *
 * @author cz
 */
public interface RabbitMQConstants {

    /** 预发送队列：【队列】api 生产，strategy 消费。短信受理后的第一跳。 */
    String SMS_PRE_SEND = "sms_pre_send_topic";

    /**
     * 网关队列名前缀：【队列名前缀】strategy 生产，smsgateway 消费。
     *
     * <p>实际队列名为"本前缀 + 通道 ID"，即按通道动态分队列，
     * 由 strategy 路由时声明并投递。</p>
     */
    String SMS_GATEWAY = "sms_gateway_topic_";

    /** 日志写入队列：【队列】strategy / smsgateway 生产，search 消费，用于把发送日志写入检索库。 */
    String SMS_WRITE_LOG = "sms_write_log_topic";
}
