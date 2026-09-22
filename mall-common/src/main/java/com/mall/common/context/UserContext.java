package com.mall.common.context;

import java.util.Collections;
import java.util.List;

/**
 * 当前登录用户上下文。
 *
 * <p>原理：Tomcat 用「一个线程处理一个请求」，所以在拦截器里把身份信息存进
 * ThreadLocal，后续的 Service / Aspect 层就能直接取到，不必层层传参。</p>
 *
 * <p>这里存两类数据，职责不同：</p>
 * <ul>
 *   <li><b>userId</b>：唯一可信身份来源，用于查权限、查数据</li>
 *   <li><b>roles</b>：角色编码，来自 JWT，仅用于展示或粗粒度判断。
 *       真正的权限判定一律走 Redis 中的权限集合，不要用角色代替权限</li>
 * </ul>
 *
 * <p><b>必须配对使用</b>：set 之后一定要在请求结束时 clear，
 * 否则线程被复用时会串数据（A 用户的请求读到 B 用户的身份）。</p>
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    private UserContext() {
    }

    /** 存入当前登录用户 ID */
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /** 取出当前登录用户 ID，未登录时为 null */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 存入当前登录用户的角色编码 */
    public static void setRoles(List<String> roles) {
        ROLES.set(roles);
    }

    /** 取出角色编码，未登录时返回空集合而非 null，调用方无需判空 */
    public static List<String> getRoles() {
        List<String> roles = ROLES.get();
        return roles == null ? Collections.emptyList() : roles;
    }

    /** 清理，防止线程复用导致数据串号 */
    public static void clear() {
        USER_ID.remove();
        ROLES.remove();
    }
}
