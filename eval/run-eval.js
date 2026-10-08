/**
 * AI 物品匹配评测执行器。
 *
 * 用法：node eval/run-eval.js <标签>
 *   标签用来区分不同阶段的报告，比如 baseline / after-p2 / after-p4。
 *   同一个评测集 + 同一个标签规则，跑出来的数字才能横向比。
 *
 * 指标：
 *   Hit@K  —— 标准答案有没有出现在返回的前 K 条里（每个用例只有一个标准答案，所以等同 Recall@K）
 *   MRR    —— 标准答案排名的倒数均值。只命中但排第 5，和排第 1，差别很大，Hit@K 看不出来
 *
 * 为什么必须分层看：
 *   池外用例的失败**不是匹配算法的问题**，是候选池根本没覆盖到。
 *   混在一起算平均，会把「召回问题」和「排序问题」搅成一团，看不出该优化哪边。
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { DEFAULT_API, assertEvalBackend } from './eval-guard.js';

// 目标后端地址：默认主环境端口，评测时用 AI_API 指到评测环境（8081）。
// 连错环境的代价不是报错，而是**静默给出错误的指标** —— 所以下面还会做目标检查。
const API = DEFAULT_API;

/** 需人工复核、暂不纳入主要指标的样本（判据与原因见该文件） */
const EXCLUDE_FILE = 'eval/excluded-samples.json';
const LABEL = process.argv[2] || 'baseline';
const DATASET = 'eval/dataset.jsonl';
const KS = [1, 3, 5];

