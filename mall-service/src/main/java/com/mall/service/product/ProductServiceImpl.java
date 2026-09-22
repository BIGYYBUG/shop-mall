package com.mall.service.product;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.api.dto.ProductDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ProductVO;
import com.mall.common.exception.BusinessException;
import com.mall.convert.product.ProductVoFactory;
import com.mall.entity.ProductEntity;
import com.mall.mapper.ProductMapper;
import com.mall.service.shop.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.mall.common.util.TextUtils.trimToNull;

/**
 * 商品服务实现。
 *
 * <p>三组公开方法的差别只有一个：<b>查询范围</b>。前台锁死 {@code status = 1}，
 * 管理端不限制归属，卖家侧强制 {@code seller_id = 当前用户}。
 * 共享逻辑收敛到 private 方法里，避免三份实现各自演化。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    /** 分页单页上限。和用户分页保持同一个口径，避免前端某个接口能一次拉全表 */
    private static final long MAX_PAGE_SIZE = 100;

    /**
     * 平台自营商品的 seller_id。
     *
     * <p>用 0 而不是 NULL：平台自营是一种确定的归属，不是"未知"。
     * 存 NULL 会导致所有卖家侧查询都要补 {@code OR seller_id IS NULL}，
     * 既容易漏写也让索引失效。</p>
     */
    private static final long PLATFORM_SELLER_ID = 0L;

    private final ProductMapper productMapper;
    /**
     * VO 装配统一走工厂。
     *
     * <p>注意本类已经不再注入 {@code FileStorageService} —— 把 objectKey 拼成
     * 可访问 URL 是"装配"职责，属于工厂内部的事。本类只管取数和业务规则，
     * 不需要知道存储层长什么样。</p>
     */
    private final ProductVoFactory productVoFactory;
    /**
     * 卖家侧写操作需要先确认「店铺在当前状态下允许经营」。
     *
     * <p>仅靠 RBAC 的 {@code seller:product:*} 权限码是不够的 ——
     * 权限码只能说明"这个人是个卖家"，说明不了"他的店现在还在正常经营"。</p>
     */
    private final ShopService shopService;

    // ==================================================================
    // 前台
    // ==================================================================

    @Override
    public PageVO<ProductVO> pageOnlineProducts(long pageNum, long pageSize, String keyword, Long categoryId) {
        IPage<ProductEntity> page = productMapper.selectPage(
                new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)),
                Wrappers.<ProductEntity>lambdaQuery()
                        // 前台必须锁死 status = 1，这是这一层的核心职责，不接受任何调用方覆盖
                        .eq(ProductEntity::getStatus, 1)
                        // and(...) 加括号，否则 LIKE 条件会被 OR 拆散，变成
                        // status = 1 AND name LIKE ? OR subtitle LIKE ? —— 直接漏出下架商品
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(ProductEntity::getName, keyword)
                                .or().like(ProductEntity::getSubtitle, keyword))
                        .eq(categoryId != null, ProductEntity::getCategoryId, categoryId)
                        // 排序与 idx_status_sort 索引顺序一致：(status, sort)
                        .orderByDesc(ProductEntity::getSort)
                        .orderByDesc(ProductEntity::getId)
        );
        return toPageVO(page);
    }

    @Override
    public ProductVO getProductById(Long id) {
        ProductEntity entity = productMapper.selectOne(
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getId, id)
                        // 下架商品对前台完全不存在。不区分 404 与"已下架"，
                        // 否则可以被用来探测某个商品是否存在过
                        .eq(ProductEntity::getStatus, 1)
        );
        if (entity == null) {
            throw new BusinessException(404, "商品不存在或已下架");
        }
        return productVoFactory.create(entity);
    }

    // ==================================================================
    // 管理端
    // ==================================================================

    @Override
    public PageVO<ProductVO> pageAdminProducts(long pageNum, long pageSize, String keyword,
                                               Integer status, Long categoryId) {
        IPage<ProductEntity> page = productMapper.selectPage(
                new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)),
                Wrappers.<ProductEntity>lambdaQuery()
                        // 管理端 status 是可选过滤条件（null = 全部），与前台"强制上架"形成对照
                        .eq(status != null, ProductEntity::getStatus, status)
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(ProductEntity::getName, keyword)
                                .or().like(ProductEntity::getSubtitle, keyword))
                        .eq(categoryId != null, ProductEntity::getCategoryId, categoryId)
                        // 管理端按 ID 倒序（最新的在最上面），不按 sort ——
                        // 运营要找的是"我刚建的那个"，不是"排在最前面的那个"
                        .orderByDesc(ProductEntity::getId)
        );
        return toPageVO(page);
    }

    @Override
    public ProductVO getProductForAdmin(Long id) {
        return productVoFactory.create(loadProductOrThrow(id));
    }

    @Override
    public Long createProduct(ProductDTO dto) {
        return doCreate(dto, PLATFORM_SELLER_ID);
    }

    @Override
    public void updateProduct(Long id, ProductDTO dto) {
        loadProductOrThrow(id);
        doUpdate(id, dto);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        validateStatus(status);
        loadProductOrThrow(id);
        doUpdateStatus(id, status);
    }

    @Override
    public void deleteProduct(Long id) {
        loadProductOrThrow(id);
        doDelete(id);
    }

    // ==================================================================
    // 卖家侧
    //
    // 六个方法都是「先确认店铺可经营 → 再确认商品归属 → 才动手」。
    // 顺序不能颠倒：归属校验说明"这件货是不是你的"，
    // 店铺状态校验说明"你现在能不能做生意"，两者都过了才有意义。
    // ==================================================================

    @Override
    public PageVO<ProductVO> pageSellerProducts(Long sellerId, long pageNum, long pageSize,
                                               String keyword, Integer status) {
        // 读操作只要求"有店铺"，不要求状态正常 ——
        // 店铺被冻结的店主仍然应该能看到自己的货还在不在
        shopService.requireShop(sellerId);

        IPage<ProductEntity> page = productMapper.selectPage(
                new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)),
                Wrappers.<ProductEntity>lambdaQuery()
                        // 归属条件放在第一位。这不是可选的过滤项，而是这一层的存在理由
                        .eq(ProductEntity::getSellerId, sellerId)
                        .eq(status != null, ProductEntity::getStatus, status)
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(ProductEntity::getName, keyword)
                                .or().like(ProductEntity::getSubtitle, keyword))
                        // 与 idx_seller_status_sort(seller_id, status, sort) 的列顺序一致
                        .orderByDesc(ProductEntity::getSort)
                        .orderByDesc(ProductEntity::getId)
        );
        return toPageVO(page);
    }

    @Override
    public ProductVO getSellerProduct(Long sellerId, Long id) {
        // 读操作同样只要求"有店铺"
        shopService.requireShop(sellerId);
        return productVoFactory.create(requireOwnedProduct(sellerId, id));
    }

    @Override
    public Long createSellerProduct(Long sellerId, ProductDTO dto) {
        shopService.requireOperableShop(sellerId);
        return doCreate(dto, sellerId);
    }

    @Override
    public void updateSellerProduct(Long sellerId, Long id, ProductDTO dto) {
        shopService.requireOperableShop(sellerId);
        requireOwnedProduct(sellerId, id);
        doUpdate(id, dto);
    }

    @Override
    public void updateSellerProductStatus(Long sellerId, Long id, Integer status) {
        validateStatus(status);
        shopService.requireOperableShop(sellerId);
        requireOwnedProduct(sellerId, id);
        doUpdateStatus(id, status);
    }

    @Override
    public void deleteSellerProduct(Long sellerId, Long id) {
        shopService.requireOperableShop(sellerId);
        requireOwnedProduct(sellerId, id);
        doDelete(id);
    }

    // ==================================================================
    // 共享实现
    //
    // 做成 private 而不是暴露一个可空的范围参数，是为了让「传 null 就变成全平台」
    // 这种危险默认值在公开 API 上根本不存在 —— 详见 ProductService 类注释。
    // ==================================================================

    private Long doCreate(ProductDTO dto, long sellerId) {
        validatePriceRelation(dto);

        ProductEntity entity = new ProductEntity();
        applyDto(entity, dto);
        // 归属由服务端写入，绝不取客户端值 —— 否则卖家可以把自己新建的商品
        // 挂到别的店铺名下
        entity.setSellerId(sellerId);
        // 新商品默认下架：避免运营/卖家填一半就保存，用户立刻能在前台看到半成品
        entity.setStatus(dto.getStatus() == null ? 0 : dto.getStatus());
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        // sales 不由客户端提供：销量只能来自真实下单，绝不能允许前端写入
        entity.setSales(0);

        productMapper.insert(entity);
        log.info("商品已创建：id={}, name={}, sellerId={}, status={}",
                entity.getId(), entity.getName(), sellerId, entity.getStatus());
        return entity.getId();
    }

    private void doUpdate(Long id, ProductDTO dto) {
        validatePriceRelation(dto);

        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        applyDto(entity, dto);
        // updateById 只更新非 null 字段，因此：
        //   · status 不在这里设置 —— 上下架走独立接口，职责分开
        //   · sales 不在这里设置 —— 销量只能由下单流程累加
        //   · sellerId 不在这里设置 —— 商品归属创建后不可通过编辑接口变更，
        //     否则一次"改文案"的请求就能把商品过户给别人
        productMapper.updateById(entity);
        log.info("商品已修改：id={}", id);
    }

    private void doUpdateStatus(Long id, Integer status) {
        productMapper.update(null, Wrappers.<ProductEntity>lambdaUpdate()
                .eq(ProductEntity::getId, id)
                .set(ProductEntity::getStatus, status));
        log.info("商品状态已变更：id={}, status={}", id, status);
    }

    private void doDelete(Long id) {
        // 逻辑删除。注意这里**不删 OSS 上的图片**：
        // 订单快照可能引用了商品图，直接删文件会导致历史订单显示破图。
        // 图片清理应该由独立的、有引用的判断的离线任务来做，
        // 而不是在下行业务操作里顺手删——这是删除与归档混在一起时的经典错误。
        productMapper.deleteById(id);
        log.info("商品已被逻辑删除：id={}", id);
    }

    /**
     * 按 ID 取商品，不存在则 404。<b>不校验归属</b>，仅供平台范围与管理端使用。
     */
    private ProductEntity loadProductOrThrow(Long id) {
        ProductEntity entity = id == null ? null : productMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(404, "商品不存在");
        }
        return entity;
    }

    /**
     * 按 ID 取商品，并确认它归属该卖家。
     *
     * <p>归属不符时返回 <b>404 而不是 403</b>：403 等于承认"这个 id 确实存在"，
     * 卖家可以据此把 id 遍历一遍，数出全平台有多少商品、哪些 id 是有效的。
     * 与前台「下架商品等同于不存在」是同一个思路 —— 不给出超出必要的信息。</p>
     */
    private ProductEntity requireOwnedProduct(Long sellerId, Long id) {
        ProductEntity entity = loadProductOrThrow(id);
        if (!sellerId.equals(entity.getSellerId())) {
            log.warn("卖家尝试操作不属于自己的商品：sellerId={}, productId={}, actualOwner={}",
                    sellerId, id, entity.getSellerId());
            throw new BusinessException(404, "商品不存在");
        }
        return entity;
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "状态值只能是 0（下架）或 1（上架）");
        }
    }

    /**
     * 原价与售价的关系校验。
     *
     * <p>{@code @DecimalMin} 这类注解只能做单字段校验，跨字段比较必须写在业务层。
     * 放任 originalPrice &lt; price 会让前端渲染出「划线价比售价还便宜」这种事故。</p>
     */
    private void validatePriceRelation(ProductDTO dto) {
        if (dto.getOriginalPrice() != null && dto.getPrice() != null
                && dto.getOriginalPrice().compareTo(dto.getPrice()) < 0) {
            throw new BusinessException(400, "原价不能低于售价");
        }
    }

    /**
     * 把 DTO 的非受控字段搬到实体上。
     *
     * <p><b>刻意不在这里处理三个字段</b>，它们各自有专门的写入路径：</p>
     * <ul>
     *   <li>{@code status} —— 只能通过上下架接口改，避免"改个标题顺手把商品下架了"；</li>
     *   <li>{@code sales} —— 只能由下单流程累加，前端写入等于伪造销量；</li>
     *   <li>{@code sellerId} —— 只能由创建时的服务端逻辑决定，防止商品被过户。</li>
     * </ul>
     *
     * <p>（历史遗留说明：本方法曾经在 {@code dto.status != null} 时顺手写 status，
     * 与 {@code doUpdate} 的注释"status 不在这里设置"自相矛盾 —— 注释描述的才是
     * 设计意图，代码是错的。现已按注释修正。）</p>
     */
    private void applyDto(ProductEntity entity, ProductDTO dto) {
        entity.setName(dto.getName().trim());
        entity.setSubtitle(trimToNull(dto.getSubtitle()));
        entity.setCategoryId(dto.getCategoryId());
        entity.setPrice(dto.getPrice());
        entity.setOriginalPrice(dto.getOriginalPrice());
        entity.setStock(dto.getStock());
        entity.setCoverKey(trimToNull(dto.getCoverKey()));
        entity.setImages(dto.getImages() == null || dto.getImages().isEmpty() ? null : dto.getImages());
        entity.setDescription(dto.getDescription());
        entity.setSort(dto.getSort());
    }

    private PageVO<ProductVO> toPageVO(IPage<ProductEntity> page) {
        List<ProductVO> records = productVoFactory.createList(page.getRecords());
        return new PageVO<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    private long normalizePageNum(long pageNum) {
        return Math.max(pageNum, 1);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
