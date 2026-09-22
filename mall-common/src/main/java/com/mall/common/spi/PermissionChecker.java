package com.mall.common.spi;

import java.util.Set;

/**
 * 权限查询能力接口（SPI）。
 *
 * <p><b>为什么在 mall-common 定义接口、却在 mall-service 实现？</b></p>
 *
 * <p>因为切面 {@code PermissionAspect} 住在 mall-common，而权限数据源（Mapper、Redis）
 * 住在 mall-service。Maven 的依赖方向是 service → common，common <b>不能反向依赖</b>
 * service，否则循环依赖。</p>
 *
 * <p>解法就是「依赖倒置」：common 只声明"我需要有人能按 userId 查出权限"这个契约，
 * 具体谁来查由 service 实现。这样：</p>
 * <ul>
 *   <li>依赖方向保持单向：common ← service</li>
 *   <li>将来权限数据源换成别的（缓存中间件、远程调用、甚至另一个微服务），
 *       common 一行都不用改</li>
 * </ul>
 *
 * <p>这正是后面拆微服务时 {@code mall-api} 定义 Feign 契约、各服务去实现的同一套思路。</p>
 */
public interface PermissionChecker {

    /**
     * 查询用户拥有的权限编码集合
     *
     * @param userId 用户 ID
     * @return 权限编码集合，永不为 null；无权限时返回空集合
     */
    Set<String> getPermissions(Long userId);

    /**
     * 查询用户拥有的角色编码集合
     *
     * @param userId 用户 ID
     * @return 角色编码集合，永不为 null
     */
    Set<String> getRoleCodes(Long userId);
}
