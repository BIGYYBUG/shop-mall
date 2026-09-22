package com.mall.convert.product;

import com.mall.api.vo.ProductVO;
import com.mall.convert.VoFactory;
import com.mall.entity.ProductEntity;
import com.mall.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品 VO 装配工厂。
 *
 * <p><b>为什么这个工厂必须是个 Spring Bean、而不是一个静态工具类</b>：
 * 它需要 {@link FileStorageService} 才能把库里存的 objectKey
 * （{@code product/2026/09/19/a1b2.jpg}）拼成前端可访问的完整 URL。
 * 用静态方法就得把 storage 当参数一路传进来，每个调用方都得知道这件事 ——
 * 那等于把装配责任又推回给了调用方。</p>
 *
 * <p>顺带一提：{@code coverKey → coverUrl} 这个转换必须发生在出参这一层。
 * 一旦有人"图省事"把裸 key 当 URL 返回，本地存储环境下可能还看得见图
 * （因为 {@code /uploads/**} 映射会兜住一部分），切到 OSS 后才会集中爆发。</p>
 */
@Component
@RequiredArgsConstructor
public class ProductVoFactory implements VoFactory<ProductEntity, ProductVO> {

    private final FileStorageService fileStorageService;

    @Override
    public ProductVO create(ProductEntity source) {
        if (source == null) {
            return null;
        }
        ProductVO vo = new ProductVO();
        vo.setId(source.getId());
        vo.setName(source.getName());
        vo.setSubtitle(source.getSubtitle());
        vo.setCategoryId(source.getCategoryId());
        vo.setSellerId(source.getSellerId());
        vo.setPrice(source.getPrice());
        vo.setOriginalPrice(source.getOriginalPrice());
        vo.setStock(source.getStock());
        vo.setSales(source.getSales());

        // 单值：objectKey → 完整 URL
        vo.setCoverUrl(fileStorageService.toAccessUrl(source.getCoverKey()));

        // 多值：图集逐项转换。images 为 null 时返回空列表而不是 null，
        // 让前端永远可以安全地 v-for，不用到处判空
        vo.setImageUrls(source.getImages() == null
                ? List.of()
                : source.getImages().stream().map(fileStorageService::toAccessUrl).toList());

        vo.setDescription(source.getDescription());
        vo.setStatus(source.getStatus());
        vo.setSort(source.getSort());
        vo.setCreateTime(source.getCreateTime());
        return vo;
    }
}
