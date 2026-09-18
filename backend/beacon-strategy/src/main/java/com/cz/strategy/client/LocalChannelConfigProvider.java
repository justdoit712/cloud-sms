package com.cz.strategy.client;

import com.cz.strategy.client.dto.ChannelInfo;
import com.cz.strategy.client.dto.ClientChannelBinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于本地配置的通道信息来源。
 *
 * <p>当前从配置文件读取固定的测试数据，用于在没有缓存服务的情况下让路由链路完整可运行。
 * 接入缓存后由缓存实现替代，调用方代码不变。</p>
 *
 * @author cz
 */
@Slf4j
@Component
public class LocalChannelConfigProvider implements ChannelConfigProvider {

    /** 测试客户绑定的通道，格式：通道ID:权重:是否可用:客户扩展号，多个用分号分隔。 */
    private final String bindings;

    /** 测试通道，格式：通道ID:是否可用:通道类型:通道接入号，多个用分号分隔。 */
    private final String channels;

    /** 测试客户的策略链。 */
    private final String clientFilters;

    public LocalChannelConfigProvider(
            @Value("${local.test-channel.bindings:}") String bindings,
            @Value("${local.test-channel.channels:}") String channels,
            @Value("${local.test-channel.client-filters:route}") String clientFilters) {
        this.bindings = bindings;
        this.channels = channels;
        this.clientFilters = clientFilters;
    }

    @Override
    public List<ClientChannelBinding> listBindings(Long clientId) {
        List<ClientChannelBinding> result = new ArrayList<>();
        for (String entry : splitEntries(bindings)) {
            String[] parts = entry.split(":");
            if (parts.length < 3) {
                log.warn("通道绑定配置格式不正确, entry={}", entry);
                continue;
            }
            result.add(new ClientChannelBinding(
                    parseLong(parts[0]),
                    parseInt(parts[1]),
                    parseInt(parts[2]),
                    parts.length > 3 ? parts[3] : ""));
        }
        return result;
    }

    @Override
    public ChannelInfo findChannel(Long channelId) {
        if (channelId == null) {
            return null;
        }
        for (String entry : splitEntries(channels)) {
            String[] parts = entry.split(":");
            if (parts.length < 4) {
                log.warn("通道配置格式不正确, entry={}", entry);
                continue;
            }
            if (channelId.equals(parseLong(parts[0]))) {
                return new ChannelInfo(parseLong(parts[0]), parseInt(parts[1]),
                        parseInt(parts[2]), parts[3]);
            }
        }
        return null;
    }

    @Override
    public String findClientFilters(String apikey) {
        if (apikey == null || apikey.isBlank()) {
            return null;
        }
        return clientFilters;
    }

    /** 按分号拆分配置项，忽略空白项。 */
    private List<String> splitEntries(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(";")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 数值解析失败时返回 null，交由调用方按"未配置"处理。 */
    private Long parseLong(String value) {
        try {
            return Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            log.warn("配置项无法解析为数字, value={}", value);
            return null;
        }
    }

    /** 数值解析失败时返回 null。 */
    private Integer parseInt(String value) {
        Long parsed = parseLong(value);
        return parsed == null ? null : parsed.intValue();
    }
}
