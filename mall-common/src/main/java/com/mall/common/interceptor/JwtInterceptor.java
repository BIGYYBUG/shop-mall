package com.mall.common.interceptor;

import com.mall.common.context.UserContext;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器。
 *
 * <p>职责：从请求头解析令牌 → 校验 → 把「用户 ID + 角色」放进 UserContext。</p>
 *
 * <p>执行顺序：preHandle（请求进入 Controller 前）→ Controller →
 * postHandle → afterCompletion（请求结束，在此清理 ThreadLocal）。</p>
 *
 * <p><b>注意</b>：拦截器只做「认得出你是谁」（认证 Authentication），
 * 「你能不能干这事」（授权 Authorization）由 {@code PermissionAspect} 负责。
 * 两件事分开，才能让认证对所有接口生效、而授权只作用于需要权限的接口。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    /** 令牌请求头名称，标准写法为 Authorization: Bearer &lt;token&gt; */
    private static final String AUTH_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行浏览器的预检请求（CORS 会先发一个 OPTIONS 探路）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "未登录或令牌缺失");
            return false;
        }

        // 去掉 "Bearer " 前缀，拿到真正的令牌
        String token = header.substring(BEARER_PREFIX.length()).trim();

        // 只解析一次：解析是有成本的（验签），后续都复用这份 Claims
        Claims claims = jwtUtil.parseToken(token);
        Long userId = jwtUtil.getUserId(claims);
        if (userId == null) {
            writeUnauthorized(response, "令牌无效或已过期");
            return false;
        }

        UserContext.setUserId(userId);
        UserContext.setRoles(jwtUtil.extractRoles(claims));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束必须清理，否则线程复用时会把上一个用户的身份带给下一个请求
        UserContext.clear();
    }

    /**
     * 统一输出 401 响应体，保持与 Result 结构一致
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
