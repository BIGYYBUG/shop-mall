package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.ShopEntity;

/**
 * 店铺 Mapper。
 *
 * <p>只需要 BaseMapper 的通用 CRUD：所有查询条件（按 userId 查、按状态筛、
 * 分页列表）都能用 Wrapper 表达，没有多表连接，因此不需要 XML。</p>
 */
public interface ShopMapper extends BaseMapper<ShopEntity> {
}
