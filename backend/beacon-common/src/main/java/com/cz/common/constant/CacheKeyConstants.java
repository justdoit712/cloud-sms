package com.cz.common.constant;

/**
 * 缓存逻辑键前缀常量。
 *
 * <p>Redis 中的键名属于<b>跨模块契约</b>（例如策略模块要按 api 模块写入的键读取客户配置），
 * 因此所有逻辑键前缀一律在此集中定义，业务代码禁止裸写字符串 ——
 * 裸写一旦拼错，运行期只会表现为"查不到缓存"，不会报错，排查成本极高。</p>
 *
 * <p>这里是<b>逻辑键</b>（不含命名空间）。实际写入 Redis 的物理键由缓存服务统一加上
 * 命名空间前缀，业务侧只需拼接本类常量与业务主键。</p>
 *
 * @author cz
 */
public interface CacheKeyConstants {

    /** 客户业务配置：{@code client_business:{apikey}}，存客户 ID、IP 白名单、策略校验链等。 */
    String CLIENT_BUSINESS = "client_business:";

    /** 客户短信签名集合：{@code client_sign:{clientId}}。 */
    String CLIENT_SIGN = "client_sign:";

    /** 客户通道绑定集合：{@code client_channel:{clientId}}，路由时用于选择下发通道。 */
    String CLIENT_CHANNEL = "client_channel:";

    /** 通道详情：{@code channel:{channelId}}，含通道可用状态与支持的运营商。 */
    String CHANNEL = "channel:";

    /** 通用分隔符，用于拼接复合键（如 {@code black:{clientId}:{mobile}}）。 */
    String SEPARATE = ":";
}
