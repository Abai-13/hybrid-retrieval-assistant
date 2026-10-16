package com.lostfound.common;

import com.lostfound.entity.Item;

/**
 * 物品参与「文本匹配」的字段 —— 召回（2-gram）、向量化、自动匹配查询、大模型候选行
 * <b>必须共用同一份拼法</b>。
 *
 * <p>为什么抽成一个方法：这四个字段（标题 / 类别 / 地点 / 描述）在改造过程中
 * 曾经被三处各自手写一遍 —— 只要有一处多带了 {@code contact}（联系方式）或少带了
 * {@code location}，「召回看到的信息」和「模型看到的信息」就对不上了，
 * 而这种错位<b>不会报错</b>，只会让匹配结果莫名其妙地变差（约定 8）。
 *
 * <p>反过来也说明了<b>为什么只有这四个字段</b>：
 * <ul>
 *   <li>{@code contact} 是隐私，进向量库等于把电话号码复制一份出去；</li>
 *   <li>认领特征证明（{@code claim_application.proof_text}）是失主与拾获者之间
 *       的核对依据，同样不进任何检索文本；</li>
 *   <li>{@code type} / {@code status} / {@code userId} / {@code version} 对
 *       「像不像」这个问题没有信息量，只占 token。</li>
 * </ul>
 */
public final class ItemTexts {

    private ItemTexts() {
    }

    /** 参与匹配的四个字段，空值按空串处理，用空格拼接 */
    public static String docText(Item item) {
        if (item == null) {
            return "";
        }
        return String.join(" ",
                nvl(item.getTitle()),
                nvl(item.getCategory()),
                nvl(item.getLocation()),
                nvl(item.getDescription()));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
