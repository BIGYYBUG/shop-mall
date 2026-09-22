package com.mall.convert.shop;

import com.mall.api.vo.ShopVO;
import com.mall.convert.VoFactory;
import com.mall.entity.ShopEntity;
import com.mall.entity.ShopStatus;
import com.mall.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 店铺 VO 装配工厂。
 *
 * <p>与 {@code ProductVoFactory} 同样是"必须能注入依赖"的工厂：
 * 它要拿 {@link FileStorageService} 把 {@code logoKey} 拼成可访问 URL。
 * 顺带把状态码翻成中文描述 —— 装配层正是做这类"给展示用的加工"的地方，
 * Service 只管业务规则，不该操心文案。</p>
 *
 * <p>店主用户名需要外部提供（要 join 用户表），所以由 Service 批量查好后
 * 通过 {@link #create(ShopEntity, String)} 传进来。放在工厂里查会导致
 * 批量装配时每行一次查询 —— 那正是 {@code createList} 要避开的 N+1。</p>
 */
@Component
@RequiredArgsConstructor
public class ShopVoFactory implements VoFactory<ShopEntity, ShopVO> {

    private final FileStorageService fileStorageService;

    @Override
    public ShopVO create(ShopEntity source) {
        return create(source, null);
    }

    /**
     * 装配店铺 VO。
     *
     * @param source        店铺实体
     * @param ownerUsername 店主登录名，可为 null（卖家侧自己看自己的店铺时无需重复携带）
     */
    public ShopVO create(ShopEntity source, String ownerUsername) {
        if (source == null) {
            return null;
        }
        ShopVO vo = new ShopVO();
        vo.setId(source.getId());
        vo.setUserId(source.getUserId());
        vo.setOwnerUsername(ownerUsername);
        vo.setName(source.getName());
        vo.setLogoUrl(fileStorageService.toAccessUrl(source.getLogoKey()));
        vo.setDescription(source.getDescription());
        vo.setContactPhone(source.getContactPhone());
        vo.setStatus(source.getStatus());
        vo.setStatusText(ShopStatus.describe(source.getStatus()));
        vo.setRejectReason(source.getRejectReason());
        vo.setCreateTime(source.getCreateTime());
        return vo;
    }
}
