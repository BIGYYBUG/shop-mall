package com.mall.service.shop;

import com.mall.api.dto.ShopAuditDTO;
import com.mall.api.dto.ShopDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ShopVO;
import com.mall.entity.ShopEntity;

/**
 * 店铺服务：入驻申请、资料维护、平台审核。
 *
 * <h3>本模块要守住的核心不变量</h3>
 *
 * <pre>
 *   SELLER 角色  ⟺  shop.status == 1（正常）
 * </pre>
 *
 * <p>这句话同时约束三件事：</p>
 * <ol>
 *   <li><b>审核通过</b> → 给店主授予 SELLER 角色（否则他有了店铺却没有 {@code seller:*} 权限）；</li>
 *   <li><b>驳回 / 冻结</b> → 收回 SELLER 角色（否则被冻结的人照样能改商品，
 *       因为权限码还在他手里，只是身份档案变了 —— 这正是"身份与权限必须联动"的体现）；</li>
 *   <li><b>管理商品前</b> → 校验店铺处于正常状态（{@link #requireOperableShop}）。</li>
 * </ol>
 *
 * <p>为什么把这条不变量写进接口注释而不是散在实现里：它跨越了 shop 与 rbac
 * 两个领域，任何一处漏改都会表现为"权限明明收回了却还能操作"，
 * 而这类问题在功能测试里极难发现。写在最显眼的位置，比埋在代码里更有用。</p>
 */
public interface ShopService {

    // ==================== 卖家侧 ====================

    /**
     * 查询当前用户自己的店铺。
     *
     * @param userId 用户 ID
     * @return 店铺 VO
     * @throws com.mall.common.exception.BusinessException 尚未入驻时抛 404
     */
    ShopVO getMyShop(Long userId);

    /**
     * 申请入驻。已有店铺时按状态区分处理：
     * <ul>
     *   <li>待审核 / 正常 → 拒绝（不重复受理）</li>
     *   <li>已驳回 → 允许修改资料后重新提交，状态回到待审核</li>
     *   <li>已冻结 → 拒绝（需平台解冻，店主改资料没有意义）</li>
     * </ul>
     *
     * @param userId 申请人用户 ID
     * @param dto    店铺资料
     * @return 店铺 ID
     */
    Long applyShop(Long userId, ShopDTO dto);

    /**
     * 店主修改自己的店铺资料。
     *
     * <p>不做重新审核 —— 只有"入驻"需要资质审核，改个店铺简介不该让生意停摆。
     * 但被冻结的店铺不允许修改。</p>
     *
     * @param userId 店主用户 ID
     * @param dto    店铺资料
     */
    void updateMyShop(Long userId, ShopDTO dto);

    /**
     * 校验「该用户当前可以管理自己的商品」，不通过直接抛异常。
     *
     * <p>这是卖家侧所有写操作的统一前置关卡。判断两件事：店铺存在，
     * 且状态为正常。仅靠 RBAC 的 {@code seller:product:*} 权限码是不够的 ——
     * 权限码只能说明"这个人是个卖家"，说明不了"他的店现在还在正常经营"。</p>
     *
     * @param userId 用户 ID
     * @return 校验通过的店铺实体，调用方可直接取用
     */
    ShopEntity requireOperableShop(Long userId);

    /**
     * 校验「该用户拥有店铺」（不论状态是否正常），用于卖家侧的读操作。
     *
     * <p>与 {@link #requireOperableShop} 的区别：被冻结的店主仍然应该能查看
     * 自己的商品列表（他得知道自己的货还在不在），只是不能改。</p>
     */
    ShopEntity requireShop(Long userId);

    // ==================== 平台侧 ====================

    /**
     * 店铺分页列表（管理端审核界面用）。
     *
     * @param status 状态过滤，null 表示全部
     */
    PageVO<ShopVO> pageShops(long pageNum, long pageSize, String keyword, Integer status);

    /**
     * 店铺详情（管理端，可按任意店铺 ID 查）
     */
    ShopVO getShopForAdmin(Long id);

    /**
     * 平台修改店铺资料。
     *
     * <p>与店主自助修改的区别：平台侧不受"店铺被冻结则不可修改"的限制 ——
     * 冻结期间往往正需要平台去更正店铺信息（比如把违规店名改掉）。</p>
     *
     * @param id  店铺 ID
     * @param dto 店铺资料
     */
    void updateShopForAdmin(Long id, ShopDTO dto);

    /**
     * 审核店铺。会同步调整店主的 SELLER 角色，并处理商品可见性：
     * <ul>
     *   <li>通过(1) → 授予 SELLER 角色；</li>
     *   <li>驳回(2) → 收回 SELLER 角色，{@code rejectReason} 必填；</li>
     *   <li>冻结(3) → 收回 SELLER 角色，并把该店铺名下所有商品下架。</li>
     * </ul>
     *
     * @param id  店铺 ID
     * @param dto 审核结果
     */
    void auditShop(Long id, ShopAuditDTO dto);
}
