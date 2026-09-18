package com.cz.api.controller;

import com.cz.api.client.ClientConfigProvider;
import com.cz.api.config.InternalSmsProperties;
import com.cz.api.filter.CheckFilterContext;
import com.cz.api.form.InternalSingleSendForm;
import com.cz.api.form.SingleSendForm;
import com.cz.api.util.ClientIpResolver;
import com.cz.api.vo.SmsSendResultVO;
import com.cz.common.constant.RabbitMQConstants;
import com.cz.common.enums.ExceptionEnums;
import com.cz.common.exception.ApiException;
import com.cz.common.model.StandardSubmit;
import com.cz.common.util.SecureEqualsUtil;
import com.cz.common.util.SnowFlakeUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 短信受理入口。
 *
 * <p>对外接口走完整校验链；内部接口供平台内其它服务调用，仅校验调用令牌
 * （令牌必填且以定长方式比较）。两者都只负责受理与投递，不做实际发送。</p>
 *
 * @author cz
 */
@Slf4j
@RestController
@RequestMapping("/sms")
public class SmsController {

    /** 内部接口令牌请求头名称。 */
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final CheckFilterContext checkFilterContext;
    private final ClientConfigProvider clientConfigProvider;
    private final ClientIpResolver clientIpResolver;
    private final InternalSmsProperties internalSmsProperties;
    private final SnowFlakeUtil snowFlakeUtil;
    private final RabbitTemplate rabbitTemplate;

    public SmsController(CheckFilterContext checkFilterContext,
                         ClientConfigProvider clientConfigProvider,
                         ClientIpResolver clientIpResolver,
                         InternalSmsProperties internalSmsProperties,
                         SnowFlakeUtil snowFlakeUtil,
                         RabbitTemplate rabbitTemplate) {
        this.checkFilterContext = checkFilterContext;
        this.clientConfigProvider = clientConfigProvider;
        this.clientIpResolver = clientIpResolver;
        this.internalSmsProperties = internalSmsProperties;
        this.snowFlakeUtil = snowFlakeUtil;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 外部客户单条短信发送。
     *
     * @param form    请求体
     * @param request 当前请求，用于解析来源 IP
     * @return 受理结果
     */
    @PostMapping("/single_send")
    public SmsSendResultVO singleSend(@RequestBody @Valid SingleSendForm form,
                                      HttpServletRequest request) {
        StandardSubmit submit = buildSubmit(form.apikey(), form.mobile(), form.text(),
                form.state(), form.uid(), clientIpResolver.resolve(request));
        checkFilterContext.check(submit);
        return enqueue(submit);
    }

    /**
     * 内部服务单条短信发送（如运营后台代发）。
     *
     * @param token   内部调用令牌
     * @param form    请求体
     * @param request 当前请求，用于解析来源 IP
     * @return 受理结果
     */
    @PostMapping("/internal/single_send")
    public SmsSendResultVO internalSingleSend(
            @RequestHeader(value = HEADER_INTERNAL_TOKEN, required = false) String token,
            @RequestBody @Valid InternalSingleSendForm form,
            HttpServletRequest request) {
        if (!SecureEqualsUtil.equals(internalSmsProperties.getToken(), token)) {
            log.info("内部受理失败: 调用令牌无效");
            throw new ApiException(ExceptionEnums.INTERNAL_TOKEN_INVALID);
        }

        StandardSubmit submit = buildSubmit(form.apikey(), form.mobile(), form.text(),
                form.state(), form.uid(), clientIpResolver.resolve(request));

        if (!clientConfigProvider.fillClientInfo(form.apikey(), submit)) {
            log.info("内部受理失败: apikey 无法识别");
            throw new ApiException(ExceptionEnums.ERROR_APIKEY);
        }

        return enqueue(submit);
    }

    /**
     * 组装提交对象：补齐流水号与受理时间。
     *
     * @return 待校验的提交对象
     */
    private StandardSubmit buildSubmit(String apikey, String mobile, String text,
                                       Integer state, String uid, String realIp) {
        StandardSubmit submit = new StandardSubmit();
        submit.setSequenceId(snowFlakeUtil.nextId());
        submit.setApiKey(apikey);
        submit.setMobile(mobile);
        submit.setText(text);
        submit.setState(state == null ? 0 : state);
        submit.setUid(uid);
        submit.setRealIp(realIp);
        submit.setSendTime(LocalDateTime.now());
        return submit;
    }

    /**
     * 投递到预发送队列并返回受理结果。
     *
     * @param submit 已通过校验的提交对象
     * @return 受理结果
     */
    private SmsSendResultVO enqueue(StandardSubmit submit) {
        rabbitTemplate.convertAndSend(RabbitMQConstants.SMS_PRE_SEND, submit);
        log.info("短信已受理并投递, sequenceId={}, clientId={}",
                submit.getSequenceId(), submit.getClientId());
        return SmsSendResultVO.ok(submit.getUid(), String.valueOf(submit.getSequenceId()));
    }
}
