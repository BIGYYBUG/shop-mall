package com.mall.convert.rbac;

import com.mall.api.vo.PermissionVO;
import com.mall.convert.VoFactory;
import com.mall.entity.PermissionEntity;
import org.springframework.stereotype.Component;

/**
 * 权限 VO 装配工厂。
 *
 * <p>与本项目所有 VO 一致，装配只在这里发生 —— Controller 和 Service
 * 都不允许出现 {@code new PermissionVO()} 加一串 setter。</p>
 */
@Component
public class PermissionVoFactory implements VoFactory<PermissionEntity, PermissionVO> {

    @Override
    public PermissionVO create(PermissionEntity source) {
        if (source == null) {
            return null;
        }
        PermissionVO vo = new PermissionVO();
        vo.setId(source.getId());
        vo.setCode(source.getCode());
        vo.setName(source.getName());
        vo.setType(source.getType());
        vo.setSort(source.getSort());
        vo.setStatus(source.getStatus());
        return vo;
    }
}
