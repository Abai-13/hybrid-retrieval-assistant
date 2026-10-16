package com.lostfound.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 找物摘要里的一条条件 —— <b>用户明确说过的一个事实</b>。
 *
 * <p>三个字段缺一不可：
 * <ul>
 *   <li>{@code field} 决定"纠正"能不能生效 —— 同 field 的新值直接覆盖旧值；</li>
 *   <li>{@code certainty} 区分「就是黑色」和「可能是黑色」。
 *       不确定的条件<b>照常参与检索</b>（用户说"可能在图书馆"是想让系统多找找），
 *       但它必须留痕：界面上写着「不确定」，用户才知道系统没有把它当硬条件。
 *       这也是为什么不能偷偷丢掉它 —— 丢掉等于系统假装没听见；</li>
 *   <li>{@code source} 是「用户原话」。摘要里的每一条都必须能回答
 *       "这是从哪句话里来的"，否则它和模型自己编的就没有区别了。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchCondition {

    /** 条件名，如「主体」「颜色」「地点」「品牌」 */
    private String field;

    /** 条件值，如「手机」「黑色」「图书馆」 */
    private String value;

    /** CERTAIN（用户确定的）/ UNCERTAIN（用户自己也不确定，如「可能在图书馆」） */
    private String certainty;

    /** 来源：用户当初说这句话时的原话（截断保存） */
    private String source;
}
