package com.mall.controller;

import com.mall.api.dto.LoginDTO;
import com.mall.api.dto.RegisterDTO;
import com.mall.api.vo.LoginVO;
import com.mall.api.vo.UserVO;
import com.mall.common.result.Result;
import com.mall.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 *
 * <p>注意：类上的 {@code @RequestMapping("/user")} 已经把前缀定好了，
 * 方法上的路径<b>不要再重复写 /user</b>，否则会变成 /user/user/xxx。</p>
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 登录（此接口在 WebMvcConfig 中已放行，不需要令牌）
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", userService.login(dto));
    }

    /**
     * 注册（已放行）
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success("注册成功", userService.register(dto));
    }

    /**
     * 查询当前登录用户，需要携带令牌
     */
    @GetMapping("/info")
    public Result<UserVO> info() {
        return Result.success(userService.getCurrentUser());
    }

    // ==================================================================
    // 这里曾经有一个 GET /user/{id} —— 已删除，不要再加回来
    //
    // 【为什么删】它是典型的 IDOR（越权访问对象）漏洞：只要有一个合法
    //   令牌，就能把 id 从 1 开始遍历，拿到全站用户的手机号、邮箱。
    //   而且它与 GET /admin/user/{id} 功能完全重复，属于「同一件事有两个入口，
    //   其中一个没有守卫」—— 后期一定会有人误用到没守卫的那个。
    //
    // 【替代】查自己走 GET /user/info（只认令牌里的 userId，无法伪造）；
    //   管理员查别人走 GET /admin/user/{id}，由 @RequiresPermission("user:detail")
    //   守卫。
    // ==================================================================
}
