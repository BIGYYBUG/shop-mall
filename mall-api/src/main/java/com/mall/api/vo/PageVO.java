package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果 VO。
 *
 * <p><b>为什么不直接把 MyBatis-Plus 的 IPage 返给前端？</b>
 * IPage 里带着 orderBy、optimizeCountSql、searchCount 等一堆纯技术字段，
 * 而且它的包名会把「前端接口」和「ORM 框架」绑死 —— 换 ORM 就得改前端。
 * 这里抽成自己的结构，只保留前端真正需要的 5 个字段。</p>
 *
 * @param <T> 记录类型
 */
@Data
public class PageVO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private Long total;

    /** 总页数 */
    private Long pages;

    /** 当前页码，从 1 开始 */
    private Long current;

    /** 每页条数 */
    private Long size;

    /** 当前页数据 */
    private List<T> records;

    public PageVO() {
    }

    public PageVO(Long total, Long pages, Long current, Long size, List<T> records) {
        this.total = total;
        this.pages = pages;
        this.current = current;
        this.size = size;
        this.records = records;
    }
}
