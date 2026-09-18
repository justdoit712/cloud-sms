package com.cz.api.filter;

import com.cz.common.model.StandardSubmit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受理校验链的上下文。
 *
 * <p>校验链的名称与顺序由配置驱动，本类负责按配置依次执行。与"按配置执行"配套的两条约束：</p>
 * <ul>
 *   <li><b>启动期校验</b>：配置中出现未知的校验器名称时直接启动失败，
 *       而不是运行期才发现某个环节被静默跳过；</li>
 *   <li><b>顺序校验</b>：后续环节依赖前序环节写入的字段（如签名校验依赖客户标识），
 *       顺序错误会导致依赖缺失，因此启动期一并检查。</li>
 * </ul>
 *
 * @author cz
 */
@Slf4j
@Component
@RefreshScope
public class CheckFilterContext {

    /** 必须位于其余环节之前的校验器：它们负责写入后续环节依赖的字段。 */
    private static final List<String> MANDATORY_ORDER = List.of("apikey");

    /** 校验器标识到实现的映射，由容器按 Bean 名称注入。 */
    private final Map<String, CheckFilter> checkFilters;

    /** 校验链配置，逗号分隔。 */
    private final String filters;

    public CheckFilterContext(Map<String, CheckFilter> checkFilters,
                              @Value("${filters:apikey,ip,sign}") String filters) {
        this.checkFilters = checkFilters;
        this.filters = filters;
    }

    /**
     * 启动期校验配置：名称必须存在，且前置环节顺序正确。
     *
     * @throws IllegalStateException 配置了未知校验器或顺序不合法时抛出
     */
    @PostConstruct
    public void validateFilters() {
        List<String> names = resolveFilterNames();

        for (String name : names) {
            if (!checkFilters.containsKey(name)) {
                throw new IllegalStateException(
                        "校验链配置了未知的校验器: " + name + "，可选值: " + checkFilters.keySet());
            }
        }

        for (String mandatory : MANDATORY_ORDER) {
            int index = names.indexOf(mandatory);
            if (index > 0) {
                throw new IllegalStateException(
                        "校验链顺序不合法: " + mandatory + " 必须位于首位，实际顺序: " + names);
            }
        }

        log.info("受理校验链已就绪, 顺序={}", names);
    }

    /**
     * 依次执行校验链。
     *
     * @param submit 待校验的提交对象
     */
    public void check(StandardSubmit submit) {
        for (String name : resolveFilterNames()) {
            CheckFilter filter = checkFilters.get(name);
            if (filter == null) {
                throw new IllegalStateException("校验链配置了未知的校验器: " + name);
            }
            filter.check(submit);
        }
    }

    /** 解析配置得到校验器名称清单。 */
    private List<String> resolveFilterNames() {
        List<String> names = new ArrayList<>();
        if (!StringUtils.hasText(filters)) {
            return names;
        }
        for (String item : filters.split(",")) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String name = item.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
