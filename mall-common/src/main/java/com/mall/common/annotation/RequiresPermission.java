package com.mall.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限校验注解。
 *
 * <p>用法：</p>
 * <pre>{@code
 * @RequiresPermission("user:delete")
 * public Result<Void> delete(@PathVariable Long id) { ... }
 *
 * // 满足任意一个即可
 * @RequiresPermission(value = {"user:list", "user:detail"}, logical = Logical.OR)
 * }</pre>
 *
 * <p>校验逻辑由 {@code PermissionAspect} 实现。注解放在方法上生效于该方法，
 * 放在类上则对该类所有方法生效（方法上的注解优先级更高）。</p>
 *
 * <p><b>为什么不用 Spring Security 的 {@code @PreAuthorize}？</b>
 * 那个注解依赖 {@code ROLE_xxx} 字符串和 SpEL，权限来源被绑死在 Security 的
 * Authentication 上。本项目的权限存在 Redis，且后续要拆微服务（网关统一鉴权），
 * 自研一个只认「权限编码」的注解更容易迁移。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** 需要的权限编码，如 {@code user:delete} */
    String[] value();

    /** 多个权限之间的组合关系，默认全部满足 */
    Logical logical() default Logical.AND;

    enum Logical {
        /** 必须同时拥有全部权限 */
        AND,
        /** 拥有其中任意一个即可 */
        OR
    }
}