async function login() {
  const res = await fetch(`${API}/api/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'evalbot', password: 'eval123456' }),
  });
  const json = await res.json();
  if (!json.data?.token) throw new Error('登录失败: ' + JSON.stringify(json));
  return json.data.token;
}

/**
 * 调一次匹配接口。
 *
 * ⚠️ 必须带重试。批量打真实外部 API 一定会撞限流（实测并发 4 就开始吃 429），
 * 不重试的话失败的用例会被当成「没命中」算进 Recall —— 指标静默变差，
 * 而你会以为是算法不行。这和「降级吞掉异常」是同一类问题：
 * 失败必须被显式处理，不能让它混进正常结果里。
 */
async function query(token, question, attempt = 0) {
  const res = await fetch(`${API}/api/ai/query`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ question }),
  });

  // 429 限流 / 5xx 服务端抖动 → 指数退避重试
  if (res.status === 429 || res.status >= 500) {
    if (attempt >= 5) {
      throw new Error(`HTTP ${res.status}（退避重试 5 次仍失败）`);
    }
    const waitMs = 1000 * 2 ** attempt + Math.floor(Math.random() * 400);
    await new Promise((r) => setTimeout(r, waitMs));
    return query(token, question, attempt + 1);
  }

  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== 200) throw new Error(`code ${json.code}: ${json.message}`);
  return json.data;
}

async function pool(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  let done = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (true) {
        const i = cursor++;
        if (i >= items.length) return;
        try {
          results[i] = await fn(items[i], i);
        } catch (e) {
          console.error(`  [${i}] 失败: ${e.message}`);
          // 标记成 error 而不是 rank=0 —— 这两者必须分开：
          // rank=0 是「算法没找到」，error 是「这次请求压根没成功」。
          // 混在一起算，限流会让 Recall 凭空掉一截，而且看不出来原因。
          results[i] = {
            ...items[i],
            error: e.message,
            rank: 0,
            recallRank: 0,
            matched: 0,
            returnedIds: [],
            // 字段与成功行保持一致，免得下游脚本还要判断字段存不存在
            scores: [],
            model: null,
            minScore: null,
            maxResults: null,
            belowThresholdCount: null,
            promptTokens: 0,
            completionTokens: 0,
            elapsedMs: 0,
          };
        }
        if (++done % 25 === 0) console.error(`  进度 ${done}/${items.length}`);
      }
    })
  );
  return results;
}

/** 算标准答案在返回列表里的排名，1 开始；没命中返回 0 */
function rankOf(data, groundTruthId) {
  const matches = data.matches || [];
  const idx = matches.findIndex((m) => m.itemId === groundTruthId);
  return idx < 0 ? 0 : idx + 1;
}

function summarize(rows) {
  const n = rows.length;
  if (!n) return { n: 0 };
  const out = { n };
  for (const k of KS) {
    const hit = rows.filter((r) => r.rank > 0 && r.rank <= k).length;
    out[`hit${k}`] = hit / n;
  }
  out.mrr = rows.reduce((s, r) => s + (r.rank > 0 ? 1 / r.rank : 0), 0) / n;
  // 召回层成功率：标准答案有没有进候选列表
  out.recallRate = rows.filter((r) => r.recallRank > 0).length / n;
  out.avgPromptTokens = rows.reduce((s, r) => s + (r.promptTokens || 0), 0) / n;
  out.totalTokens = rows.reduce((s, r) => s + (r.promptTokens || 0) + (r.completionTokens || 0), 0);
  out.avgMs = rows.reduce((s, r) => s + (r.elapsedMs || 0), 0) / n;
  out.emptyRate = rows.filter((r) => r.matched === 0).length / n;
  return out;
}

const pct = (v) => (v === undefined ? '-' : (v * 100).toFixed(1) + '%');

function table(title, groups) {
  const lines = [`### ${title}`, '', '| 分组 | 用例数 | Hit@1 | Hit@3 | Hit@5 | MRR | 平均 prompt tokens |', '|---|---:|---:|---:|---:|---:|---:|'];
  for (const [name, s] of Object.entries(groups)) {
    if (!s.n) continue;
    lines.push(`| ${name} | ${s.n} | ${pct(s.hit1)} | ${pct(s.hit3)} | ${pct(s.hit5)} | ${s.mrr.toFixed(3)} | ${Math.round(s.avgPromptTokens)} |`);
  }
  return lines.join('\n');
}

function groupBy(rows, key) {
  const g = {};
  for (const r of rows) {
    (g[r[key]] ||= []).push(r);
  }
  return Object.fromEntries(Object.entries(g).map(([k, v]) => [k, summarize(v)]));
}

async function main() {
  const dataset = readFileSync(DATASET, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
  console.log(`评测集 ${dataset.length} 条，标签 = ${LABEL}`);

  // ── 「待人工复核」排除清单（放在最前，空跑与正式跑共用）──
  // 判定标准与逐条原因见 eval/excluded-samples.json。
  // 强调：这里是**把样本排除出指标**，不是放宽命中判定 —— 命中始终严格要求 itemId 相等，
  // 绝不接受「同标题任意一条都算对」（同标题的两个条目通常是两件不同的真实物品）。
  let excludedIds = new Set();
  let exclusion = null;
  try {
    exclusion = JSON.parse(readFileSync(EXCLUDE_FILE, 'utf8'));
    excludedIds = new Set(exclusion.excluded.map((e) => e.groundTruthId));
  } catch {
    console.warn(`⚠️  没找到 ${EXCLUDE_FILE}，本次按全部样本统计（含不可辨识的样本）。`);
  }
  const effectiveCount = dataset.filter((d) => !excludedIds.has(d.groundTruthId)).length;

  // 空跑：只确认数据集、排除清单、目标地址就位 —— 不连后端、不调模型、不花钱
  if (process.argv.includes('--dry')) {
    console.log(`\n【空跑模式】`);
    console.log(`  目标后端    ：${API}（正式跑前会做「是否误连主库」检查）`);
    console.log(`  有效样本    ：${effectiveCount} / ${dataset.length}` +
      (exclusion ? `（排除 ${exclusion.excluded.length} 条待人工复核）` : ''));
    if (exclusion) {
      exclusion.excluded.forEach((e) =>
        console.log(`     ${e.level}  #${e.groundTruthId}  ${e.title || ''} — ${e.reason?.slice(0, 40) || ''}…`));
    }
    console.log(`  报告与明细将写入：eval/report-${LABEL}.md、eval/results-${LABEL}.jsonl`);
    console.log('\n（空跑结束，未发起任何请求）');
    return;
  }

  // 先确认连的是干净评测库 —— 连到主库的话，12.6 万条压测数据会把指标彻底污染
  await assertEvalBackend(API);

  const token = await login();
  const started = Date.now();

  // 并发压到 2：实测并发 4 会持续吃 429。
  // 评测不是压测，跑慢一点没关系，结果可信更重要。
  const rows = await pool(dataset, 2, async (d) => {
    const data = await query(token, d.query);
    return {
      ...d,
      // 精排层：标准答案在最终 matches 里的排名
      rank: rankOf(data, d.groundTruthId),
      // 召回层：标准答案在候选列表里的排名（0 = 压根没召回到）
      // 有了这个才能分清失败发生在哪一层 —— 只看 rank 的话，
      // 「没召回到」和「召回到了但没挑中」长得一模一样，但修法完全不同
      recallRank: (data.candidateIds || []).indexOf(d.groundTruthId) + 1,
      matched: (data.matches || []).length,
      returnedIds: (data.matches || []).map((m) => m.itemId),
      // ⚠️ 逐条 match 的分数必须落盘。
      //    只存 returnedIds 的话，「第 3 位那条打了多少分」就永远丢了 ——
      //    而调阈值全靠这个分布：存了它，70/75/80/85/90 就能**离线**反复比较，不用再花钱重跑。
      //    历史报告就是缺这一项，导致"80 分合不合适"至今没有数据支撑。
      scores: (data.matches || []).map((m) => ({ itemId: m.itemId, score: m.score })),
      // 本次真正生效的模型与展示规则，由后端在响应里回显 ——
      // 比让人记得在命令行传对环境变量可靠得多（历史报告里连模型都没记，事后无法区分对照组）
      model: data.model ?? null,
      minScore: data.minScore ?? null,
      maxResults: data.maxResults ?? null,
      belowThresholdCount: data.belowThresholdCount ?? null,
      candidateCount: data.candidateCount,
      promptTokens: data.promptTokens,
      completionTokens: data.completionTokens,
      elapsedMs: data.elapsedMs,
      answer: data.answer,
      // ⚠️ 后端降级标记。没有它的话，「大模型调用失败」和「匹配了但没找到」
      // 在外面看完全一样（都是 HTTP 200 + 一段正常文案），
      // 失败的用例会被当成未命中算进指标 —— 实测因此得出过完全颠倒的结论。
      degradeReason: data.degradeReason || null,
    };
  });

  const wall = ((Date.now() - started) / 1000).toFixed(0);
  writeFileSync(`eval/results-${LABEL}.jsonl`, rows.map((r) => JSON.stringify(r)).join('\n') + '\n');

  // 分三类，绝不能混在一起算：
  //   llmFailed    后端调大模型失败，返回了降级文案 → 既不是命中也不是未命中，是"没测成"
  //   noCandidates 召回确实为空，压根没调大模型   → 这是正常的未命中
  //   errored      连我们的后端都没调通
  const llmFailed = rows.filter((r) => r.degradeReason === 'LLM_ERROR');
  const noCandidates = rows.filter((r) => r.degradeReason === 'NO_CANDIDATES');
  const errored = rows.filter((r) => r.error);
  const ok = rows.filter((r) => !r.error && r.degradeReason !== 'LLM_ERROR');

  if (llmFailed.length) {
    console.error(`\n⚠️  ${llmFailed.length} 条用例的大模型调用失败（已从指标中剔除）`);
    console.error(`   降级率 ${((llmFailed.length / rows.length) * 100).toFixed(1)}% —— 这是可用性指标，和命中率同等重要`);
  }
  if (errored.length) {
    console.error(`   另有 ${errored.length} 条请求我们的后端就失败了`);
  }
  const degradeRate = llmFailed.length / rows.length;
  if (degradeRate > 0.05) {
    console.error(`🔴 降级率 ${(degradeRate * 100).toFixed(1)}% 过高 —— 说明这家供应商的可用性有问题，报告要谨慎解读`);
  }

  // ── 把「待人工复核」的样本排除出主要指标（清单在最前面已加载）──
  const effective = ok.filter((r) => !excludedIds.has(r.groundTruthId));
  const excludedRows = ok.filter((r) => excludedIds.has(r.groundTruthId));

  const overall = summarize(effective);
  const byStratum = groupBy(effective, 'stratum');
  const byDiff = groupBy(effective, 'difficulty');

  // 失败归因 —— 把未命中的用例拆成两类，这两类要修的地方完全不同
  const recalled = effective.filter((r) => r.recallRank > 0);
  const missed = effective.filter((r) => r.rank === 0);
  const missByRecall = missed.filter((r) => r.recallRank === 0);      // 召回层就没捞到
  const missByRerank = missed.filter((r) => r.recallRank > 0);        // 捞到了但没挑中

  // 本次跑的模型与生效的展示规则，取自后端在响应里的回显（任一成功行都带）。
  // 记下来是为了让报告自证条件：历史报告没写模型，事后根本分不清哪份是哪个模型跑的。
  const sample = rows.find((r) => !r.error) || {};
  const runModel = sample.model ?? null;
  const runMinScore = sample.minScore ?? null;
  const runMaxResults = sample.maxResults ?? null;
  const belowTotal = rows.reduce((s, r) => s + (r.belowThresholdCount || 0), 0);

  if (!runModel) {
    console.error('\n⚠️  后端没有回显 model —— 这份报告无法区分模型，别拿它和别的报告横比。');
  }
  if (runMinScore !== null && runMinScore > 0) {
    // 主动匹配按设计不过滤（NO_SCORE_FILTER）。真触发说明有人给主动路径加了门槛 —— 得查。
    console.error(`\n⚠️  本次 minScore=${runMinScore} —— 主动匹配本不该过滤分数，返回值已被裁剪。`);
    console.error('   这份结果的分数分布是不完整的。请检查 AiServiceImpl#query 传的是不是 NO_SCORE_FILTER。');
  }

  const md = [
    `# AI 物品匹配评测报告 — ${LABEL}`,
    '',
    `- 评测集：\`${DATASET}\`（${dataset.length} 条）`,
    `- **模型**：${runModel ?? '⚠️ 未记录（后端未回显 model）'}`,
    `- **筛选策略**：主动匹配 = 按分数降序取 Top-${runMaxResults ?? '?'}，**不设最低分门槛**` +
      (runMinScore === 0 ? ' ✅' : `（⚠️ 本次回显 minScore=${runMinScore}，分数分布不完整）`),
    `- 被门槛挡下的条数合计：${belowTotal}（主动匹配应为 0；逐条明细见 \`results-${LABEL}.jsonl\` 的 \`scores\` 字段）`,
    `- 执行时间：${new Date().toISOString().slice(0, 19).replace('T', ' ')} UTC，总耗时 ${wall}s`,
    `- 说明：每个用例只有一个标准答案，所以 Hit@K 等于 Recall@K`,
    `- 参与统计：${effective.length} 条`,
    `- **有效样本：${effective.length} / ${dataset.length}**` +
      (exclusion
        ? `（排除 ${excludedRows.length} 条待人工复核的样本，逐条原因见 \`${EXCLUDE_FILE}\`）`
        : '（未找到排除清单，全量统计）'),
    '- ⚠️ **本报告是「干净评测环境」下的新基线**：跑在 lost_found_eval（只装 2000 条种子），' +
      '而 `report-baseline` ~ `report-qwen-retry` 那批跑在主库、且早于压测数据入库 —— ' +
      '池子规模差了 60 倍，**两组数字不可直接比较**。',
    `- **大模型降级率**：${((llmFailed.length / rows.length) * 100).toFixed(1)}%（${llmFailed.length} 条调用失败已剔除）` +
      (degradeRate > 0.05 ? ' 🔴 过高，供应商可用性存疑' : ''),
    `- 召回为空（正常路径）：${noCandidates.length} 条`,
    '',
    '## 总览',
    '',
    '| 指标 | 值 |',
    '|---|---:|',
    `| Hit@1 | ${pct(overall.hit1)} |`,
    `| Hit@3 | ${pct(overall.hit3)} |`,
    `| Hit@5 | ${pct(overall.hit5)} |`,
    `| MRR | ${overall.mrr.toFixed(3)} |`,
    `| **召回率**（标准答案进了候选列表） | ${pct(overall.recallRate)} |`,
    `| 返回空结果的用例占比 | ${pct(overall.emptyRate)} |`,
    `| 平均 prompt tokens / 次 | ${Math.round(overall.avgPromptTokens)} |`,
    `| 全量总 tokens | ${overall.totalTokens} |`,
    `| 平均耗时 | ${Math.round(overall.avgMs)} ms |`,
    '',
    '## 失败归因',
    '',
    '只看最终命中率的话，「没召回到」和「召回到了但没挑中」长得一模一样，',
    '但这两类要修的地方完全不同。拆开看：',
    '',
    '| 失败类型 | 数量 | 占全部用例 | 该修哪里 |',
    '|---|---:|---:|---|',
    `| 召回层没捞到 | ${missByRecall.length} | ${pct(missByRecall.length / effective.length)} | 改召回（换打分算法 / 扩 Top-K） |`,
    `| 召回到了但精排没选中 | ${missByRerank.length} | ${pct(missByRerank.length / effective.length)} | 改精排（prompt / 候选表示 / 模型） |`,
    '',
    `召回层总体成功率：**${pct(overall.recallRate)}**（${recalled.length}/${effective.length} 条标准答案进了候选列表）`,
    '',
    table('按候选池分层', byStratum),
    '',
    '> `in_pool` = 标准答案落在系统候选池（最近 30 条）内，考的是**匹配质量**。',
    '> `out_pool` = 标准答案不在候选池里，考的是**召回能力** —— 这一层的失败和排序算法无关，',
    '> 是压根没机会进入候选列表。',
    '',
    table('按难度分层', byDiff),
    '',
    '## 失败样例（前 10 条）',
    '',
  ];

  const failed = effective.filter((r) => r.rank === 0).slice(0, 10);
  if (!failed.length) {
    md.push('（无）');
  } else {
    for (const r of failed) {
      const reason = r.recallRank === 0
        ? '❌ 召回层就没捞到'
        : `⚠️ 召回到了（召回排名第 ${r.recallRank}）但精排没选中`;
      md.push(`- **[${r.stratum}/${r.difficulty}]** 「${r.query}」`);
      md.push(`  - 标准答案：\`#${r.groundTruthId}\` ${r.groundTruthTitle}`);
      md.push(`  - ${reason}`);
      md.push(`  - 实际返回：${r.returnedIds.length ? r.returnedIds.map((i) => '`#' + i + '`').join(', ') : '空'}`);
    }
  }

  writeFileSync(`eval/report-${LABEL}.md`, md.join('\n') + '\n');

  console.log('\n=== 总览 ===');
  console.log(`Hit@1 ${pct(overall.hit1)} | Hit@3 ${pct(overall.hit3)} | Hit@5 ${pct(overall.hit5)} | MRR ${overall.mrr.toFixed(3)}`);
  console.log(`召回率 ${pct(overall.recallRate)} | 平均 prompt tokens ${Math.round(overall.avgPromptTokens)} | 总 tokens ${overall.totalTokens} | 平均耗时 ${Math.round(overall.avgMs)}ms`);
  console.log(`\n失败归因：召回没捞到 ${missByRecall.length} 条 / 召回到了没挑中 ${missByRerank.length} 条`);
  console.log('\n=== 分层 ===');
  for (const [k, s] of Object.entries(byStratum)) {
    console.log(`${k.padEnd(10)} n=${s.n}  Hit@5 ${pct(s.hit5)}  MRR ${s.mrr.toFixed(3)}`);
  }
  console.log(`\n报告已写入 eval/report-${LABEL}.md`);
}

// 接住异常：像「误连主库」这种是**故意的**拦截，应该打一句人话再退出，
// 而不是甩一段 Node 调用栈让人以为是脚本崩了。
//
// ⚠️ 这里用 process.exitCode 而不是 process.exit(1)：后者会在 Windows 上
// 触发 libuv 断言（"Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)"），
// 因为 fetch 的连接还没排空就被强杀了 —— 退出码照样是 1，但会多打一段吓人的崩溃信息。
main().catch((e) => {
  console.error('\n🔴 ' + e.message);
  process.exitCode = 1;
});
