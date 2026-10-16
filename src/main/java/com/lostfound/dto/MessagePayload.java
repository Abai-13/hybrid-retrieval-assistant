package com.lostfound.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手消息落库时带的**结构化载荷**（{@code ai_message.payload_json}）。
 *
 * <p>为什么不给这些内容单独建列：它们是一组整体，只在读一条消息时一起用，
 * 从不在 SQL 里被筛过。拆成列只会让表变宽，且每加一个诊断字段就要改表结构 ——
 * 而诊断字段恰恰是最会频繁增加的一类。
 *
 * <p><b>卡片里不存标题/地点/状态</b>（存了也不用）：读的时候一律从数据库回填。
 * 理由见 {@link ConversationCard} 的类注释 —— 历史卡片必须显示物品的**当前**状态。
 */
@Data
public class MessagePayload {

    private List<ConversationCard> cards = new ArrayList<>();

    /** 这一轮结束时的条件快照 —— 用户点开历史消息时能看到"当时记住了什么" */
    private List<SearchCondition> conditions = new ArrayList<>();

    private TurnDiagnostics diagnostics;

    /**
     * 这一轮**结束时**的会话版本号 —— 只为幂等重放而存。
     *
     * <p>重放一条历史回复时，响应里的版本必须是**当时的**版本：
     * 拼上当前版本（可能已经被后续几轮推高）就成了「旧条件 + 新版本」的矛盾组合，
     * 页面照着应用会让下一轮的指代错位（第一阶段验收复现的缺陷 1）。
     * 历史快照里没有这一项，就只能整份都是历史 —— 所以它随消息落库。
     */
    private Integer conversationVersion;

    /** 降级原因（枚举）；正常为 null */
    private String degradeReason;

    /** 降级细节（自由文本，给人排查用） */
    private String degradeDetail;

    /**
     * 用户消息才有的：这一轮带下来的**结构化撤回指令**。
     * <p>存它的唯一理由是失败重试：撤回是"点 ×"这种确定操作，
     * 重试时必须原样重放指令，绝不能退化成让模型去猜一句自然语言。
     */
    private RetractRequest retract;

    public String toJson() {
        return JSONUtil.toJsonStr(this);
    }

    /** 反序列化；坏数据退化成空载荷并记日志，不让一条脏记录把整个会话读挂 */
    public static MessagePayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new MessagePayload();
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            MessagePayload payload = new MessagePayload();
            JSONArray cards = obj.getJSONArray("cards");
            if (cards != null) {
                payload.setCards(JSONUtil.toList(cards, ConversationCard.class));
            }
            JSONArray conditions = obj.getJSONArray("conditions");
            if (conditions != null) {
                payload.setConditions(JSONUtil.toList(conditions, SearchCondition.class));
            }
            JSONObject diag = obj.getJSONObject("diagnostics");
            if (diag != null) {
                payload.setDiagnostics(JSONUtil.toBean(diag, TurnDiagnostics.class));
            }
            payload.setConversationVersion(obj.getInt("conversationVersion"));
            payload.setDegradeReason(obj.getStr("degradeReason"));
            payload.setDegradeDetail(obj.getStr("degradeDetail"));
            JSONObject retract = obj.getJSONObject("retract");
            if (retract != null) {
                payload.setRetract(JSONUtil.toBean(retract, RetractRequest.class));
            }
            return payload;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MessagePayload.class)
                    .error("消息载荷 JSON 解析失败，本次按空载荷继续: {}", json, e);
            return new MessagePayload();
        }
    }
}
