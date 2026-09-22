package com.mall.controller;

import com.mall.api.dto.ChatDTO;
import com.mall.api.vo.ChatVO;
import com.mall.common.result.Result;
import com.mall.convert.ai.ChatVoFactory;
import com.mall.service.ai.AiChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话接口。
 *
 * <p><b>本类的职责边界</b>：取参 → 校验 → 调服务 → 包 {@code Result}。
 * 三件事都不做：</p>
 * <ul>
 *   <li><b>不拼装 VO</b> —— {@code ChatTurn → ChatVO} 交给 {@link ChatVoFactory}；</li>
 *   <li><b>不手工判空</b> —— 交给 {@link ChatDTO} 上的 {@code @NotBlank} + {@code @Valid}，
 *       校验规则跟着 DTO 走，换个入口（比如将来的流式接口）也能复用；</li>
 *   <li><b>不碰大模型</b> —— 拼 system 提示词、组织 messages 由 {@code AiChatService} 负责。</li>
 * </ul>
 *
 * <p>所以整个方法体只剩一行。这不是为了短，而是因为<b>职责都归位了</b>：
 * 以后调参、加历史、接工具调用，改的都不是这个类。</p>
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final ChatVoFactory chatVoFactory;

    @PostMapping("/chat")
    public Result<ChatVO> chat(@Valid @RequestBody ChatDTO dto) {
        return Result.success(chatVoFactory.create(aiChatService.chat(dto)));
    }
}
