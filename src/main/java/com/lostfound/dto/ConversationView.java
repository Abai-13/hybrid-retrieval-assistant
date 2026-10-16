package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个完整会话（刷新页面恢复时用）。
 *
 * <p>{@code expiresAt} 也返回给前端：会话快要过期时界面上可以提示一句，
 * 而不是让用户在某天突然发现条件全没了。
 */
@Data
public class ConversationView {

    private Long id;

    private Integer version;

    /** 当前条件（含来源与确定性），前端渲染成可删除的标签 */
    private List<SearchCondition> conditions;

    /** 消息，按时间正序 */
    private List<ConversationMessageView> messages;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
