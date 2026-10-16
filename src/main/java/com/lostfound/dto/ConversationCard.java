package com.lostfound.dto;

import com.lostfound.entity.Item;
import lombok.Data;

/**
 * 会话里展示的一张物品卡片 —— 前端点得开的那张。
 *
 * <p><b>字段里没有联系方式，这是有意的</b>：助手只负责"帮你找到"，不负责"帮你联系"。
 * 联系方式要不要给这个人看，由物品详情接口按既有规则判断（发布者 + 已通过审核的申请人）。
 * 卡片上顺手带上电话，等于在认领流程之外开了一条旁路 ——
 * 谁都能直接拿到电话的话，失主根本不会去走申请流程。
 *
 * <p>{@code status} 是**读取时从库里现取的**，不是展示那一刻的快照：
 * 上轮显示「未认领」的物品被认领走以后，历史消息里的这张卡也必须改成最新状态，
 * 否则助手会一直承诺一件已经没了的东西。
 */
@Data
public class ConversationCard {

    private Long itemId;

    /** 以下四项一律本地回填，不用模型返回的（模型只负责判断"哪条最像"） */
    private String title;
    private String category;
    private String location;

    /** 物品当前状态 —— UNCLAIMED / CLAIMING / CLAIMED，实时取自数据库 */
    private String status;

    /** 匹配度 0~100，模型给出。解释/指代类的卡片可以为空 */
    private Integer score;

    /** 匹配理由 */
    private String reason;

    /**
     * 这张卡在<b>它所属的那批结果里的序号</b>（从 1 开始）。
     *
     * <p>为什么序号要由后端给、而不是前端按下标自己数：
     * 「第二个」是用户唯一能用的指代方式，而**解释某一条**的那一轮只会带一张卡。
     * 前端要是按下标数，那张卡就会被写成「1」—— 用户问的是第二个，屏幕上却显示 1，
     * 下次说「不是第二个」时就再也对不上了。
     * 后端知道它是从哪一批的第几个解析出来的，所以由后端把序号钉在卡片上，
     * 并跟着消息一起落库，刷新后也不会变。
     */
    private Integer index;

    /** 用本地数据回填一张卡片；item 为空（已被删除等）时返回 null，由调用方跳过 */
    public static ConversationCard from(Item item, Integer score, String reason) {
        return from(item, score, reason, null);
    }

    /** 回填时带上序号（见 {@link #index}） */
    public static ConversationCard from(Item item, Integer score, String reason, Integer index) {
        if (item == null) {
            return null;
        }
        ConversationCard card = new ConversationCard();
        card.setItemId(item.getId());
        card.setTitle(item.getTitle());
        card.setCategory(item.getCategory());
        card.setLocation(item.getLocation());
        card.setStatus(item.getStatus());
        card.setScore(score);
        card.setReason(reason);
        card.setIndex(index);
        return card;
    }
}
