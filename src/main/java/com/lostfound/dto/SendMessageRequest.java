package com.lostfound.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 向会话发一条消息。
 *
 * <p>{@code requestId} 由前端生成并在**重试时原样复用** —— 它是后端识别
 * 「这是刚才那条，不是新的一条」的唯一依据。没有它，一次网络重试就会变成两轮。
 */
@Data
public class SendMessageRequest {

    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 过长")
    private String requestId;

    /** 用户原话（删除条件时是前端生成的说明文字，对用户可见） */
    @NotBlank(message = "内容不能为空")
    @Size(max = 500, message = "内容过长（最多 500 字）")
    private String content;

    /**
     * 客户端**当前看到的**会话版本（{@code ConversationView.version}）。
     *
     * <p>为什么需要它：用户可能同时开着两个标签页，一个还停在几分钟前的搜索结果上。
     * 他在旧页面里说「第二个」，服务端却已经有一批新结果了 ——
     * 不校验的话，这句话会被按新结果解释，指向一件他从没看见过的物品。
     * 版本对不上就让他先刷新：刷新之后页面上的卡片顺序和服务端一致，指代才有意义。
     *
     * <p>可为空：不传就不做这项校验（命令行/脚本调试方便），前端一律会传。
     */
    private Integer conversationVersion;

    /**
     * 结构化撤回指令 —— 用户点「当前条件」上的 × 时带上。
     *
     * <p>为什么删除条件不走自然语言而是明确的指令：「颜色记不清了」这类话
     * 需要模型理解，理解错了是"猜错"；而点 × 是<b>确定操作</b>，
     * 它不该有猜错的可能。所以这条路径不调模型，直接按 field 删。
     * 计划里"确定的操作可走普通代码，不强制调用模型"说的就是这种情况。
     */
    @Valid
    private RetractRequest retract;
}
