#!/usr/bin/env node
/**
 * 用**真实模型**跑一遍多轮样例（少量调用，避免不必要的费用）。
 *
 * 它和 multi-turn-check.js 的分工：
 *   · multi-turn-check.js 用假模型，断言多轮**编排**（会话、条件合并、指代、排除、幂等）；
 *   · 本脚本用真实模型，只看**理解提示词到底管不管用** ——
 *     真实模型会不会按我们要求的 JSON 结构回答、会不会把用户没说过的条件编出来、
 *     会不会把「可能在图书馆」当成确定条件。
 *
 * 它刻意**不做硬断言**：真实模型是概率性的，一次不合规不等于实现有 bug。
 * 它只做结构性的必守检查（不能 500、不能泄露联系方式、失败必须可辨识），
 * 其余结果打印出来给人判断。样本量小，**不足以给出准确率结论**。
 *
 * 用法：
 *   API_BASE=http://127.0.0.1:18081/api \
 *   LLM_MODEL=<本次实际使用的模型名> \
 *   node eval/phase1/real-model-sample.js
 */
const BASE = process.env.API_BASE || 'http://127.0.0.1:18081/api'

/**
 * 八轮样例 —— 与协调者复验用的那组说法保持一致（R9 的原样复现），
 * 外加每轮一条**契约级**断言：意图对不对、已确认的条件有没有被丢掉。
 * 这些断言和"模型聪不聪明"无关，是系统的行为下限。
 */
const TURNS = [
  {
    said: '我丢了一部黑色苹果手机，在图书馆丢的。',
    expect: '颜色、品牌、地点都要抽出来（品牌曾经被漏掉）',
    check: (d) => hasCondition(d, '品牌', '苹果'),
    checkName: '抽出了品牌=苹果',
  },
  {
    said: '地点我记错了，其实是在食堂。',
    expect: '地点被覆盖，旧地点不残留',
    check: (d) => hasCondition(d, '地点', '食堂') && !hasConditionValue(d, '图书馆'),
    checkName: '地点覆盖成食堂且没有残留图书馆',
  },
  {
    said: '颜色我不确定了，去掉颜色这个条件。',
    expect: '颜色被撤回，其余条件保留',
    check: (d) => !hasConditionField(d, '颜色') && hasConditionField(d, '主体'),
    checkName: '颜色被删掉、主体还在',
  },
  {
    said: '第二个为什么像？',
    expect: 'EXPLAIN_ITEM，且解释的是上一轮第 2 张卡（上一轮只有 1 张时，追问才是对的）',
    check: (d, ctx) => (ctx.previousSecondItemId
      ? d.intent === 'EXPLAIN_ITEM' && d.cards?.[0]?.itemId === ctx.previousSecondItemId
      // 上一轮只展示了一条 —— 这时候「第二个」根本不存在，追问才是正确行为
      : d.intent === 'ASK_MORE' && (d.cards || []).length === 0),
    checkName: '按编号解释第 2 条（或如实追问）',
  },
  {
    said: '不是第二个，再帮我找找。',
    expect: 'EXCLUDE_ITEM（不是 SEARCH），且被否的物品不再出现',
    check: (d, ctx) => d.intent === 'EXCLUDE_ITEM'
      && !(d.cards || []).some((c) => c.itemId === ctx.previousSecondItemId),
    checkName: '判成排除并重新检索，被否的那条不再返回',
  },
  {
    said: '我找到手机了，现在换找一把银色车钥匙，挂着红色绳子。',
    expect: '换主体（手机→车钥匙），并抽出银色/红色绳子，且都是 CERTAIN',
    check: (d) => hasCondition(d, '主体', '车钥匙'),
    checkName: '主体切到车钥匙',
    observe: (d) => (hasConditionValue(d, '银色') && hasConditionValue(d, '红色绳子'))
      ? null : '明说的特征没抽全（或没标成确定）',
  },
  {
    said: '钥匙可能落在图书馆，但我不确定。',
    expect: '只是补了个不确定地点 —— 颜色和特征绝不能丢，也绝不能算换物品',
    check: (d) => hasCondition(d, '地点', '图书馆') && hasConditionValue(d, '银色')
      && hasCondition(d, '主体', '车钥匙'),
    checkName: '补地点后颜色、主体都还在（没有误判换物品）',
  },
  {
    said: '如果找到了，平台上怎么申请认领，最后谁确认收到？',
    expect: 'PLATFORM（不是 SEARCH），不搜物品、不改条件',
    check: (d) => d.intent === 'PLATFORM' && (d.cards || []).length === 0,
    checkName: '判成平台问答，没去搜物品',
  },
  {
    // 中间隔了一轮"没有卡片的"平台问答之后再说「第二个」，
    // 编号基准应当仍然是最近一次**搜索结果**（两条钥匙），而不是被打断的那一轮
    said: '那第二个为什么像？',
    expect: 'EXPLAIN_ITEM，且仍然指向最近一次搜索结果里的第 2 条',
    check: (d, ctx) => d.intent === 'EXPLAIN_ITEM'
      && !!ctx.previousSecondItemId
      && d.cards?.[0]?.itemId === ctx.previousSecondItemId,
    checkName: '中间隔了平台问答，编号基准不变',
  },
]

function hasConditionField(turn, field) {
  return (turn.conditions || []).some((c) => c.field === field)
}

function hasCondition(turn, field, value) {
  return (turn.conditions || []).some((c) => c.field === field && c.value.includes(value))
}

function hasConditionValue(turn, value) {
  return (turn.conditions || []).some((c) => c.value.includes(value))
}

let structural = 0
let structuralFailed = 0
const rows = []

