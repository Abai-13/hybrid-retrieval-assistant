package com.lostfound.service;

import com.lostfound.dto.ConversationTurnView;
import com.lostfound.dto.ConversationView;
import com.lostfound.dto.SendMessageRequest;
import com.lostfound.dto.SessionResponse;

/**
 * 找物助手会话 —— 第一阶段的业务编排入口。
 *
 * <p>它替代的是"用户自己记住刚才说过什么"：一段会话里的条件、修正、撤回、
 * 排除项和指代都由服务端起，前端只负责显示和转发。
 *
 * <p><b>每个方法都必须拿 {@code userId} 把会话重新认领一遍</b>：
 * 接口路径里的会话 id 来自客户端，属于不可信输入。
 * 「GET 一律放行」的拦截器规则只回答"你是谁"，回答不了"这段会话是不是你的"。
 */
public interface ConversationService {

    /**
     * 当前账号**未过期**的最近一段会话；没有时 {@code conversation} 为 {@code null}（前端据此新建）。
     * <p>刷新页面能恢复对话靠的就是它：只按 userId 查，
     * 前端存不存会话 id 都不影响正确性（也就没法用它越权）。
     * <p>同时顺手清理这个用户已过期的会话，并如实告诉调用方"刚清掉过一段"
     * —— 过期和"从没来过"必须分开说，否则用户会以为系统把他的条件弄丢了。
     */
    SessionResponse currentSession(Long userId);

    /** 开一段新会话 —— 换一件东西找时用它，保证不带入上一件的条件和排除项 */
    ConversationView startNewSession(Long userId);

    /** 按 id 读会话（所有权在 Store 层校验）；会话已过期时抛 409 */
    ConversationView getSession(Long userId, Long conversationId);

    /**
     * 发一条消息，跑完整的一轮。
     *
     * <p>幂等：同一个 {@code requestId} 重复到达时，已成功的那一轮直接重放，
     * 不会重复调模型、也不会在界面上多出一条消息。
     */
    ConversationTurnView sendMessage(Long userId, Long conversationId, SendMessageRequest request);
}
