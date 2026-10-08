/**
 * 小规模真实匹配验证 —— 5 条代表性样本，人工确认「80 分阈值会不会漏掉正确答案」。
 *
 * 用法：
 *   node eval/probe-threshold.js --dry     # 只打印样本清单和将要发出的请求，不发任何请求
 *   node eval/probe-threshold.js           # 真跑（会调用 5 次大模型）
 *
 * 前提：<b>主动匹配不过滤分数</b>（见 AiServiceImpl#NO_SCORE_FILTER），所以响应里天然带全了
 * 每条推荐的原始分数 —— 不需要再设任何环境变量去"关掉过滤"。
 * 脚本仍会检查响应回显的 minScore，若哪天有人给主动匹配加回门槛，这里会立刻报警。
 *
 * 只读接口 + 只输出报告，不修改任何匹配逻辑。
 */

import { writeFileSync } from 'node:fs';
import { DEFAULT_API, assertEvalBackend } from './eval-guard.js';

// 目标后端地址：默认主环境端口，评测时用 AI_API 指到评测环境（8081）
const API = DEFAULT_API;
const DRY = process.argv.includes('--dry');
/** 待评估的阈值假设 —— 对应 ai.match.auto.min-score（自动匹配/站内通知用，本轮尚未开发） */
const THRESHOLD = 80;

/**
 * 五条样本。历史数据来自 results-qwen-retry.jsonl（与当前配置同一个模型），
 * 用来对照「这次跑出来的位次有没有漂移」。
 */
const SAMPLES = [
  {
    kind: '简单',
    query: '急死我了，谁在计算机学院机房捡到一把银色的汽车钥匙啊，好像是大众的，麻烦联系我一下！',
    groundTruthId: 1703,
    groundTruthTitle: '捡到银色车钥匙',
    hist: { rank: 1, recallRank: 2, matched: 3 },
    note: '品牌+颜色+地点齐全，预期拿高分；用来确认高阈值不会误伤"好样本"',
  },
  {
    kind: '中等',
    query: '有没有人在大学生活动中心附近捡到一个银色的手表啊，好像是落在那边了，急死我了',
    groundTruthId: 2132,
    groundTruthTitle: '拾到银色的智能手表',
    hist: { rank: 1, recallRank: 5, matched: 3 },
    note: '描述偏泛，最接近真实用户写法；看它还能拿多少分',
  },
  {
    kind: '困难',
    query: '有没有人这两天在三号楼下捡到一本学Java用的那个“圣经”啊，封面是机械工业出版社的，我快急死了！',
    groundTruthId: 1757,
    groundTruthTitle: '机械工业 第四版 《Java 编程思想》',
    hist: { rank: 1, recallRank: 2, matched: 5 },
    note: '线索是间接的（"圣经"是外号、"机械工业"是出版社），分数很可能偏低 —— 阈值风险最高的样本',
  },
  {
    kind: '排第2-5位',
    query: '刚在学生活动中心那边丢了一张蓝色的学生卡，好像是掉在哪个角落了，有没有好心人看到啊，急死了！',
    groundTruthId: 2453,
    groundTruthTitle: '校园卡',
    hist: { rank: 4, recallRank: 43, matched: 5 },
    note: '历史精排第 4 位（前面有 3 条更像的），分数大概率压在低位 —— 专门用来看阈值会不会砍掉尾巴上的正确答案',
  },
  {
    kind: '精排失败',
    query: '今天下午在报告厅好像落了一副白色的无线耳机，不知道有没有人捡到？',
    groundTruthId: 2302,
    groundTruthTitle: '华为 FreeBuds Pro 3 蓝牙耳机（白色）',
    hist: { rank: 0, recallRank: 1, matched: 3 },
    note: '历史上召回排第 1、模型却没挑中 —— 用来对照：有一种"没展示"和阈值无关',
  },
];

// ── 与 run-eval.js 同一套鉴权与重试方式 ────────────────────────────

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
 * 单次请求，<b>不重试</b>。
 *
 * <p>和 run-eval.js 不同：那边批量跑 146 条，不重试就会把限流失败当成"没匹配到"算进指标，
 * 所以必须退避重试。这里是 5 条人工验证，要的恰恰是**知道这一轮到底干不干净** ——
 * 重试会把一次失败悄悄变成一次成功，看起来跑通了，实际条件已经变了。
 * 按约定：遇到异常 / 429 / 5xx 立即停止并报告，由人来决定下一步。
 */
