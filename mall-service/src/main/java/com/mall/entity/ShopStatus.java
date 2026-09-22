package com.mall.entity;

/**
 * 店铺状态常量。
 *
 * <p>用常量的理由很直白：{@code shop.getStatus() == 1} 这种代码，三个月后
 * 没人记得 1 是"正常"还是"已冻结"。而 {@code ShopStatus.ACTIVE} 不需要注释。</p>
 *
 * <p><b>为什么不做成枚举</b>：数据库列是 {@code TINYINT}，用枚举就得加
 * MyBatis-Plus 的 {@code @EnumValue} 映射，还要在 DTO/VO 各写一次转换。
 * 对四个状态来说，常量 + 一处 {@link #isValid} 校验的性价比更高。
 * 如果状态机继续膨胀（加"审核中"、"封禁申诉中"等），再升级成枚举不迟。</p>
 */
public final class ShopStatus {

    private ShopStatus() {
    }

    /** 待审核：入驻申请已提交，等平台处理。此状态下不能管理商品 */
    public static final int PENDING = 0;

    /** 正常：审核通过，可正常经营 */
    public static final int ACTIVE = 1;

    /** 已驳回：附 {@code rejectReason}，店主可修改后重新提交 */
    public static final int REJECTED = 2;

    /** 已冻结：违规被平台关停，可恢复 */
    public static final int FROZEN = 3;

    /**
     * 状态值是否合法。
     *
     * <p>用于接口入参校验 —— 商品上下架接口曾经写死 {@code status != 0 && status != 1}，
     * 这种散落的字面量判断是"加了一个新状态但漏改某处"的温床，
     * 统一收在这里。</p>
     */
    public static boolean isValid(Integer status) {
        return status != null
                && (status == PENDING || status == ACTIVE || status == REJECTED || status == FROZEN);
    }

    /** 是否可开展经营活动（唯一允许管理商品的状态） */
    public static boolean canOperate(Integer status) {
        return status != null && status == ACTIVE;
    }

    /** 状态的中文描述，用于拼装错误信息 */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "待审核";
            case ACTIVE -> "正常";
            case REJECTED -> "已驳回";
            case FROZEN -> "已冻结";
            default -> "未知(" + status + ")";
        };
    }
}
