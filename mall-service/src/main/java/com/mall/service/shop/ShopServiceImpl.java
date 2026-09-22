package com.mall.service.shop;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.api.dto.ShopAuditDTO;
import com.mall.api.dto.ShopDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ShopVO;
import com.mall.common.exception.BusinessException;
import com.mall.convert.shop.ShopVoFactory;
import com.mall.entity.ProductEntity;
import com.mall.entity.ShopEntity;
import com.mall.entity.ShopStatus;
import com.mall.entity.UserEntity;
import com.mall.mapper.ProductMapper;
import com.mall.mapper.ShopMapper;
import com.mall.mapper.UserMapper;
import com.mall.service.rbac.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mall.common.util.TextUtils.trimToNull;

/**
 * 店铺服务实现。
 *
 * <p>核心不变量见 {@link ShopService} 类注释：{@code SELLER 角色 ⟺ shop.status == 1}。
 * 本类里所有改变 {@code status} 的地方，都必须同步调整 SELLER 角色。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    /** 分页单页上限，与用户/商品分页保持同一口径 */
    private static final long MAX_PAGE_SIZE = 100;

    private final ShopMapper shopMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final RoleService roleService;
    /** VO 装配统一走工厂 */
    private final ShopVoFactory shopVoFactory;

    // ==================================================================
    // 卖家侧
    // ==================================================================

    @Override
    public ShopVO getMyShop(Long userId) {
        return shopVoFactory.create(requireShop(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long applyShop(Long userId, ShopDTO dto) {
        ShopEntity existing = findShopByUserId(userId, false);

        if (existing != null) {
            int status = existing.getStatus() == null ? -1 : existing.getStatus();

            if (status == ShopStatus.PENDING) {
                throw new BusinessException(400, "入驻申请已提交，请等待平台审核");
            }
            if (status == ShopStatus.ACTIVE) {
                throw new BusinessException(400, "店铺已在经营中，如需调整资料请使用店铺信息修改");
            }
            if (status == ShopStatus.FROZEN) {
                throw new BusinessException(400, "店铺已被冻结，请联系平台处理后再提交");
            }
            if (status != ShopStatus.REJECTED) {
                throw new BusinessException(400, "店铺状态异常，请联系平台处理");
            }

            // 走到这里必然是「已驳回」—— 允许改资料后重新提交
            shopMapper.update(null, Wrappers.<ShopEntity>lambdaUpdate()
                    .eq(ShopEntity::getId, existing.getId())
                    .set(ShopEntity::getName, dto.name().trim())
                    .set(ShopEntity::getLogoKey, trimToNull(dto.logoKey()))
                    .set(ShopEntity::getDescription, trimToNull(dto.description()))
                    .set(ShopEntity::getContactPhone, trimToNull(dto.contactPhone()))
                    .set(ShopEntity::getStatus, ShopStatus.PENDING)
                    // 关键：把上一次的驳回原因清掉。
                    // 不清的话，重新提交后界面上仍挂着旧理由，店主会以为没提交成功。
                    .set(ShopEntity::getRejectReason, null));

            log.info("店铺已重新提交审核：shopId={}, userId={}", existing.getId(), userId);
            return existing.getId();
        }

        ShopEntity entity = new ShopEntity();
        entity.setUserId(userId);
        applyDto(entity, dto);
        // 状态由服务端决定，绝不接受客户端传入
        entity.setStatus(ShopStatus.PENDING);

        shopMapper.insert(entity);
        log.info("店铺入驻申请已提交：shopId={}, userId={}", entity.getId(), userId);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMyShop(Long userId, ShopDTO dto) {
        ShopEntity shop = requireShop(userId);

        if (shop.getStatus() != null && shop.getStatus() == ShopStatus.FROZEN) {
            throw new BusinessException(400, "店铺已被冻结，暂时无法修改资料");
        }

        // 用 lambdaUpdate + set 而非 updateById：后者忽略 null 字段，
        // 店主把简介清空时不会被写成 null，会留下删不掉的旧内容
        shopMapper.update(null, Wrappers.<ShopEntity>lambdaUpdate()
                .eq(ShopEntity::getId, shop.getId())
                .set(ShopEntity::getName, dto.name().trim())
                .set(ShopEntity::getLogoKey, trimToNull(dto.logoKey()))
                .set(ShopEntity::getDescription, trimToNull(dto.description()))
                .set(ShopEntity::getContactPhone, trimToNull(dto.contactPhone())));

        // 刻意不动 status：改个店铺简介不该让生意停下来重新审核。
        // 只有「入驻」需要资质审核，日常资料维护不需要。
        log.info("店铺资料已更新：shopId={}, userId={}", shop.getId(), userId);
    }

    @Override
    public ShopEntity requireShop(Long userId) {
        return findShopByUserId(userId, true);
    }

    @Override
    public ShopEntity requireOperableShop(Long userId) {
        ShopEntity shop = findShopByUserId(userId, true);
        if (!ShopStatus.canOperate(shop.getStatus())) {
            throw new BusinessException(403, "店铺当前状态为「"
                    + ShopStatus.describe(shop.getStatus()) + "」，暂时无法管理商品");
        }
        return shop;
    }

    // ==================================================================
    // 平台侧
    // ==================================================================

    @Override
    public PageVO<ShopVO> pageShops(long pageNum, long pageSize, String keyword, Integer status) {
        IPage<ShopEntity> page = shopMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)),
                Wrappers.<ShopEntity>lambdaQuery()
                        .eq(status != null, ShopEntity::getStatus, status)
                        .like(StringUtils.hasText(keyword), ShopEntity::getName, keyword)
                        // 按状态升序 = 待审核(0) 排最前。
                        // 审核界面的核心诉求是「把积压的申请清掉」，若按 id 倒序，
                        // 新提交的申请会被埋在已通过的记录下面。
                        // ⚠ 这个排序是业务优先级驱动的，不是索引顺序 —— mall_shop 只有
                        //   idx_status(status) 单列索引，ORDER BY 会走一次 filesort。
                        //   在店铺这种量级极小的表上完全可接受，不值得为它加复合索引。
                        .orderByAsc(ShopEntity::getStatus)
                        .orderByDesc(ShopEntity::getId));

        // 批量补店主用户名。逐行查用户表就是标准的 N+1 ——
        // 一页 20 家店铺会变成 21 次数据库往返
        Map<Long, String> ownerNames = loadOwnerNames(page.getRecords());
        List<ShopVO> records = page.getRecords().stream()
                .map(shop -> shopVoFactory.create(shop, ownerNames.get(shop.getUserId())))
                .toList();

        return new PageVO<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public ShopVO getShopForAdmin(Long id) {
        ShopEntity shop = id == null ? null : shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        String ownerName = shop.getUserId() == null
                ? null
                : loadOwnerNames(List.of(shop)).get(shop.getUserId());
        return shopVoFactory.create(shop, ownerName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShopForAdmin(Long id, ShopDTO dto) {
        ShopEntity shop = id == null ? null : shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(404, "店铺不存在");
        }

        // 与店主自助修改共用同一组可改字段；刻意不受"冻结不可改"限制 ——
        // 冻结期间正是平台需要更正店铺信息的时候。
        // status / userId 都不在可改范围内：前者属于审核流程，后者决定归属。
        shopMapper.update(null, Wrappers.<ShopEntity>lambdaUpdate()
                .eq(ShopEntity::getId, id)
                .set(ShopEntity::getName, dto.name().trim())
                .set(ShopEntity::getLogoKey, trimToNull(dto.logoKey()))
                .set(ShopEntity::getDescription, trimToNull(dto.description()))
                .set(ShopEntity::getContactPhone, trimToNull(dto.contactPhone())));

        log.info("平台已修改店铺资料：shopId={}, userId={}", id, shop.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditShop(Long id, ShopAuditDTO dto) {
        ShopEntity shop = id == null ? null : shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(404, "店铺不存在");
        }

        int target = dto.status();
        if (target == ShopStatus.PENDING) {
            // 契约上已被 @Min(1) 挡住，这里是纵深防御：审核动作的意义就是离开"待审核"
            throw new BusinessException(400, "审核结果不能是「待审核」");
        }

        String reason = trimToNull(dto.rejectReason());
        if (target == ShopStatus.REJECTED && reason == null) {
            // 跨字段规则，@NotNull 表达不了，只能在这里校验
            throw new BusinessException(400, "驳回时必须填写驳回原因，否则店主不知道要改什么");
        }

        shopMapper.update(null, Wrappers.<ShopEntity>lambdaUpdate()
                .eq(ShopEntity::getId, id)
                .set(ShopEntity::getStatus, target)
                // 只有驳回才保留原因；通过 / 冻结时清空，
                // 否则界面上会一直显示上一次的驳回理由，误导店主
                .set(ShopEntity::getRejectReason, target == ShopStatus.REJECTED ? reason : null));

        Long ownerId = shop.getUserId();

        // 不变量维护：status 与 SELLER 角色必须同步。
        // 少了这一步，会出现「店铺已被冻结，店主却还能改商品」这种故障 ——
        // 因为权限码还留在他手里，光看权限查不出任何异常。
        if (target == ShopStatus.ACTIVE) {
            roleService.grantRole(ownerId, RoleService.SELLER_ROLE_CODE);
        } else {
            roleService.revokeRole(ownerId, RoleService.SELLER_ROLE_CODE);
        }

        if (target == ShopStatus.FROZEN) {
            takeProductsOffline(ownerId);
        }

        log.info("店铺审核完成：shopId={}, userId={}, result={}",
                id, ownerId, ShopStatus.describe(target));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 按用户 ID 查店铺。
     *
     * @param required 为 true 时查不到直接抛 404；为 false 时返回 null，
     *                 供「申请入驻前先看看有没有店铺」这类判断使用
     */
    private ShopEntity findShopByUserId(Long userId, boolean required) {
        ShopEntity shop = userId == null ? null : shopMapper.selectOne(
                Wrappers.<ShopEntity>lambdaQuery().eq(ShopEntity::getUserId, userId));
        if (shop == null && required) {
            throw new BusinessException(404, "尚未申请入驻，请先提交开店申请");
        }
        return shop;
    }

    private void applyDto(ShopEntity entity, ShopDTO dto) {
        entity.setName(dto.name().trim());
        entity.setLogoKey(trimToNull(dto.logoKey()));
        entity.setDescription(trimToNull(dto.description()));
        entity.setContactPhone(trimToNull(dto.contactPhone()));
    }

    /**
     * 冻结店铺时下架其名下所有商品。
     *
     * <p><b>为什么必须做</b>：只改店铺状态的话，被冻结店主的商品仍然挂在前台
     * 正常售卖 —— 平台冻结了店铺却还在替它出货，属于严重的业务漏洞。</p>
     *
     * <p><b>解冻时为什么不自动恢复上架</b>：批量恢复意味着平台替店主做了
     * 「哪些该卖」的决定，而冻结期间的情况可能已经变了（断货、涨价）。
     * 让店主自己重新上架，是可解释且更安全的选择。</p>
     */
    private void takeProductsOffline(Long sellerId) {
        int affected = productMapper.update(null, Wrappers.<ProductEntity>lambdaUpdate()
                .eq(ProductEntity::getSellerId, sellerId)
                .eq(ProductEntity::getStatus, 1)
                .set(ProductEntity::getStatus, 0));
        if (affected > 0) {
            log.info("店铺被冻结，已下架其名下 {} 个在售商品：sellerId={}", affected, sellerId);
        }
    }

    /**
     * 批量查店主登录名，返回 userId → username。
     */
    private Map<Long, String> loadOwnerNames(List<ShopEntity> shops) {
        if (shops == null || shops.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = shops.stream()
                .map(ShopEntity::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        UserEntity::getUsername,
                        // 主键唯一，理论上不会有重复键；写上合并函数只是为了让
                        // toMap 不在运行期因意外重复而抛 IllegalStateException
                        (a, b) -> a));
    }
}
