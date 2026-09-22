package com.mall.mapper.model;

import lombok.Data;

/**
 * 跨表查询的投影对象：一个用户对应一条角色编码。
 *
 * <p>不是数据库表的映射，而是 JOIN 查询结果的载体，所以不继承 BaseEntity、
 * 也不加 {@code @TableName}。</p>
 */
@Data
public class UserRoleCode {

    private Long userId;

    private String roleCode;
}
