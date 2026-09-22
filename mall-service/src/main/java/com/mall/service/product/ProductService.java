package com.mall.service.product;

import com.mall.api.dto.ProductDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ProductVO;

/**
 * 商品服务。
 *
 * <p>接口被刻意分成三组：<b>前台只读</b>、<b>管理端读写</b>、<b>卖家侧读写</b>。
 * 这个划分不是形式主义 —— 前台查询必须强制过滤 {@code status = 1}，
 * 管理端查询则要能看见下架商品，卖家侧还要在两者之上再叠一层
 * 「只看自己名下」的归属过滤。三种查询的权限范围、缓存策略、限流阈值全都不同，
 * 混在一个方法里迟早会写出「前台看到下架商品」或「卖家改到别人的货」这类事故。</p>
 *
 * <h3>为什么卖家侧不复用管理端方法 + 一个可空的 sellerId 参数</h3>
 *
 * <p>看起来 {@code pageProducts(..., Long sellerId)} 传 {@code null} 表示全平台
 * 会更省代码，但那等于把「范围」做成了一个可空参数 —— 任何一个调用方漏传、
 * 或者某个重构把值弄丢，方法就<b>静默地</b>从"只看我的"变成"看全部"，
 * 不报错、不告警，只是数据泄露了。安全相关的默认值必须是"最窄"的那一个。</p>
 *
 * <p>因此这里不暴露可空范围参数：卖家侧的每个方法签名里都明确带着
 * {@code sellerId}，而管理端的同名方法内部传平台范围。共享逻辑放在
 * private 方法里，公开 API 保持各自的语义清晰。</p>
 */
public interface ProductService {

    // ==================================================================
    // 前台（公开，无需登录）
    // ==================================================================

    /**
     * 前台商品分页：只返回已上架商品。
     *
     * @param keyword    商品名模糊搜索，可为 null
     * @param categoryId 分类过滤，可为 null
     */
    PageVO<ProductVO> pageOnlineProducts(long pageNum, long pageSize, String keyword, Long categoryId);

    /**
     * 查询商品详情（前台）。下架或不存在的商品统一返回 404，
     * 不区分「不存在」和「已下架」—— 避免泄露商品是否曾经存在。
     */
    ProductVO getProductById(Long id);

    // ==================================================================
    // 管理端（需要 product:* 权限）
    // ==================================================================

    /**
     * 管理端商品分页：包含下架商品，支持按状态过滤。
     */
    PageVO<ProductVO> pageAdminProducts(long pageNum, long pageSize, String keyword, Integer status, Long categoryId);

    /**
     * 管理端商品详情：下架商品也要能查到。
     *
     * <p>不能复用 {@link #getProductById(Long)} —— 那个方法强制带
     * {@code status = 1}，管理端打开一个下架商品的编辑页会直接 404，
     * 变成「永远改不回上架」的死锁。</p>
     */
    ProductVO getProductForAdmin(Long id);

    /**
     * 新增商品。
     *
     * @return 新商品 ID
     */
    Long createProduct(ProductDTO dto);

    /**
     * 修改商品。
     */
    void updateProduct(Long id, ProductDTO dto);

    /**
     * 上架 / 下架。
     *
     * @param status 0 下架，1 上架
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除商品（逻辑删除）。
     */
    void deleteProduct(Long id);

    // ==================================================================
    // 卖家侧（需要 seller:product:* 权限，且范围被锁定为「自己名下」）
    //
    // 每个方法的 sellerId 都由 Controller 从令牌里的当前用户取得，
    // 绝不接受请求参数传入 —— 否则卖家只要改一个 id 就能操作别人的商品。
    // ==================================================================

    /**
     * 卖家商品分页：只返回 {@code seller_id = sellerId} 的商品。
     *
     * @param status 状态过滤，null 表示全部（含下架）
     */
    PageVO<ProductVO> pageSellerProducts(Long sellerId, long pageNum, long pageSize,
                                        String keyword, Integer status);

    /**
     * 卖家查看自己的商品详情。
     *
     * <p>商品不属于该卖家时返回 404 而不是 403：403 等于承认"这个 id 确实存在"，
     * 卖家可以据此遍历出全平台的商品规模。与前台"下架商品等同于不存在"
     * 是同一个思路。</p>
     */
    ProductVO getSellerProduct(Long sellerId, Long id);

    /**
     * 卖家新增商品。{@code seller_id} 由服务端写入，不取客户端值。
     *
     * @return 新商品 ID
     */
    Long createSellerProduct(Long sellerId, ProductDTO dto);

    /**
     * 卖家修改自己的商品。归属校验在服务层完成。
     */
    void updateSellerProduct(Long sellerId, Long id, ProductDTO dto);

    /**
     * 卖家上架 / 下架自己的商品。
     */
    void updateSellerProductStatus(Long sellerId, Long id, Integer status);

    /**
     * 卖家删除自己的商品（逻辑删除）。
     */
    void deleteSellerProduct(Long sellerId, Long id);
}
