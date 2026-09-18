package com.cz.api.config;

import com.cz.common.constant.RabbitMQConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列配置。
 *
 * <p>本模块只负责预发送队列的<b>声明与投递</b>，消费方是策略服务。
 * 队列由生产方声明，避免多个模块重复声明导致参数不一致。</p>
 *
 * @author cz
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * 预发送队列：短信受理后的第一跳。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue smsPreSendQueue() {
        return QueueBuilder.durable(RabbitMQConstants.SMS_PRE_SEND).build();
    }

    /**
     * 消息转换器。
     *
     * <p>复用容器中的对象映射器，而不是自建实例 —— 自建实例不带日期时间模块，
     * 消息体中的时间字段会在运行期序列化失败（编译期不报错）。</p>
     *
     * @param objectMapper 容器中的对象映射器
     * @return JSON 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 投递模板。
     *
     * <p>显式开启投递确认与不可路由返回：只注册回调而不开启对应开关时，
     * 回调不会生效，投递失败将无声丢失。此处把失败记录到日志，便于后续补偿。</p>
     *
     * @param connectionFactory 连接工厂
     * @param messageConverter  消息转换器
     * @return 投递模板
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                        MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setReturnsCallback(returned ->
                log.error("消息不可路由, exchange={}, routingKey={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息未被 broker 确认, correlationData={}, cause={}", correlationData, cause);
            }
        });
        return template;
    }
}
