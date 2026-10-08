package com.lostfound.service.impl;

import com.lostfound.dto.AiQueryResponse;
import com.lostfound.dto.MatchResult;
import com.lostfound.entity.Item;
import com.lostfound.entity.Notification;
import com.lostfound.entity.NotificationMatch;
import com.lostfound.service.AiService;
import com.lostfound.service.CandidateService;
import com.lostfound.service.ItemService;
import com.lostfound.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发布后自动匹配的**异步执行体** —— 真正干活的地方。
 *
 * <p>和 {@link AutoMatchServiceImpl} 分开成两个 Bean 是有原因的：那个是「门面」，
 * 负责判开关；这里才是异步的。如果开关判断和 @Async 写在同一个方法里，
 * "关掉自动匹配"时任务仍然会被丢进线程池（只是立刻返回）——
 * 分开之后，**关掉就是真的一个任务都不提交**。
 *
 * <p>两个方向共用同一套召回 + 精排，区别只在「候选池是谁」和「收件人是谁」：
 * <pre>
 * 发布 LOST  → 候选池＝已有 FOUND → 收件人＝发布者本人
 * 发布 FOUND → 候选池＝已有 LOST  → 收件人＝每条被匹配失物的主人（按人分组，每人一条）
 * </pre>
 * 注意两个方向的候选池都**不含刚发布的那条自己**（池子按 type 筛），
 * 所以天然不会自己匹配自己；下面还有一道 userId 检查做兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMatchRunner {

    private final AiService aiService;
    private final CandidateService candidateService;
    private final ItemService itemService;
    private final NotificationService notificationService;

    /** 自动匹配的门槛：低于它不进通知 */
    @Value("${ai.match.auto.min-score:80}")
    private int autoMinScore;

    /** 自动匹配最多进几条 */
    @Value("${ai.match.auto.max-results:5}")
    private int autoMaxResults;

    /** 送进大模型的候选条数（和主动匹配共用同一个值） */
    @Value("${ai.recall.top-k:50}")
    private int recallTopK;

    /** 通知文案里标题的截断长度，避免撑爆 VARCHAR(200) */
    private static final int TITLE_MAX_LEN = 30;

    /** 匹配理由的截断长度，对应 notification_match.reason 的 VARCHAR(255) */
    private static final int REASON_MAX_LEN = 255;

    /**
     * 「泛分类」—— 它本身不表达"这是什么"，所以不能拿它去判定"类别不一致"。
     * 落在这一类的物品，交给大模型按文本判断。
     */
    private static final String GENERIC_CATEGORY = "其他";

    @Async("autoMatchExecutor")
    public void match(Item item) {
        try {
            if (item == null) {
                return;
            }
            if (Item.TYPE_LOST.equals(item.getType())) {
                matchAfterLostPublish(item);
            } else if (Item.TYPE_FOUND.equals(item.getType())) {
                matchAfterFoundPublish(item);
            }
        } catch (Exception e) {
            // 兜住一切：自动匹配是"锦上添花"，它坏了不能牵连已经成功的发布
            log.error("自动匹配失败（不影响物品发布）: itemId={}, type={}",
                    item == null ? null : item.getId(), item == null ? null : item.getType(), e);
        }
    }

    // ===================== 场景A：发布失物 → 找招领 =====================

    private void matchAfterLostPublish(Item lost) {
        // 复用整条主动匹配链路（召回 + 精排 + 幻觉防护 + 匹配理由）
        AiQueryResponse response = aiService.query(buildQueryText(lost));
        List<MatchResult> qualified = pickQualified(response, "itemId=" + lost.getId());
        if (qualified.isEmpty()) {
            return;
        }

        Map<Long, Item> foundItems = itemService.mapByIds(idsOf(qualified));
        List<MatchResult> effective = qualified.stream()
                .filter(m -> {
                    Item found = foundItems.get(m.getItemId());
                    if (found == null) {
                        return false;
                    }
                    if (found.getUserId().equals(lost.getUserId())) {
                        log.info("自动匹配跳过自己发布的招领: lostItemId={}, foundItemId={}", lost.getId(), found.getId());
                        return false;
                    }
                    return true;
                })
                .toList();
        if (effective.isEmpty()) {
            return;
        }

        // 一次发布 → 发布者收一条汇总通知
        Notification notification = new Notification();
        notification.setReceiverId(lost.getUserId());
        notification.setSourceItemId(lost.getId());
        notification.setIsRead(Notification.UNREAD);
        notification.setContent(
                "系统为你的失物《" + truncate(nvl(lost.getTitle()), TITLE_MAX_LEN) + "》找到了 "
                        + effective.size() + " 条可能匹配的招领信息，点击查看。");

        List<NotificationMatch> details = effective.stream()
                .map(m -> detail(lost.getId(), m.getItemId(), m))
                .toList();

        if (notificationService.createWithMatches(notification, details)) {
            log.info("自动匹配完成并已通知（失物→招领）: itemId={}, 匹配 {} 条", lost.getId(), details.size());
        }
    }

    // ===================== 场景B：发布招领 → 找失物 =====================

    private void matchAfterFoundPublish(Item found) {
        String query = buildQueryText(found);
        // 候选池换成失物；精排仍然走同一段代码。**整轮只有这一次模型调用。**
        List<Item> candidates = candidateService.recall(query, Item.TYPE_LOST, recallTopK);
        AiQueryResponse response = aiService.matchAgainst(query, candidates);

        List<MatchResult> qualified = pickQualified(response, "itemId=" + found.getId());
        if (qualified.isEmpty()) {
            return;
        }

        Map<Long, Item> lostItems = itemService.mapByIds(idsOf(qualified));
        List<MatchResult> effective = qualified.stream()
                .filter(m -> {
                    Item lost = lostItems.get(m.getItemId());
                    if (lost == null) {
                        return false;
                    }
                    // 自己发的失物不提醒自己
                    if (lost.getUserId().equals(found.getUserId())) {
                        log.info("自动匹配跳过自己发布的失物: foundItemId={}, lostItemId={}", found.getId(), lost.getId());
                        return false;
                    }
                    // 类别守卫：明确不一致的直接不通知；泛分类交给模型判断
                    if (!categoryCompatible(lost.getCategory(), found.getCategory())) {
                        log.info("自动匹配因类别不一致跳过: foundItemId={}({}), lostItemId={}({})",
                                found.getId(), found.getCategory(), lost.getId(), lost.getCategory());
                        return false;
                    }
                    return true;
                })
                .toList();
        if (effective.isEmpty()) {
            return;
        }

        // 按失主分组：**每位失主对同一次发布最多收到一条汇总通知**
        Map<Long, List<MatchResult>> byOwner = effective.stream()
                .collect(Collectors.groupingBy(m -> lostItems.get(m.getItemId()).getUserId()));

        int notified = 0;
        for (Map.Entry<Long, List<MatchResult>> entry : byOwner.entrySet()) {
            List<MatchResult> mine = entry.getValue();
            Notification notification = new Notification();
            notification.setReceiverId(entry.getKey());
            notification.setSourceItemId(found.getId());
            notification.setIsRead(Notification.UNREAD);
            notification.setContent(buildFoundContent(found.getTitle(), mine.size()));

            List<NotificationMatch> details = mine.stream()
                    .map(m -> detail(m.getItemId(), found.getId(), m))
                    .toList();

            if (notificationService.createWithMatches(notification, details)) {
                notified++;
            }
        }
        log.info("自动匹配完成（招领→失物）: itemId={}, 达标 {} 条, 通知 {} 位失主",
                found.getId(), effective.size(), notified);
    }

    /** 一条匹配明细：「哪条失物 ↔ 哪条招领，多少分，为什么像」 */
    private NotificationMatch detail(Long lostItemId, Long foundItemId, MatchResult match) {
        NotificationMatch d = new NotificationMatch();
        d.setLostItemId(lostItemId);
        d.setFoundItemId(foundItemId);
        d.setScore(match.getScore());
        d.setReason(truncate(match.getReason(), REASON_MAX_LEN));
        return d;
    }

    // ===================== 共用：降级检查 + 门槛过滤 =====================

    /**
     * 从模型返回里挑出够格的。
     * <p>
     * ⚠️ <b>降级必须挡在最前面</b>：模型挂了 / 召回为空时 matches 也是空的，
     * 拿它去决定"要不要通知"会把"调用失败"当成"没匹配到"。
     */
    private List<MatchResult> pickQualified(AiQueryResponse response, String context) {
        if (response.getDegradeReason() != null) {
            log.warn("自动匹配降级，本次不生成通知: {}, degradeReason={}", context, response.getDegradeReason());
            return List.of();
        }
        List<MatchResult> qualified = response.getMatches().stream()
                .filter(m -> m.getScore() != null && m.getScore() >= autoMinScore)
                .limit(Math.max(1, autoMaxResults))
                .toList();
        if (qualified.isEmpty()) {
            int best = response.getMatches().stream()
                    .mapToInt(m -> m.getScore() == null ? 0 : m.getScore()).max().orElse(0);
            log.info("自动匹配无达标结果，不生成通知: {}, 模型返回 {} 条，最高分 {}",
                    context, response.getMatches().size(), best);
        }
        return qualified;
    }

    /**
     * 类别守卫。
     *
     * <p>为什么需要它：实测里"蓝色的学生卡"曾被推成"蓝色的 U 盘/充电宝"并且都拿了 80 分 ——
     * 颜色属性对了、物品本体根本不是一类。加一道最便宜的过滤能挡掉最刺眼的那一类误报。
     *
     * <p>什么时候放行：
     * <ul>
     *   <li>两边类别相同 → 明确一致，放行；</li>
     *   <li>任一边是「其他」或没填 → 那是**泛分类**，它本身不表达"这是什么"，
     *       拿它判"不一致"没道理，交给大模型按文本判断；</li>
     *   <li>其余（明确不一致）→ 不通知。</li>
     * </ul>
     */
    static boolean categoryCompatible(String lostCategory, String foundCategory) {
        if (!hasText(lostCategory) || !hasText(foundCategory)) {
            return true;
        }
        if (lostCategory.equals(foundCategory)) {
            return true;
        }
        return GENERIC_CATEGORY.equals(lostCategory) || GENERIC_CATEGORY.equals(foundCategory);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static List<Long> idsOf(List<MatchResult> matches) {
        return matches.stream().map(MatchResult::getItemId).toList();
    }

    /**
     * 拼给召回和大模型看的查询文本 —— <b>字段必须和召回的文档侧一致</b>
     * （title / category / location / description，见 CandidateServiceImpl 的 docText）。
     * 两边字段不一致等于白做。
     */
    private static String buildQueryText(Item item) {
        return String.join(" ",
                nvl(item.getTitle()), nvl(item.getCategory()),
                nvl(item.getLocation()), nvl(item.getDescription()));
    }

    /** 场景B 的文案：收件人是失主，主语是"有人发了招领" */
    private static String buildFoundContent(String foundTitle, int count) {
        return "有人发布了招领信息《" + truncate(nvl(foundTitle), TITLE_MAX_LEN) + "》，"
                + (count > 1 ? "可能与你发布的 " + count + " 条失物匹配" : "可能与你发布的失物匹配")
                + "，点击查看。";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
