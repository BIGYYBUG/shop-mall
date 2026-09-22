package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 店铺 VO。
 */
@Data
public class ShopVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 店主用户 ID */
    private Long userId;

    /**
     * 店主登录名。
     *
     * <p>仅管理端列表填充。审核界面若只显示 {@code userId} 就没法用了 ——
     * 审核员需要知道"这是谁在申请"。由服务层批量查出来传进工厂，
     * 而不是在 VO 里让前端再请求一次用户接口。</p>
     */
    private String ownerUsername;

    private String name;

    /** 店铺 Logo 的完整访问地址。库里的 logoKey 已在装配时拼好，前端拿不到裸 key */
    private String logoUrl;

    private String description;

    private String contactPhone;

    /** 状态：0 待审核，1 正常，2 已驳回，3 已冻结 */
    private Integer status;

    /**
     * 状态的中文描述。
     *
     * <p>由服务端给出而不是让前端自己 switch：状态的中文说法是业务语义，
     * 前端各页面各写一份映射，等文案调整时必然改漏一处。
     * 这里多一个字段，换来的是文案只有一处真相。</p>
     */
    private String statusText;

    /** 驳回原因，仅 status = 2 时有值 */
    private String rejectReason;

    private LocalDateTime createTime;
}
