package com.cz.strategy.config;

import com.cz.common.constant.RabbitMQConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列配置。
 *
 * <p>本模块既是预发送队列的消费方，也是日志与回调两个队列的生产方。
 * 队列声明遵循"由消费方声明"的约定：消费方先启动时队列就已存在，
 * 生产方投递时不会因为队列缺失而丢消息。</p>
 *
 * @author cz
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * 预发送队列：由本模块消费。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue smsPreSendQueue() {
        return QueueBuilder.durable(RabbitMQConstants.SMS_PRE_SEND).build();
    }

    /**
     * 日志写入队列：由日志服务消费，本模块只投递。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue smsWriteLogQueue() {
        return QueueBuilder.durable(RabbitMQConstants.SMS_WRITE_LOG).build();
    }

    /**
     * 状态回调队列：由回调服务消费，本模块只投递。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue smsPushReportQueue() {
        return QueueBuilder.durable(RabbitMQConstants.SMS_PUSH_REPORT).build();
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
}