function check(name, condition, detail) {
  if (condition) {
    structural++
  } else {
    structuralFailed++
    console.log(`  ✗ [结构性必守] ${name} → ${JSON.stringify(detail)}`)
  }
}

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  return { http: res.status, ...json }
}

async function main() {
  const username = 'phase1_real'
  const password = 'phase1-test-pw'
  await api('/user/register', { method: 'POST', body: { username, password, nickname: username } })
  const login = await api('/user/login', { method: 'POST', body: { username, password } })
  if (login.code !== 200) throw new Error('登录失败: ' + JSON.stringify(login))
  const token = login.data.token

  const conv = await api('/ai/session', { method: 'POST', token })
  const convId = conv.data.id
  console.log(`真实模型多轮样例 —— 会话 ${convId}，共 ${TURNS.length} 轮\n`)

  let totalTokens = 0
  let totalCalls = 0
  let totalMs = 0
  let behaviorPassed = 0
  let behaviorFailed = 0
  const observations = []
  const ctx = {}

  for (const [i, turn] of TURNS.entries()) {
    const res = await api(`/ai/session/${convId}/messages`, {
      method: 'POST',
      token,
      // 带上"我看到的版本"：旧页面里的「第二个」不该被按新结果解析
      body: {
        requestId: `real-${Date.now()}-${i}`,
        content: turn.said,
        conversationVersion: ctx.version,
      },
    })
    if (res.code !== 200) {
      console.log(`第 ${i + 1} 轮「${turn.said}」→ code=${res.code} ${res.message}`)
      rows.push({ turn: turn.said, code: res.code })
      continue
    }
    const d = res.data
    const diag = d.diagnostics || {}
    totalTokens += (diag.promptTokens || 0) + (diag.completionTokens || 0)
    totalCalls += diag.modelCalls || 0
    totalMs += diag.elapsedMs || 0

    console.log(`第 ${i + 1} 轮「${turn.said}」`)
    console.log(`  期望：${turn.expect}`)
    console.log(`  实际：intent=${d.intent} status=${d.status}` +
      (d.degradeReason ? ` degrade=${d.degradeReason}(${diag.degradeDetail || ''})` : ''))
    console.log(`  回复：${(d.text || '').replace(/\n/g, ' / ').slice(0, 120)}`)
    console.log(`  条件：${JSON.stringify((d.conditions || []).map((c) => `${c.field}=${c.value}${c.certainty === 'UNCERTAIN' ? '(不确定)' : ''}`))}`)
    console.log(`  卡片：${JSON.stringify((d.cards || []).map((c) => `#${c.index ?? '-'}:${c.itemId}:${c.title}(${c.status},${c.score ?? '-'})`))}`)
    console.log(`  用量：${diag.modelCalls} 次调用 / ${(diag.promptTokens || 0) + (diag.completionTokens || 0)} tokens / ${diag.elapsedMs}ms\n`)

    // ── 契约级断言：意图对不对、已确认的条件有没有被丢掉。和"模型聪不聪明"无关 ──
    check(`第 ${i + 1} 轮不是 500`, res.http < 500, res)
    check(`第 ${i + 1} 轮失败时有可辨识的 degradeReason`,
      d.status === 'OK' || !!d.degradeReason, d.status)
    check(`第 ${i + 1} 轮一轮最多两次模型调用`, (diag.modelCalls || 0) <= 2, diag.modelCalls)
    check(`第 ${i + 1} 轮的卡片不含联系方式`,
      (d.cards || []).every((c) => !('contact' in c)), Object.keys(d.cards?.[0] || {}))

    if (turn.check) {
      const ok = turn.check(d, ctx)
      if (ok) {
        behaviorPassed++
        console.log(`  ✓ 契约：${turn.checkName}`)
      } else {
        behaviorFailed++
        console.log(`  ✗ 契约：${turn.checkName}`)
      }
    }
    if (turn.observe) {
      const problem = turn.observe(d)
      if (problem) {
        observations.push(`第 ${i + 1} 轮：${problem}`)
        console.log(`  ⚠️ 观察：${problem}`)
      }
    }

    // 编号基准 = 最近一次**真的展示了卡片**的那一轮（和服务的 lastVisibleCards 同一口径）
    if ((d.cards || []).length > 0) {
      ctx.lastCards = d.cards
    }
    ctx.previousSecondItemId = ctx.lastCards?.[1]?.itemId
    ctx.version = d.version

    rows.push({
      turn: turn.said, intent: d.intent, status: d.status,
      conditions: (d.conditions || []).map((c) => `${c.field}=${c.value}`),
      cards: (d.cards || []).map((c) => c.title),
    })
  }

  console.log('—— 汇总 ——')
  console.log(`模型调用共 ${totalCalls} 次，${totalTokens} tokens，累计耗时 ${totalMs}ms`)
  console.log(`结构性必守检查：${structural} 通过 / ${structuralFailed} 失败`)
  console.log(`★ 交叉轮契约检查：${behaviorPassed} 通过 / ${behaviorFailed} 失败`)
  if (observations.length) {
    console.log('观察项（不一定是缺陷，供人工判断）：')
    for (const o of observations) console.log(`  · ${o}`)
  }
  console.log('注意：样例只有 9 轮，**不足以给出准确率结论**，只能看提示词是否被模型遵守。')

  const finalCheck = await api(`/ai/session/${convId}`, { token })
  console.log(`会话最终条件：${JSON.stringify((finalCheck.data?.conditions || []).map((c) => `${c.field}=${c.value}`))}`)
  if (structuralFailed > 0 || behaviorFailed > 0) process.exit(1)
}

main().catch((e) => {
  console.error('脚本异常中止：', e.message)
  process.exit(1)
})
