package com.mall.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-角色关联实体，对应表 mall_user_role。
 *
 * <p><b>刻意不继承 BaseEntity</b>：本表只有 id / user_id / role_id / create_time，
 * 没有 update_time 和 deleted。若继承 BaseEntity，MyBatis-Plus 生成的 INSERT 会带上
 * 这两个列，数据库直接报 Unknown column。</p>
 *
 * <p><b>为什么关联表不要逻辑删除？</b>表上有唯一索引 {@code uk_user_role(user_id, role_id)}。
 * 如果取消授权不物理删除而是把 deleted 置 1，那么再次给同一个用户授同一个角色时，
 * 就会撞上唯一索引（因为旧的脏行还在）。所以关联表的关系解除一律物理删除。</p>
 */
@Data
@TableName("mall_user_role")
public class UserRoleEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
