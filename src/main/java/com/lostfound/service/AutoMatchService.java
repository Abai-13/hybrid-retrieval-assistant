package com.lostfound.service;

import com.lostfound.entity.Item;

/**
 * 发布后自动匹配 —— 物品发布成功后在后台跑一遍匹配，够格就生成站内通知。
 *
 * <p>和主动匹配（用户在 AI 页输入描述）的区别只有两点：
 * <ul>
 *   <li><b>触发方式</b>：这个是系统自动触发的，用户没主动要；</li>
 *   <li><b>筛选策略</b>：这个用分数门槛（{@code ai.match.auto.min-score}）压掉无关结果 ——
 *       通知是推给用户的，多推一条就是一次打扰。</li>
 * </ul>
 * 召回算法、大模型精排、幻觉防护、匹配理由<b>全部复用</b>，一行都不用改。
 */
public interface AutoMatchService {

    /**
     * 物品发布成功后触发。<b>异步执行，立刻返回</b>，绝不阻塞发布请求。
     *
     * <p>整个方法内部兜住所有异常：自动匹配失败<b>不能</b>导致物品发布失败。
     * 失败就是"这次没提醒到" —— 物品已经发布成功，用户随时可以自己去 AI 页搜，
     * 能力和自动匹配一模一样。
     */
    void triggerAfterPublish(Item item);
}