async function query(token, question) {
  const res = await fetch(`${API}/api/ai/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ question }),
  });
  if (res.status === 429) throw new Error('HTTP 429 被限流');
  if (res.status >= 500) throw new Error(`HTTP ${res.status} 服务端错误`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== 200) throw new Error(`code ${json.code}: ${json.message}`);
  return json.data;
}

// ── 表格对齐（中文按两个字符宽算，否则列会参差不齐） ──────────────

const displayWidth = (s) =>
  [...String(s)].reduce((w, ch) => w + (ch.codePointAt(0) > 0x1100 ? 2 : 1), 0);

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - displayWidth(s)));

// ── 主流程 ────────────────────────────────────────────────────────

function printDryRun() {
  console.log(`【空跑模式】待验证阈值：${THRESHOLD} 分\n`);
  console.log('本次将要发出的 5 条查询（不会真的发出去）：\n');
  console.log(`${pad('#', 3)}${pad('类型', 12)}${pad('正确答案', 9)}${pad('历史精排', 10)}输入描述`);
  SAMPLES.forEach((s, i) => {
    const hist = s.hist.rank === 0 ? '未命中' : `第 ${s.hist.rank} 位`;
    console.log(`${pad(i + 1, 3)}${pad(s.kind, 12)}${pad('#' + s.groundTruthId, 9)}${pad(hist, 10)}${s.query}`);
  });
  console.log('\n每条要观测的点：');
  SAMPLES.forEach((s, i) => console.log(`  ${i + 1}. [${s.kind}] ${s.note}`));
  console.log('\n正式跑之前请确认：');
  console.log('  · 主动匹配不过滤分数，响应里天然带全每条的原始分，**无需再设任何环境变量**');
  console.log('  · 后端限流够用：默认 5 次/分钟，正好卡在 5 条查询的边界上，');
  console.log('    建议同时设 RATE_LIMIT_AI_MAX_REQUESTS=20，免得重试吃 429');
  console.log('\n（空跑结束，未发起任何请求）');
}

async function main() {
  if (DRY) {
    printDryRun();
    return;
  }

  // 先确认连的是干净评测库 —— 连到主库的话，近重复物品会污染召回与命中判定
  await assertEvalBackend(API);

  const token = await login();
  const rows = [];
  // 一旦某条失败就整体停下：继续跑剩下的只会得到一份"条件不一致"的结果
  let aborted = null;

  for (const s of SAMPLES) {
    let data;
    try {
      data = await query(token, s.query);
    } catch (e) {
      aborted = { kind: s.kind, groundTruthId: s.groundTruthId, reason: e.message };
      console.error(`\n🔴 第 ${rows.length + 1} 条（${s.kind} #${s.groundTruthId}）失败：${e.message}`);
      console.error('   按约定立即停止：不重试、也不继续跑剩下的样本。');
      break;
    }
    const matches = data.matches || [];
    const candidateIds = data.candidateIds || [];

    const inRecallPool = candidateIds.includes(s.groundTruthId);
    const picked = matches.find((m) => m.itemId === s.groundTruthId) || null;
    const score = picked ? picked.score : null;
    const survivesThreshold = score === null ? null : score >= THRESHOLD;

    // 四种结果，只有第 ② 种是阈值造成的
    let verdict;
    if (picked) {
      verdict = survivesThreshold ? '① 命中且高分' : '② 命中但低分 → 会被阈值藏掉';
    } else {
      verdict = inRecallPool ? '③ 精排未选中（与阈值无关）' : '④ 召回未捞到（与阈值无关）';
    }

    rows.push({
      ...s, matches, candidateIds, inRecallPool,
      pickedByLlm: !!picked, score, survivesThreshold, verdict,
      shownCount: matches.length,
      degradeReason: data.degradeReason || null,
      minScore: data.minScore, maxResults: data.maxResults, model: data.model,
      candidateCount: data.candidateCount, elapsedMs: data.elapsedMs,
    });
    console.log(`  ✓ 已跑：${s.kind}（#${s.groundTruthId}）`);
  }

  // 一条都没跑成：没什么可分析的，直接报告失败原因
  if (!rows.length) {
    console.error('\n🔴 一条都没跑成功，不输出对比表。');
    console.error(`   失败原因：${aborted ? aborted.reason : '未知'}`);
    writeResultFile([], aborted, null);
    process.exitCode = 1;
    return;
  }

  // 条件检查：配置不对的话，下面的数字全都不能当依据
  const first = rows[0] || {};
  console.log('\n' + '─'.repeat(100));
  console.log(`模型：${first.model ?? '（后端未回显）'}   |   生效配置：最低分 ${first.minScore} / 最多 ${first.maxResults} 条   |   待验证阈值：${THRESHOLD}`);
  if (first.minScore !== 0) {
    // 主动匹配按设计不过滤；这里不该触发。真触发了说明有人给主动路径加了门槛 —— 得查。
    console.log('\n🔴 警告：本次 minScore 不是 0 —— 主动匹配本不该过滤分数，返回值已被裁剪。');
    console.log('   下面「正确答案得分」那一列可能本身就是缺失的，不能用来判断阈值影响。');
    console.log('   请检查 AiServiceImpl#query 里传的是不是 NO_SCORE_FILTER。');
  }
  const degraded = rows.filter((r) => r.degradeReason);
  if (degraded.length) {
    console.log(`\n⚠️  ${degraded.length} 条走了降级（${degraded.map((r) => r.kind + ':' + r.degradeReason).join(', ')}），`);
    console.log('   这几条的命中与否不算数 —— 调用失败和"没匹配到"必须分开看。');
  }

  // ── 对比表 ──
  console.log('\n' + '─'.repeat(100));
  console.log('【对比表】\n');
  const head = [pad('#', 3), pad('类型', 12), pad('正确答案', 9), pad('召回池', 8),
    pad('候选位次', 10), pad('LLM选中', 9), pad('得分', 6), pad('80分保留', 10),
    pad('展示条数', 10), '判定'];
  console.log(head.join(''));
  rows.forEach((r, i) => {
    const candPos = r.inRecallPool ? `第${r.candidateIds.indexOf(r.groundTruthId) + 1}位` : '—';
    console.log([
      pad(i + 1, 3), pad(r.kind, 12), pad('#' + r.groundTruthId, 9),
      pad(r.inRecallPool ? '是' : '否', 8),
      pad(candPos, 10),
      pad(r.pickedByLlm ? '是' : '否', 9),
      pad(r.score === null ? '—' : r.score, 6),
      pad(r.survivesThreshold === null ? '—' : (r.survivesThreshold ? '保留' : '★被藏掉'), 10),
      pad(r.shownCount, 10),
      r.verdict,
    ].join(''));
  });

  // ── 逐条推荐明细（记录了每条推荐的 itemId 与 score） ──
  console.log('\n【每条查询的推荐明细】（itemId:score）\n');
  rows.forEach((r, i) => {
    const detail = r.matches.map((m) => `${m.itemId}:${m.score}`).join(', ') || '（无推荐）';
    console.log(`  ${i + 1}. [${r.kind}] ${detail}`);
  });

  // ── 结论 ──
  const hitLow = rows.filter((r) => r.verdict.startsWith('②'));
  const hitHigh = rows.filter((r) => r.verdict.startsWith('①'));
  console.log('\n' + '─'.repeat(100));
  console.log('【结论】');
  console.log(`  · 命中且过线（不受阈值影响）：${hitHigh.length} 条`);
  console.log(`  · 命中但会被阈值藏掉      ：${hitLow.length} 条` +
    (hitLow.length ? `  ← ${hitLow.map((r) => '#' + r.groundTruthId + '(' + r.score + '分)').join(' ')}` : ''));
  console.log(`  · 与阈值无关的失败        ：${rows.length - hitHigh.length - hitLow.length} 条（精排未选中 / 召回未捞到）`);
  console.log('\n  ⚠️ 样本只有 5 条，这里的比例不能外推成"整体命中率会掉多少" ——');
  console.log('     它只能回答一个问题：**80 分这个位置，会不会把正确答案一起砍掉**。');

  if (aborted) {
    console.log('\n🔴 本轮**没有跑完**：' + `第 ${rows.length + 1} 条（${aborted.kind} #${aborted.groundTruthId}）失败 —— ${aborted.reason}`);
    console.log('   下面这张表只包含成功的那 ' + rows.length + ' 条，不能当成完整结论。');
  }

  writeResultFile(rows, aborted, first);
  if (aborted) {
    process.exitCode = 1;
  }
}

