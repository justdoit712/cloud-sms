package com.cz.api.filter;

import com.cz.common.model.StandardSubmit;

/**
 * 受理校验链中的一个环节。
 *
 * <p>实现类以 Bean 名称作为校验器标识（如 {@code apikey}、{@code ip}、{@code sign}），
 * 校验顺序由配置决定，链本身不感知具体实现。</p>
 *
 * <p>校验不通过时抛出业务异常，由统一异常处理转成对外响应 ——
 * <b>不使用返回值表示失败</b>，避免调用方漏判。</p>
 *
 * @author cz
 */
public interface CheckFilter {

    /**
     * 执行校验，必要时把校验结果写回提交对象。
     *
     * @param submit 待校验的提交对象
     */
    void check(StandardSubmit submit);
}
