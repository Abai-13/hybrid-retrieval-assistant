#!/usr/bin/env node
/**
 * ⚠️ 这是一个**假模型**，不是真实大模型，输出效果不能代表线上效果。
 *
 * 它的存在只有一个目的：让第一阶段的**多轮流程**（会话、条件增改撤、指代、排除、
 * 幂等、过期）可以在完全不花模型费用、不需要网络的前提下，端到端跑一遍并被断言。
 * 它按固定的规则匹配用户原话，因此**只能验证编排逻辑，不能验证模型理解能力** ——
 * 真实模型的理解效果必须另外用真实配置跑样例，见 docs/phase1-implementation-report.md。
 *
 * 实现的是 OpenAI 兼容的 /v1/chat/completions，按提示词里出现的段落标题判断
 * 这是三种调用中的哪一种（理解 / 精排 / 解释），然后给出确定性的 JSON 回应。
 *
 * 用法：
 *   node eval/phase1/mock-llm.js            # 默认监听 19090
 *   PORT=19090 node eval/phase1/mock-llm.js
 *   curl http://127.0.0.1:19090/_stats      # 看它被调了多少次、分别是什么类型
 */
const http = require('http')

const PORT = Number(process.env.PORT || 19090)

/**
 * 每次调用前故意等一会儿（毫秒）。
 * <p>存在的理由：验证"同会话并发写"需要两个请求真的**重叠**在模型调用那一段里。
 * 假模型瞬时返回时，两个请求往往一前一后跑完，谁也撞不上谁 ——
 * 那样测出来的"没冲突"是运气，不是结论。
 */
const DELAY_MS = Number(process.env.MOCK_DELAY_MS || 0)

/** 调用流水，用来验证「重放不该再调模型」这类断言 */
const calls = []

/** 按用户原话给出确定性的"理解"结果 */
const RULES = [
  { when: '我丢了一部手机', out: { intent: 'ASK_MORE', reply: '请问是什么颜色的？品牌还记得吗？' } },
  {
    when: '黑色苹果手机', out: {
      intent: 'SEARCH', subject: '手机',
      facts: [{ field: '颜色', value: '黑色' }, { field: '品牌', value: '苹果' }],
    },
  },
  // ⚠️ 顺序有意义：带「可能」的规则必须排在普通地点前面，
  //    否则「手机可能在图书馆」会先被 '在图书馆' 命中，不确定性就丢了
  {
    when: '可能在图书馆',
    out: { intent: 'SEARCH', facts: [{ field: '地点', value: '图书馆', certainty: 'UNCERTAIN' }] },
  },
  { when: '在图书馆', out: { intent: 'SEARCH', facts: [{ field: '地点', value: '图书馆' }] } },
  { when: '其实是在食堂', out: { intent: 'SEARCH', facts: [{ field: '地点', value: '食堂' }] } },
  { when: '颜色记不清了', out: { intent: 'SEARCH', removedFields: ['颜色'] } },
  { when: '第二个为什么像', out: { intent: 'EXPLAIN_ITEM', referenceIndex: 2 } },
  { when: '不是第二个', out: { intent: 'EXCLUDE_ITEM', referenceIndex: 2 } },
  { when: '你好', out: { intent: 'SMALLTALK', reply: '我在。说说你丢了什么、大概什么样？' } },
  {
    when: '提交认领',
    out: { intent: 'PLATFORM', reply: '在物品详情页点「我要认领」，填写只有物主才知道的特征证明后提交。' },
  },
  {
    when: '钥匙', out: {
      intent: 'SEARCH', subject: '钥匙',
      facts: [{ field: '颜色', value: '银色' }, { field: '地点', value: '二教阶梯教室' }],
    },
  },
]

function kindOf(prompt) {
  if (prompt.includes('【当前已知条件】')) return 'understand'
  if (prompt.includes('候选物品（格式')) return 'match'
  if (prompt.includes('【用户想了解的这条招领信息】')) return 'explain'
  return 'unknown'
}

