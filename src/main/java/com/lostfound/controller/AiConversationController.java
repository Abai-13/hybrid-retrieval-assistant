package com.lostfound.controller;

import com.lostfound.common.Result;
import com.lostfound.dto.ConversationTurnView;
import com.lostfound.dto.ConversationView;
import com.lostfound.dto.SendMessageRequest;
import com.lostfound.dto.SessionResponse;
import com.lostfound.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 找物助手会话接口。
 *
 * <p><b>会话 id 一律不可信</b>：路径里的 {@code {id}} 是客户端传的，
 * 每个操作都必须拿它和 token 里的用户比对一遍 —— 这件事在 Service 层做
 * （{@code ConversationStore.requireOwned}），Controller 只负责把 userId 取出来。
 * 「GET 一律放行」的拦截器规则只说明"你是谁"，说明不了"这段会话是不是你的"。
 *
 * <p><b>限流</b>：只有真正会调用大模型的 {@code .../messages} 受 AI 限流；
 * 读取/新建会话这类不花模型额度的接口已在 {@code WebConfig} 里排除
 * （见那里的注释）。登录要求一步都不能省 —— 放行限流 ≠ 放行鉴权。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/session")
@RequiredArgsConstructor
public class AiConversationController {

    private final ConversationService conversationService;

    /**
     * 打开页面时读当前会话。
     * <p>没有可用会话时 {@code conversation} 为 null，{@code previousExpired} 区分
     * "第一次来"和"上一段过期被清理了" —— 后者要让用户知道，否则他会以为条件被弄丢了。
     */
    @GetMapping
    public Result<SessionResponse> current(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.ok(conversationService.currentSession(userId));
    }

    /** 新对话 —— 换一件东西找时用它，保证不带入上一件的条件和排除项 */
    @PostMapping
    public Result<ConversationView> startNew(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.ok("已开始新的对话", conversationService.startNewSession(userId));
    }

    /** 按 id 读会话（所有权在 Service 校验） */
    @GetMapping("/{id}")
    public Result<ConversationView> detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.ok(conversationService.getSession(userId, id));
    }

    /**
     * 发一条消息，跑完整的一轮。
     * <p>失败（模型超时/坏 JSON）同样返回 code 200，但 {@code status=FAILED}
     * 且 {@code degradeReason} 非空 —— 这是本项目 AI 链路的既有约定：
     * 超时是预期内的结果，用 500 表达它，前端就只能弹一句"服务器错误"，
     * 而真正要传达的是"条件还在，可以重试"。但失败必须**可辨识**，
     * 绝不允许渲染成"没有找到"。
     */
    @PostMapping("/{id}/messages")
    public Result<ConversationTurnView> send(@PathVariable Long id,
                                            @Valid @RequestBody SendMessageRequest request,
                                            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("用户 {} 在会话 {} 发消息: requestId={}", userId, id, request.getRequestId());
        return Result.ok(conversationService.sendMessage(userId, id, request));
    }
}
