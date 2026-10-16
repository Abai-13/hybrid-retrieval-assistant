package com.lostfound.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打开 AI 页时拿到的会话状态。
 *
 * <p><b>为什么要区分 {conversation: null} 的两种原因</b>：
 * 「第一次来」和「上一段会话过期被清理了」在数据上都是"没有可用会话"，
 * 但对用户是两件事 —— 后者必须说一句"之前那段已经过期了，我们重新开始"，
 * 否则他会以为系统把他的条件弄丢了。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    /** 当前可用的会话；没有则为 null */
    private ConversationView conversation;

    /** 是否存在**刚刚被清理掉的过期会话** */
    private boolean previousExpired;
}
