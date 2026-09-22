package com.mall.api;

import com.mall.api.dto.RegisterDTO;
import com.mall.api.vo.UserVO;
import com.mall.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户接口定义
 *
 * <p>说明：当前为单体架构下的接口规范（预留 Feign 接口风格）。
 * 未来微服务拆分时，为本接口添加 {@code @FeignClient(name = "mall-user-service")} 注解，
 * 并配合 spring-cloud-starter-openfeign 即可无缝升级为远程调用。</p>
 */
public interface UserApi {

    /**
     * 根据 ID 查询用户
     *
     * @param id 用户 ID
     * @return 用户信息
     */
    @GetMapping("/user/{id}")
    UserVO getUserById(@PathVariable("id") Long id);

    @PostMapping("/user/register")
    Result<String> register(@RequestBody RegisterDTO regiserDTO);
}