/** 落盘：成功多少条就记多少条，失败信息一并带上，避免"看起来跑完了" */
function writeResultFile(rows, aborted, first) {
  const outFile = 'eval/probe-threshold-result.json';
  const head = first || rows[0] || {};
  writeFileSync(outFile, JSON.stringify({
    ranAt: new Date().toISOString(),
    threshold: THRESHOLD,
    model: head.model ?? null,
    minScore: head.minScore ?? null,
    maxResults: head.maxResults ?? null,
    completed: !aborted && rows.length === SAMPLES.length,
    aborted,
    rows: rows.map((r) => ({
      kind: r.kind, query: r.query, groundTruthId: r.groundTruthId,
      inRecallPool: r.inRecallPool, candidateRank: r.inRecallPool ? r.candidateIds.indexOf(r.groundTruthId) + 1 : 0,
      pickedByLlm: r.pickedByLlm, score: r.score, survivesThreshold: r.survivesThreshold,
      shownCount: r.shownCount, verdict: r.verdict, degradeReason: r.degradeReason,
      recommendations: r.matches.map((m) => ({ itemId: m.itemId, score: m.score })),
    })),
  }, null, 2) + '\n');
  console.log(`\n明细已写入 ${outFile}`);
}

main().catch((e) => {
  console.error('\n运行失败：', e.message);
  process.exit(1);
});
