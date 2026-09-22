package com.mall.common.aspect;

import com.mall.common.annotation.RequiresPermission;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.spi.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * 权限校验切面：拦截所有标注 {@link RequiresPermission} 的方法。
 *
 * <p><b>执行时机</b>：@Around 在目标方法前后都能插入逻辑。这里只做前置校验，
 * 校验通过后放行 proceed()，异常则直接抛出，目标方法根本不会被执行。</p>
 *
 * <p><b>为什么是 AOP 而不是在 Controller 里手写 if？</b>
 * 权限判断是典型的横切关注点：它散落在几十个接口里，但逻辑完全一致。写在切面里，
 * 业务方法保持干净，加权限只是加一行注解。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    /**
     * 用 ObjectProvider 而不是直接注入：避免 mall-common 在缺少实现时启动即失败，
     * 也便于将来同一个 common 被多个应用复用时做优雅降级。
     */
    private final ObjectProvider<PermissionChecker> permissionCheckerProvider;

    /**
     * 切点说明：
     * <ul>
     *   <li>{@code @annotation(...)}：匹配方法上标注了该注解的方法</li>
     *   <li>{@code @within(...)}：匹配类上标注了该注解的类中的所有方法</li>
     * </ul>
     */
    @Around("@annotation(com.mall.common.annotation.RequiresPermission) "
            + "|| @within(com.mall.common.annotation.RequiresPermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        RequiresPermission annotation = resolveAnnotation(joinPoint);
        if (annotation == null) {
            return joinPoint.proceed();
        }

        // ① 必须已登录。身份只有 UserContext 一个来源，它由 JwtInterceptor 写入
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        // ② 拿到权限查询器（实现方在 mall-service）
        PermissionChecker checker = permissionCheckerProvider.getIfAvailable();
        if (checker == null) {
            throw new BusinessException(500, "权限校验器未装配，请检查 PermissionChecker 是否已有实现类");
        }

        // ③ 比对权限
        Set<String> owned = checker.getPermissions(userId);
        String[] required = annotation.value();
        boolean pass = annotation.logical() == RequiresPermission.Logical.OR
                ? Arrays.stream(required).anyMatch(owned::contains)
                : Arrays.stream(required).allMatch(owned::contains);

        if (!pass) {
            String separator = annotation.logical() == RequiresPermission.Logical.OR ? " 或 " : " 且 ";
            String joined = String.join(separator, required);
            log.warn("权限校验未通过：userId={}，需要=[{}]，实际持有={}", userId, joined, owned);
            throw new BusinessException(403, "权限不足，需要权限：" + joined);
        }

        return joinPoint.proceed();
    }

    /**
     * 解析注解：先找方法上的，再找类上的（方法级优先）。
     *
     * <p>用 {@code AnnotatedElementUtils.findMergedAnnotation} 而不是
     * {@code getAnnotation}，前者会沿父类和接口向上查找，兼容性更好。</p>
     */
    private RequiresPermission resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        RequiresPermission annotation =
                AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (annotation != null) {
            return annotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(),
                RequiresPermission.class);
    }
}
