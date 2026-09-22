package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.ProductEntity;

/**
 * 商品 Mapper。
 *
 * <p>当前没有自定义 SQL 需求，{@link BaseMapper} 提供的
 * insert / updateById / deleteById / selectPage 已经够用，因此不配 XML。
 * 真正需要 4 表以上 join 或复杂聚合时再建 {@code ProductMapper.xml}。</p>
 */
public interface ProductMapper extends BaseMapper<ProductEntity> {
}
