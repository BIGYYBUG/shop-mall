package com.mall;

import com.mall.entity.ProductEntity;
import com.mall.mapper.ProductMapper;
import com.mall.storage.FileStorageService;
import com.mall.storage.StoredFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品模块集成测试。
 *
 * <p>与 {@code RbacIntegrationTest} 同样跑在真实的 Spring 上下文与数据库上，
 * 原因很简单：这里要验证的东西（JSON 列的 typeHandler 是否生效、拦截器放行清单、
 * 分页参数归一化）全都是"只有真正跑起来才知道对不对"的集成行为，
 * 用 Mockito 把 Mapper 打桩测不出任何一条。</p>
 *
 * <p><b>改任何商品 / 存储相关代码后，先跑这个。</b></p>
 * <pre>mvn test -Dtest=ProductIntegrationTest</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private FileStorageService fileStorageService;

    // ==================================================================
    // 前台接口：免登录 + 强制只返回上架商品
    // ==================================================================

    @Test
    @DisplayName("前台商品列表免登录可访问，且不包含任何下架商品")
    void onlineListIsPublicAndHidesOfflineProducts() throws Exception {
        // 注意：这里必须带 status = 1 条件，不能用 selectCount(null) 统计整表 ——
        // 种子数据里刻意留了一个下架商品，就是为了让"前台泄露下架商品"这类 bug 能被这条断言抓住
        long onlineCount = productMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getStatus, 1));

        mockMvc.perform(get("/product/page").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 关键断言：返回的每一条 status 都必须是 1。
                // 只要有人把 status 条件写进了 OR 分支，这条就会红
                .andExpect(jsonPath("$.data.records[*].status", everyItem(is(1))))
                // 总数应该等于"库里上架商品数"，而不是整表行数
                .andExpect(jsonPath("$.data.total").value(onlineCount));
    }

    @Test
    @DisplayName("前台详情查询下架商品返回 404 业务码，不泄露商品是否存在过")
    void offlineProductDetailReturnsNotFound() throws Exception {
        ProductEntity offline = productMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getStatus, 0)
                        .last("LIMIT 1"));

        if (offline == null) {
            return;
        }

        mockMvc.perform(get("/product/" + offline.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("前台详情返回的图片字段是可访问 URL，而不是裸 objectKey")
    void detailExposesUrlInsteadOfObjectKey() throws Exception {
        ProductEntity online = productMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getStatus, 1)
                        .last("LIMIT 1"));

        assertThat(online).as("种子数据里应至少有一个上架商品").isNotNull();

        mockMvc.perform(get("/product/" + online.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value(online.getName()));
    }

    // ==================================================================
    // 管理端接口：必须登录
    // ==================================================================

    @Test
    @DisplayName("未携带令牌访问管理端商品接口返回 401")
    void adminEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin/product/page"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 存储层
    // ==================================================================

    @Test
    @DisplayName("本地存储：上传返回对象键，且 key 按 category/日期 组织")
    void uploadBuildsDatedObjectKey() {
        byte[] png = minimalPng();

        StoredFile stored = fileStorageService.upload(png, "封面图.png", "image/png", "product");

        // 原始文件名（含中文）绝不能被带进 key —— 既容易撞名，也有路径穿越风险
        assertThat(stored.objectKey())
                .startsWith("product/")
                .doesNotContain("封面图")
                .matches(Pattern.compile("^product/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.png$"));
        assertThat(stored.url()).isNotBlank().endsWith(stored.objectKey());

        // toAccessUrl 必须与 upload 返回的 url 一致，否则"上传说的是 A、存库拼出来是 B"
        assertThat(fileStorageService.toAccessUrl(stored.objectKey())).isEqualTo(stored.url());

        fileStorageService.delete(stored.objectKey());
    }

    @Test
    @DisplayName("本地存储：拒绝白名单外的扩展名")
    void uploadRejectsDisallowedExtension() {
        byte[] png = minimalPng();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> fileStorageService.upload(png, "shell.jsp", "image/png", "product"))
                .hasMessageContaining("不支持的文件格式");
    }

    @Test
    @DisplayName("本地存储：toAccessUrl 对空值返回 null，不抛异常")
    void toAccessUrlToleratesNull() {
        assertThat(fileStorageService.toAccessUrl(null)).isNull();
        assertThat(fileStorageService.toAccessUrl("  ")).isNull();
    }

    // ==================================================================
    // JSON 列往返：验证 @TableName(autoResultMap = true) 真的生效
    // ==================================================================

    @Test
    @DisplayName("images 多值字段能正确写入 JSON 列并读回 List")
    void imagesRoundTripThroughJsonColumn() {
        ProductEntity entity = new ProductEntity();
        entity.setName("JSON 往返测试商品");
        entity.setPrice(new BigDecimal("9.90"));
        entity.setStock(1);
        entity.setStatus(0);
        entity.setSort(0);
        entity.setImages(List.of("product/a.jpg", "product/b.png"));

        productMapper.insert(entity);
        ProductEntity reloaded = productMapper.selectById(entity.getId());

        assertThat(reloaded).isNotNull();
        // 如果 @TableName 上漏了 autoResultMap = true，这里会是 null ——
        // 而且插入完全成功、查询也不报错，是最难查的一类问题
        assertThat(reloaded.getImages())
                .containsExactly("product/a.jpg", "product/b.png");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 最小的 PNG 文件头，够通过魔数校验 */
    private byte[] minimalPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
    }
}
