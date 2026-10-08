package com.lostfound.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lostfound.common.RetrySupport;
import com.lostfound.config.LlmConfig;
import com.lostfound.dto.AiQueryResponse;
import com.lostfound.dto.MatchResult;
import com.lostfound.entity.Item;
import com.lostfound.service.AiService;
import com.lostfound.service.CandidateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务实现 — 调用大模型完成问答和物品匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final LlmConfig llmConfig;
    private final RestTemplate llmRestTemplate;
    private final CandidateService candidateService;

    /**
     * 召回后送进大模型的候选条数。
     * <p>
     * 做成配置项的理由和限流阈值一样：这是运维参数，要按实测调。
     * 调小了召回不够（池子里的东西进不来），调大了 token 上去、干扰项也变多。
     */
    @Value("${ai.recall.top-k:50}")
    private int recallTopK;

    /**
     * 大模型调用失败时的重试次数（不含首次）。
     * <p>
     * 实测免费档的长尾延迟导致的降级率在 1.4%~32.2% 之间波动，
     * 重试一次就能把绝大部分超时救回来。设成 0 可关闭重试。
     */
    @Value("${llm.max-retries:2}")
    private int maxRetries;

    /**
     * 主动匹配最多返回几条（用户在 AI 页输入描述）。
     * <p>prompt 里也写了 5，这里再夹一道，不把展示条数交给模型自觉。
     */
    @Value("${ai.match.interactive.max-results:5}")
    private int interactiveMaxResults;

    /**
     * 自动匹配的最低分门槛（物品发布后由系统自动跑，用于站内通知）。
     * <p>⚠️ <b>主动匹配不使用这个值</b> —— 见 {@link #NO_SCORE_FILTER}。
     * <p>目前还没有代码读它：功能尚未开发，先把策略和配置预留出来。
     */
    @Value("${ai.match.auto.min-score:80}")
    private int autoMatchMinScore;

    /** 自动匹配最多进几条通知 */
    @Value("${ai.match.auto.max-results:5}")
    private int autoMatchMaxResults;

    /**
     * 主动匹配不做分数过滤。
     *
     * <p>为什么主动匹配不设门槛：这个场景是<b>用户主动来找东西</b> ——
     * 他描述完就在等结果，多给他看一条让他自己判断，远比"替他筛掉"安全；
     * 漏报的代价（他以为平台上没有，就此放弃）远大于误报（他扫一眼发现不像）。
     * 而且模型的 score 是自己写的 0~100 整数，<b>没有被校准过</b>，
     * 拿它当"质量闸门"本身就没有依据。
     *
     * <p>分数门槛留给自动匹配 —— 那边是系统主动推给用户，多推一条就是一次打扰。
     */
    private static final int NO_SCORE_FILTER = 0;

    /** 展示条数的上限。与 prompt 里的「最多 5 个」保持一致 */
    private static final int MAX_ALLOWED_RESULTS = 5;

    /**
     * 启动时校验两套筛选策略的配置 —— <b>配置不合法就直接起不来，绝不静默钳位</b>。
     *
     * <p>为什么不做"悄悄夹到合法范围"：那样配置文件写着 150，程序却按 100 跑、
     * 或者写着 -5 却按 0 跑，<b>看代码的人和看配置的人会得出两个不同的结论</b>。
     * 更糟的是门槛写成 `800`（手滑多打一个 0）会安静地让自动匹配永远不发通知 ——
     * 而"没有通知"看起来太像一个正常业务结果了，没人会去怀疑是配置写错了。
     *
     * <p>自动匹配的两个键目前还没有代码读 —— <b>照样校验</b>：配置写在文件里却没人读时，
     * 写错了会静默存续到功能上线那天，那才是最贵的。
     *
     * <p>抛的是 {@link IllegalStateException}，Spring 会中止启动并把消息原样打出来，
     * 而且<b>一次报全部问题</b>，不用改一个重启一次。
     */
    @PostConstruct
    void validateMatchRules() {
        List<String> errors = new ArrayList<>();
        if (interactiveMaxResults < 1 || interactiveMaxResults > MAX_ALLOWED_RESULTS) {
            errors.add("ai.match.interactive.max-results 必须在 1~" + MAX_ALLOWED_RESULTS
                    + " 之间，当前值：" + interactiveMaxResults);
        }
        if (autoMatchMinScore < 0 || autoMatchMinScore > 100) {
            errors.add("ai.match.auto.min-score 必须在 0~100 之间（0 表示不过滤），当前值：" + autoMatchMinScore);
        }
        if (autoMatchMaxResults < 1 || autoMatchMaxResults > MAX_ALLOWED_RESULTS) {
            errors.add("ai.match.auto.max-results 必须在 1~" + MAX_ALLOWED_RESULTS
                    + " 之间，当前值：" + autoMatchMaxResults);
        }
        // ⚠️ 自动匹配是在主动匹配拿到的结果之上再筛的（它复用的就是 AiService#query），
        //    所以自动的条数上限不可能超过主动的 —— 配反了会让自动匹配永远吃不饱，
        //    而且表现出来只是"通知少了"，查起来很费劲。直接在启动时拦住。
        if (interactiveMaxResults < autoMatchMaxResults) {
            errors.add("ai.match.interactive.max-results（" + interactiveMaxResults
                    + "）不能小于 ai.match.auto.max-results（" + autoMatchMaxResults
                    + "）—— 自动匹配复用主动匹配的结果，后者是前者的上限");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("AI 匹配筛选策略配置不合法：\n  - "
                    + String.join("\n  - ", errors));
        }
    }

    /** 系统提示：限制 AI 只回答失物招领相关问题 */
    private static final String SYSTEM_PROMPT =
            "你是校园失物招领助手，只回答校园失物招领、物品挂失、物品寻找相关的问题。" +
            "如果用户问不相关的问题，请礼貌地表示你只能回答失物招领相关问题。" +
            "回答尽量简洁，不超过 200 字。";

    /**
     * 匹配提示词。
     * <p>
     * 版本演进记录（换小模型时踩的坑，比代码本身值钱）：
     * <pre>
     * v1  原版，「用户描述 → 50 行候选 → 指令」结构
     *     DeepSeek 93.2% / Qwen2.5-7B 76.0%
     *
     * v2  为了适配小模型做了四处"优化"：指令前置 + 打分档位 +
     *     宁缺毋滥 + 结尾重申
     *     → Qwen 掉到 61.0%，反而更差
     *
     * v3  从 v2 的失败里定位到根因，只改一处：明确鼓励"不确定也要返回"
     * </pre>
     * <p>
     * v2 失败的原因值得记下来 —— 它是「prompt 和评测集设计撞车」：
     * 评测集的 query 是「失主视角的模糊描述」，故意丢掉了精确型号，
     * 所以<b>正确答案天生就是那条"不太确定"的</b>。
     * 而我在 v2 里写了「不像的不要硬凑，宁可少返回」——
     * 模型照做，把正确答案也一起毙了。
     * 实测证据：返回空结果的用例占比 4.1%(DeepSeek) → 13.0%(v1) → 32.9%(v2)，
     * 和命中率严格负相关。
     * <p>
     * 更深的教训是<b>产品判断</b>：失物招领场景里，漏报的代价远大于误报 ——
     * 多返回几条用户扫一眼就行，返回空用户就直接放弃了。
     * 我把一个"搜索"任务当成"分类"任务来设计 prompt 了。
     * <p>
     * 要求返回 JSON 而不是自然语言，是为了让结果可被程序消费。
     * 配套用 {@code response_format=json_object} 约束输出格式。
     */
    private static final String MATCH_PROMPT_TEMPLATE = """
            用户描述：%s

            %s

            请从候选物品中挑出与用户描述最匹配的物品，最多 5 个，按匹配度从高到低排序。
            只返回 JSON，格式：
            {"answer":"给用户的一句话总结","matches":[{"itemId":数字,"score":0到100的整数,"reason":"简短理由"}]}
            要求：
            - itemId 必须是候选列表中真实存在的 id，不要编造
            - score 是 0 到 100 的整数，越大越像
            - 用户的描述通常很模糊，只要有一点像就返回，交给用户自己判断 ——
              这是找东西的场景，漏掉比多返回的代价大得多
            - 确实一条都不像时，matches 返回空数组
            """;

    /** 描述字段在 prompt 里的最大长度，超出截断 */
    private static final int DESC_MAX_LEN = 60;

    // ===================== 对外接口 =====================

    @Override
    public String chat(String question) {
        log.info("AI 问答请求: {}", question);
        ApiCall call = callLlm(buildChatBody(question));
        if (call == null) {
            return "AI 服务繁忙，请稍后重试";
        }
        return call.content();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 只负责「召回 + 交给精排」，<b>本身不做任何筛选</b> —— 筛选是策略层的事
     * （主动匹配不过滤、自动匹配用门槛）。
     */
    @Override
    public AiQueryResponse query(String question) {
        log.info("AI 物品匹配请求: {}", question);
        // ① 召回候选物品 —— 从「最近发布的 30 条」改成「全量 + 2-gram 打分取 Top-K」
        return matchAgainst(question, candidateService.recall(question, recallTopK));
    }

    @Override
    public AiQueryResponse matchAgainst(String question, List<Item> candidates) {
        long start = System.currentTimeMillis();

        AiQueryResponse result = new AiQueryResponse();
        result.setCandidateCount(candidates.size());
        result.setCandidateIds(candidates.stream().map(Item::getId).toList());
        result.setMatches(List.of());
        // 回显"本次用的什么模型、生效的展示规则"—— 降级路径也要带上，
        // 否则评测记录不到这一轮的对照条件（历史报告缺失模型信息就是这么来的）
        result.setModel(llmConfig.getModel());
        // 回显**本次实际生效的**策略：主动匹配一律不过滤，所以最低分恒为 0
        result.setMinScore(NO_SCORE_FILTER);
        result.setMaxResults(interactiveMaxResults);

        // ② 一个候选都没有时，别调大模型 —— 白花一次 token 和 1 秒延迟。
        //    模型面对空列表只有两种反应：说「没找到」，或者开始编。
        //    这两种结果我们本来就知道，没必要花钱问一次。
        if (candidates.isEmpty()) {
            result.setAnswer("没有找到相关的招领信息。可以换个说法再试试，"
                    + "或者直接去失物招领处登记一下。");
            // 这是正常路径（召回确实没捞到东西），不是故障。但也要标出来 ——
            // 判据是它和"调用了大模型但没匹配到"是两回事，评测时要分开统计。
            result.setDegradeReason(AiQueryResponse.DEGRADE_NO_CANDIDATES);
            result.setElapsedMs(System.currentTimeMillis() - start);
            return result;
        }

        // ③ 拼 prompt 并调用大模型
        String prompt = String.format(MATCH_PROMPT_TEMPLATE, question, formatCandidates(candidates));
        ApiCall call = callLlm(buildMatchBody(prompt));

        if (call == null) {
            result.setAnswer("AI 服务繁忙，请稍后重试");
            // ⚠️ 必须显式标记。不标记的话，外面看到的是 HTTP 200 + 一段正常文案，
            // 和"匹配了但没找到"完全无法区分 —— 评测脚本会把它当未命中算进去，
            // 得出「优化之后反而更差」这种颠倒的结论（实测踩过）。
            result.setDegradeReason(AiQueryResponse.DEGRADE_LLM_ERROR);
            result.setElapsedMs(System.currentTimeMillis() - start);
            log.warn("大模型调用失败，本次请求降级: question={}", question);
            return result;
        }

        result.setPromptTokens(call.promptTokens());
        result.setCompletionTokens(call.completionTokens());

        // ④ 解析 JSON 并回填本地字段
        try {
            JSONObject json = JSONUtil.parseObj(call.content());
            List<MatchResult> all = toMatchResults(json.getJSONArray("matches"), candidates);
            // 主动匹配：按分数降序取 Top-N，**不做最低分过滤**（见 NO_SCORE_FILTER 的说明）
            DisplaySelection selection = applyDisplayRules(all, NO_SCORE_FILTER, interactiveMaxResults);
            result.setMatches(selection.shown());
            result.setBelowThresholdCount(selection.belowThreshold());

            // ⑤ 只有确实裁剪过才重写 answer。
            //    模型的"一句话总结"是基于**过滤前**的 matches 写的，裁剪之后就自相矛盾了
            //    （卡片显示 1 条、总结里写着"找到 3 条"）。没裁剪时它和自己的结果一致，保留原文更自然。
            if (selection.shown().size() < all.size()) {
                result.setAnswer(buildDisplayAnswer(selection.shown().size(), selection.belowThreshold()));
            } else {
                result.setAnswer(json.getStr("answer", ""));
            }
        } catch (Exception e) {
            // 大模型偶尔不按格式返回（最常见的是套一层 ```json 代码块围栏）。
            // 解析失败不能让整个接口挂掉 —— 把原文塞进 answer，前端至少还能显示。
            log.warn("AI 返回内容不是合法 JSON，降级为纯文本展示: {}", call.content());
            result.setAnswer(call.content());
        }

        result.setElapsedMs(System.currentTimeMillis() - start);
        log.info("AI 匹配完成: 候选 {} 条, 命中 {} 条, prompt {} tokens, 耗时 {} ms",
                result.getCandidateCount(), result.getMatches().size(),
                result.getPromptTokens(), result.getElapsedMs());
        return result;
    }

    // ===================== 私有方法 =====================
    //
    // 候选物品的取法已移交 CandidateService:
    //   改造前：itemService.page(最近 30 条) —— 取的是「最近」不是「最像」，池外命中率 0%
    //   改造后：CandidateService.recall()  —— 全量候选 + 2-gram 打分取 Top-K
    // 保留这条注释是因为「为什么换掉」比「换成了什么」更值得记住。

    /** 一次大模型调用的结果：正文 + token 用量 */
    private record ApiCall(String content, int promptTokens, int completionTokens) {
    }

    /**
     * 统一调用大模型，集中处理超时、网络异常和重试。
     * <p>
     * 具体调哪家由 application.yml 的 llm.* 决定 —— 都是 OpenAI 兼容接口。
     * <p>
     * <b>为什么一定要重试</b>：实测硅基流动免费档存在偶发的长尾延迟 ——
     * 大多数请求 2-3 秒返回，但少数会挂死超过 30 秒的读超时。
     * 评测 146 条时降级率在 1.4% ~ 32.2% 之间剧烈波动，全是这个原因。
     * 而超时重试对免费额度来说是零成本的，收益极大。
     *
     * @return 调用结果；重试后仍失败返回 {@code null}，由调用方决定怎么降级
     */
    private ApiCall callLlm(JSONObject body) {
        return callLlm(body, 0);
    }

    private ApiCall callLlm(JSONObject body, int attempt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmConfig.getApiKey());
        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        try {
            ResponseEntity<String> response = llmRestTemplate.postForEntity(
                    llmConfig.getApiUrl(), entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.error("大模型 API 返回异常状态码: {}", response.getStatusCode());
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            // 把 token 用量带出来 —— 评测要拿它对比优化前后的成本，
            // 改造前这段数据是被丢掉的，导致「省了多少 token」只能靠估
            JSONObject usage = json.getJSONObject("usage");
            int promptTokens = usage == null ? 0 : usage.getInt("prompt_tokens", 0);
            int completionTokens = usage == null ? 0 : usage.getInt("completion_tokens", 0);

            return new ApiCall(content, promptTokens, completionTokens);

        } catch (RestClientException e) {
            // 连接超时、读取超时、网络异常统一在这里处理
            if (attempt < maxRetries && RetrySupport.isRetryable(e)) {
                long waitMs = RetrySupport.backoffMs(attempt);
                log.warn("大模型调用失败（第 {} 次尝试），{}ms 后重试: {}",
                        attempt + 1, waitMs, e.getMessage());
                RetrySupport.sleepQuietly(waitMs);
                return callLlm(body, attempt + 1);
            }
            log.error("调用大模型 API 失败（共尝试 {} 次）", attempt + 1, e);
            return null;
        } catch (Exception e) {
            // 解析失败属于"调用成功了但返回体不对"，重试大概率还是同样的结果
            log.error("解析大模型返回内容失败", e);
            return null;
        }
    }

    /**
     * 重试判断已抽到 {@link com.lostfound.common.RetrySupport} ——
     * 接向量接口（P3）时要用一模一样的规则，与其复制一份让两边各自演进，
     * 不如让它只有一个出处。
     */

    /**
     * 把大模型返回的 matches 数组转成 {@link MatchResult}。
     * <p>
     * 两个防护：
     * <ol>
     *   <li><b>幻觉防护</b>：itemId 不在候选列表里的直接丢弃。
     *       大模型确实会编出不存在的 id，不校验就会给用户返回一个点不开的结果。</li>
     *   <li><b>本地回填</b>：title/category/location 一律用本地数据，
     *       不用大模型返回的 —— 它只负责判断「哪条最像」，回填是我们的事。</li>
     * </ol>
     */
    static List<MatchResult> toMatchResults(JSONArray arr, List<Item> candidates) {
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }

        Map<Long, Item> byId = new HashMap<>();
        for (Item item : candidates) {
            byId.put(item.getId(), item);
        }

        List<MatchResult> list = new ArrayList<>();
        for (Object obj : arr) {
            if (!(obj instanceof JSONObject m)) {
                continue;
            }
            Long itemId = m.getLong("itemId");
            Item item = itemId == null ? null : byId.get(itemId);
            if (item == null) {
                log.warn("大模型返回了不在候选列表中的 itemId={}，已丢弃（幻觉防护）", itemId);
                continue;
            }

            MatchResult r = new MatchResult();
            r.setItemId(item.getId());
            r.setTitle(item.getTitle());
            r.setCategory(item.getCategory());
            r.setLocation(item.getLocation());
            r.setScore(parseScore(m, item.getId()));
            r.setReason(m.getStr("reason", ""));
            list.add(r);
        }

        list.sort(Comparator.comparingInt(MatchResult::getScore).reversed());
        return list;
    }

    /**
     * 解析模型给的 score，并**把异常情况记进日志、不静默吞掉**。
     *
     * <p>为什么不能就这么写 {@code getInt("score", 0)}：那样"模型没填"和"模型给了 0 分"
     * 长得一模一样。加了最低分过滤之后，一条本可以展示的结果会因为缺个字段而无声消失，
     * 用户和我们都不会知道 —— 这正是这个项目反复踩过的同一类坑：
     * 降级/异常可以容忍，但<b>不能不可观测</b>。
     *
     * <p>缺失或非法的 score 一律按 0 处理（宁可少展示一条，也不要拿脏数据当高分），
     * 但会留下 warn 日志和 {@code belowThresholdCount} 上的痕迹。
     */
    private static int parseScore(JSONObject m, Long itemId) {
        Object raw = m.get("score");
        if (raw == null) {
            log.warn("大模型未返回 score 字段，按 0 分处理: itemId={}", itemId);
            return 0;
        }
        Integer value = null;
        if (raw instanceof Number n) {
            value = n.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                // 落到下面统一记日志
            }
        }
        if (value == null || value < 0 || value > 100) {
            log.warn("大模型返回的 score 非法，按 0 分处理: itemId={}, score={}", itemId, raw);
            return 0;
        }
        return value;
    }

    /** 展示筛选的结果：给用户看的列表 + 因为分数不够被挡下的条数 */
    record DisplaySelection(List<MatchResult> shown, int belowThreshold) {
    }

    /**
     * 展示规则 —— 先按最低分过滤，再截断条数。<b>不重新排序</b>。
     *
     * <p>为什么顺序不能动：入参已经按分数降序排好（{@link #toMatchResults} 排的）。
     * 保持这个顺序有两个后果：① 前端拿到的就是"最像的在前"；
     * ② 阈值过滤等价于<b>保留列表的前缀</b> —— 而实测数据显示正确答案有约 85% 排在第一位，
     * 所以只要还有一条过线，正确结果几乎不会被误伤。风险集中在"一条都不剩"，不在"砍掉了尾巴"。
     *
     * <p>抽成 static 纯函数是为了能单测 —— AI 链路原本一个单元测试都没有，
     * 只有 146 条评测兜着，而评测要花钱、跑得慢、还不能断言边界（79/80/81）。
     *
     * <p>⚠️ <b>这里不做任何钳位</b>：`minScore` / `maxResults` 的合法性由
     * {@link #validateMatchRules()} 在启动时把关，非法配置根本进不来。
     * 函数只负责按规则执行，不再"偷偷纠正"调用方 —— 两处都改一半，才最容易出对不上的口径。
     *
     * @param minScore   0~100（0 表示关闭过滤），调用方已保证
     * @param maxResults 1~{@value #MAX_ALLOWED_RESULTS}，调用方已保证
     */
    static DisplaySelection applyDisplayRules(List<MatchResult> all, int minScore, int maxResults) {
        if (all == null || all.isEmpty()) {
            return new DisplaySelection(List.of(), 0);
        }
        List<MatchResult> passed = all.stream()
                .filter(m -> m.getScore() != null && m.getScore() >= minScore)
                .toList();
        int belowThreshold = all.size() - passed.size();
        List<MatchResult> shown = passed.size() <= maxResults ? passed : passed.subList(0, maxResults);
        return new DisplaySelection(List.copyOf(shown), belowThreshold);
    }

    /**
     * 裁剪过结果之后，由后端自己生成一句和实际展示完全对得上的话。
     *
     * <p>三种情况，措辞都不能含糊：
     * <ul>
     *   <li><b>有结果</b>：直接说找到几条，不夸大；</li>
     *   <li><b>有结果但都被门槛挡下</b>：说"暂未发现高度匹配的物品"，并说明还有几条相似度低的 ——
     *       <b>绝不能写成"没有匹配的物品"</b>，那会让用户以为平台上一定没有自己的东西而直接放弃，
     *       而失物招领场景里"用户因为没看到而放弃"正是最贵的损失。
     *       （主动匹配不过滤，所以这一支目前只有自动匹配会走到）</li>
     *   <li><b>模型确实一条都没返</b>：给兜底引导。</li>
     * </ul>
     */
    private static String buildDisplayAnswer(int shownCount, int belowThreshold) {
        if (shownCount > 0) {
            return "为你找到 " + shownCount + " 条匹配度较高的招领信息，"
                    + "点开可以看详情、申请认领。";
        }
        if (belowThreshold > 0) {
            return "暂未发现高度匹配的物品。另外有 " + belowThreshold + " 条相似度较低的结果，"
                    + "可以补充颜色、品牌型号、丢失地点或一眼能认出的特征后，换个说法再试试。";
        }
        return "没有匹配到招领信息。可以换个说法再试试，或者直接去失物招领处登记一下。";
    }

    /**
     * 把候选物品格式化成给大模型看的紧凑文本。
     * <p>
     * 改造前直接用的是 {@code List<Item>.toString()}（Lombok 的 @Data 生成的），
     * 输出长这样：
     * <pre>
     * Item(id=1000, userId=2, title=黑色iPhone 15, type=FOUND, category=电子产品,
     *      location=图书馆三楼, description=..., imageUrl=null, contact=null,
     *      status=UNCLAIMED, version=0, createdAt=2026-09-15T01:44:48,
     *      updatedAt=2026-09-15T01:44:48)
     * </pre>
     * <p>
     * 问题有两个：
     * <ol>
     *   <li><b>大部分字段是噪音</b>。模型要判断「这像不像我丢的东西」，只需要
     *       标题 / 类别 / 地点 / 描述。userId、version、imageUrl、contact、status、
     *       type、createdAt 它一个都用不上，却占了 70% 以上的 token</li>
     *   <li><b>字段名本身也在烧钱</b>。{@code imageUrl=null} 这种不但没信息，
     *       还占 5 个 token，30 条就是 150 个</li>
     * </ol>
     * <p>
     * 换成一行一条的管道分隔格式，格式说明只在开头写一次，
     * 不用每条都重复字段名。
     * <p>
     * 注意：这一步<b>只改表示，不改候选取哪些</b>，所以命中率不该变化，
     * 收益体现在 token 成本上。两个改动分开做，才能分别量化各自的效果。
     */
    private String formatCandidates(List<Item> candidates) {
        StringBuilder sb = new StringBuilder("候选物品（格式：id|标题|类别|地点|描述）：\n");
        for (Item item : candidates) {
            sb.append(item.getId()).append('|')
                    .append(nvl(item.getTitle())).append('|')
                    .append(nvl(item.getCategory())).append('|')
                    .append(nvl(item.getLocation())).append('|')
                    .append(truncate(nvl(item.getDescription()), DESC_MAX_LEN))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 防止某条描述特别长把 prompt 撑爆 */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 构建问答请求体 */
    private JSONObject buildChatBody(String question) {
        JSONObject body = new JSONObject();
        body.set("model", llmConfig.getModel());
        body.set("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", question)
        ));
        body.set("temperature", 0.7);
        body.set("max_tokens", llmConfig.getMaxTokens());
        return body;
    }

    /** 构建匹配请求体。temperature=0 是因为匹配要可复现，不能每次给出不同结果 */
    private JSONObject buildMatchBody(String prompt) {
        JSONObject body = new JSONObject();
        body.set("model", llmConfig.getModel());
        body.set("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        ));
        body.set("temperature", 0);
        body.set("response_format", Map.of("type", "json_object"));
        body.set("max_tokens", llmConfig.getMaxTokens());
        return body;
    }
}
