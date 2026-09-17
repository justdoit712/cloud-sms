package com.cz.common.constant;

/**
 * 短信业务状态与类型常量。
 *
 * @author cz
 */
public interface SmsConstant {

    /** 发送状态：成功。 */
    int REPORT_SUCCESS = 1;

    /** 发送状态：失败。 */
    int REPORT_FAIL = 2;

    /** 短信类型：验证码。 */
    int CODE_TYPE = 0;

    /** 短信类型：通知。 */
    int NOTIFY_TYPE = 1;

    /** 短信类型：营销。 */
    int MARKETING_TYPE = 2;
}
