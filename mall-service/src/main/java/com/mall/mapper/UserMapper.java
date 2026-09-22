package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UserEntity;

/**
 * 用户 Mapper。
 *
 * <p>继承 BaseMapper 后即拥有 insert / selectById / selectList / updateById
 * 等约 17 个通用方法，无需手写 SQL。复杂查询再另外定义方法 + 写 XML。</p>
 *
 * <p>注意：这里没有加 {@code @Mapper} 注解，因为启动类上已有
 * {@code @MapperScan("com.mall.mapper")}，会扫描本包下所有接口。</p>
 */
public interface UserMapper extends BaseMapper<UserEntity> {
}
