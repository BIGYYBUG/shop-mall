package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 店铺资料参数，用于两处：<b>申请入驻</b>与<b>店主修改自己的店铺</b>。
 *
 * <p><b>为什么共用一个 DTO</b>：两处可填的字段完全一致，差别只在服务端规则
 * （申请时强制置为"待审核"、修改时不动状态）。把这些规则放进服务层而不是
 * 拆成两个几乎相同的 DTO，能少一份要同步维护的契约 —— 给店铺加一个字段时
 * 只需改一处。</p>
 *
 * <p><b>为什么没有 status 字段</b>：状态由平台审核决定，绝不能让店主自己填。
 * 一旦出现在契约里，客户端就能提交 {@code status=1} 直接把自己"审核通过"。
 * 契约里根本不存在这个字段，是比"服务端忽略它"更可靠的防线。</p>
 */
public record ShopDTO(

        @NotBlank(message = "店铺名称不能为空")
        @Size(max = 64, message = "店铺名称不能超过 64 个字符")
        String name,

        /** 店铺 Logo 的 objectKey，由上传接口返回。可为空 */
        @Size(max = 512, message = "Logo 对象键过长")
        String logoKey,

        @Size(max = 512, message = "店铺简介不能超过 512 个字符")
        String description,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "联系电话格式不正确")
        String contactPhone
) {
}
