package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户 VO：出参对象。
 *
 * <p><b>为什么不直接返回 UserEntity？</b>实体是数据库结构的镜像，一旦包含
 * password、deleted 这类字段，返回给前端就是数据泄漏 + 表结构耦合。
 * VO 只暴露「前端需要且允许看到」的字段，两者解耦。</p>
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String email;

    private String phone;

    private String nickname;

    private String avatar;

    /** 状态：0 禁用，1 正常。管理端需要，普通用户看到也无害 */
    private Integer status;

    /** 角色编码列表，来自 mall_user_role 关联表 */
    private List<String> roles;

    private LocalDateTime createTime;
}