function userMessageOf(prompt) {
  const marker = '【本轮用户消息】'
  const i = prompt.indexOf(marker)
  if (i < 0) return ''
  const rest = prompt.slice(i + marker.length)
  const j = rest.indexOf('请只输出 JSON')
  return (j < 0 ? rest : rest.slice(0, j)).trim()
}

function respondUnderstand(prompt) {
  const message = userMessageOf(prompt)
  for (const rule of RULES) {
    if (message.includes(rule.when)) {
      return JSON.stringify({
        intent: rule.out.intent,
        referenceIndex: rule.out.referenceIndex ?? null,
        subject: rule.out.subject ?? null,
        subjectChanged: false,
        facts: rule.out.facts ?? [],
        removedFields: rule.out.removedFields ?? [],
        reply: rule.out.reply ?? '',
      })
    }
  }
  // 兜底：把整句话当主体，正常去搜一次
  return JSON.stringify({
    intent: 'SEARCH', referenceIndex: null, subject: message.slice(0, 8), subjectChanged: false,
    facts: [], removedFields: [], reply: '',
  })
}

function respondMatch(prompt) {
  const header = prompt.indexOf('候选物品（格式')
  const body = prompt.slice(prompt.indexOf('\n', header) + 1)
  const lines = body.split('\n').map((l) => l.trim()).filter((l) => /^\d+\|/.test(l))
  const matches = lines.slice(0, 5).map((line, i) => {
    const id = Number(line.split('|')[0])
    return {
      itemId: id,
      score: 95 - i * 7,
      reason: i === 0 ? '颜色和物品类型都对得上，拾获地点也接近。' : '类型一致，细节还需要你自己核对。',
    }
  })
  return JSON.stringify({
    answer: matches.length ? `为你找到 ${matches.length} 条比较像的招领信息。` : '没有找到相近的招领信息。',
    matches,
  })
}

function respondExplain(prompt) {
  return JSON.stringify({
    answer: '它对得上的地方是物品类型一致、颜色也接近；不确定的是拾获地点和你的描述不完全一样，' +
      '另外它的描述里没有提到你说的那处细节，需要你自己核对。',
  })
}

function respond(prompt) {
  const kind = kindOf(prompt)
  if (kind === 'understand') return respondUnderstand(prompt)
  if (kind === 'match') return respondMatch(prompt)
  if (kind === 'explain') return respondExplain(prompt)
  return JSON.stringify({ answer: '（mock：不认识的提示词）' })
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/_stats')) {
    const kinds = calls.reduce((acc, c) => ((acc[c.kind] = (acc[c.kind] || 0) + 1), acc), {})
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ total: calls.length, kinds, calls }))
    return
  }
  if (req.url.startsWith('/_reset')) {
    calls.length = 0
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end('{"ok":true}')
    return
  }

  let raw = ''
  req.on('data', (chunk) => { raw += chunk })
  req.on('end', async () => {
    let content = ''
    let prompt = ''
    try {
      const payload = JSON.parse(raw)
      prompt = payload.messages.find((m) => m.role === 'user')?.content || ''
      content = respond(prompt)
    } catch (e) {
      content = JSON.stringify({ answer: 'mock 解析请求失败: ' + e.message })
    }
    calls.push({ kind: kindOf(prompt), at: Date.now() })
    if (DELAY_MS > 0) {
      await new Promise((r) => setTimeout(r, DELAY_MS))
    }

    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({
      id: 'mock-' + calls.length,
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      // token 数是按提示词长度估的，只是为了验证链路会把用量如实累计，
      // 不代表真实模型的计费口径
      usage: { prompt_tokens: Math.round(prompt.length / 3), completion_tokens: 40 },
    }))
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock-llm] 假模型已启动 http://127.0.0.1:${PORT}/v1/chat/completions`)
  console.log('[mock-llm] ⚠️ 规则驱动的假模型，只能验证多轮编排，不代表真实模型效果')
})
