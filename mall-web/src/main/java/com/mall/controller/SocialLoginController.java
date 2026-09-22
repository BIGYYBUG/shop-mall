package com.mall.controller;

import com.mall.api.dto.SocialLoginDTO;
import com.mall.api.vo.LoginVO;
import com.mall.common.result.Result;
import com.mall.social.SocialLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 第三方登录入口。
 *
 * <p>路径用 {@code /auth/{source}/...} 而不是 {@code /auth/wechat/...}，
 * 这样加 QQ、支付宝登录时前端不用改路由结构。</p>
 *
 * <p>本控制器整体在 {@code WebMvcConfig} 中放行 —— 因为这两个接口正是
 * 「用来拿令牌」的，不能要求先有令牌。</p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class SocialLoginController {

    private final SocialLoginService socialLoginService;

    /**
     * 查询当前可用的第三方登录渠道（已完成配置的），前端据此决定显示哪些按钮
     */
    @GetMapping("/sources")
    public Result<List<String>> sources() {
        return Result.success(socialLoginService.listAvailableSources());
    }

    /**
     * 获取授权跳转地址。前端拿到后 {@code window.location.href = url} 即可。
     *
     * <p><b>state 参数的作用</b>：一个随机串，随授权请求发给微信，
     * 微信回调时会原样带回。前端比对回调里的 state 与自己存的是否一致，
     * 不一致就说明这不是用户主动发起的授权 —— 这是防 CSRF 的标准做法。
     * 不传时后端生成一个，保证字段不为空。</p>
     */
    @GetMapping("/{source}/authorize-url")
    public Result<String> authorizeUrl(@PathVariable("source") String source,
                                       @RequestParam(required = false) String state) {
        String finalState = StringUtils.hasText(state)
                ? state
                : UUID.randomUUID().toString().replace("-", "");
        return Result.success(socialLoginService.buildAuthorizeUrl(source, finalState));
    }

    /**
     * 用授权码登录（查不到账号会自动注册）。
     *
     * <p>前端在回调页 {@code /oauth/callback?code=xxx} 里取出 code，
     * POST 到本接口，拿到的是本项目的 JWT —— 与账号密码登录返回结构完全一致。</p>
     */
    @PostMapping("/{source}/login")
    public Result<LoginVO> login(@PathVariable("source") String source,
                                 @Valid @RequestBody SocialLoginDTO dto) {
        return Result.success("登录成功", socialLoginService.login(source, dto.code()));
    }
}
