package com.mall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌工具：负责签发与解析。
 *
 * <p>JWT 由三段组成，用点号分隔：Header.Payload.Signature</p>
 * <ul>
 *   <li>Header：声明签名算法（如 HS256）</li>
 *   <li>Payload：业务数据，如用户 ID、角色、过期时间。<b>只是 Base64 编码，
 *       不是加密，任何人都能解开</b></li>
 *   <li>Signature：用密钥对前两段做签名，防止篡改</li>
 * </ul>
 *
 * <p>因此：Payload 里绝对不能放密码、手机号等敏感信息。</p>
 *
 * <p><b>为什么角色放进令牌，权限却不放？</b>令牌一旦签发，在过期前无法撤销。
 * 角色是低频变更的（一般用户的角色几个月才动一次），放进去风险可控；
 * 权限是高频可调的（给某个角色加一个权限应立即生效），必须存在 Redis 里
 * 支持随时失效 —— 这就是「角色入令牌 + 权限进 Redis」的取舍逻辑。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    /** 自定义 claim 键名 */
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";

    /** 签名密钥，HS256 要求至少 256 位（32 字节） */
    private final SecretKey key;

    /** 令牌有效期（分钟） */
    private final long expireMinutes;

    public JwtUtil(@Value("${mall.jwt.secret}") String secret,
                   @Value("${mall.jwt.expire-minutes:120}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /**
     * 签发令牌
     *
     * @param userId   用户 ID，放入 subject
     * @param username 用户名
     * @param roles    角色编码，如 ["ADMIN"]
     * @return 令牌字符串
     */
    public String createToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expireMinutes * 60 * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLES, roles == null ? Collections.emptyList() : roles)
                .issuedAt(now)
                .expiration(expireAt)
                .signWith(key)
                .compact();
    }

    /**
     * 签发无角色的令牌。仅用于角色尚未确定的场景，正常登录请用三参版本。
     */
    public String createToken(Long userId, String username) {
        return createToken(userId, username, Collections.emptyList());
    }

    /**
     * 解析令牌，校验签名与有效期。
     *
     * @param token 令牌字符串
     * @return Claims；校验失败（篡改、过期、格式错）返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // 令牌无效属于可预期的业务场景，记 debug 级别即可，避免污染日志
            log.debug("令牌解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从已解析的 Claims 中取出用户 ID。
     *
     * <p>拦截器里已经解析过一次，应该复用 Claims 调用本方法，
     * 而不是再拿 token 字符串解析第二遍。</p>
     *
     * @return 用户 ID；subject 非法时返回 null
     */
    public Long getUserId(Claims claims) {
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从令牌中取出用户 ID（便捷重载，内部会自行解析）
     */
    public Long getUserId(String token) {
        return getUserId(parseToken(token));
    }

    /**
     * 从已解析的 Claims 中取出角色编码。
     *
     * <p>JSON 反序列化后拿到的是 {@code List<String>}，但声明类型是 Object，
     * 所以这里做一次防御性转换，避免 ClassCastException。</p>
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        if (claims == null) {
            return Collections.emptyList();
        }
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }
}
