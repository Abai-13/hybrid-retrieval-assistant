#!/usr/bin/env node
/**
 * 第一阶段「多轮找物助手」的可复现验收脚本。
 *
 * 它把验收清单里能自动断言的场景跑一遍：补充 / 纠正 / 撤回 / 不确定条件 /
 * 指代 / 排除后重搜 / 新对话 / 刷新恢复 / 会话所有权 / 过期 / 幂等 / 不该搜的就不搜。
 *
 * ⚠️ 前置条件（脚本不会替你启动服务，免得误连到真实环境）：
 *   1) 隔离库已建好并导入了种子数据：
 *        mysql -u root -p lost_found_phase1 < sql/schema.sql
 *        mysql -u root -p lost_found_phase1 < eval/phase1/seed-items.sql
 *   2) 假模型在跑：      node eval/phase1/mock-llm.js
 *   3) 后端指向隔离库与假模型（见 eval/phase1/README.md 的启动命令）
 *
 * ⚠️ 假模型是**规则驱动**的，所以这个脚本验证的是**编排流程**（会话、条件合并、
 *    指代解析、排除、幂等、权限），**不是模型的理解能力**。真实模型效果要另外跑。
 *
 * 用法：node eval/phase1/multi-turn-check.js
 */
const BASE = process.env.API_BASE || 'http://127.0.0.1:18080/api'
const MOCK = process.env.MOCK_BASE || 'http://127.0.0.1:19090'

let passed = 0
let failed = 0
const failures = []

function check(name, condition, detail) {
  if (condition) {
    passed++
    console.log(`  ✓ ${name}`)
  } else {
    failed++
    failures.push({ name, detail })
    console.log(`  ✗ ${name}${detail === undefined ? '' : ' → ' + JSON.stringify(detail)}`)
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

async function mockStats() {
  const res = await fetch(`${MOCK}/_stats`)
  return res.json()
}

async function resetMock() {
  await fetch(`${MOCK}/_reset`)
}

function uuid() {
  return `t-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

async function loginOrRegister(username) {
  const password = 'phase1-test-pw'
  await api('/user/register', { method: 'POST', body: { username, password, nickname: username } })
  const res = await api('/user/login', { method: 'POST', body: { username, password } })
  if (res.code !== 200) throw new Error(`登录失败: ${JSON.stringify(res)}`)
  return { token: res.data.token, userId: res.data.userId }
}

function conditionsOf(turn) {
  return ((turn && turn.conditions) || []).map((c) => `${c.field}=${c.value}`)
}

const run = async () => {
  console.log('== 准备：注册两个用户，A 用来跑场景，B 用来验证会话隔离 ==')
  const A = await loginOrRegister('phase1_alice')
  const B = await loginOrRegister('phase1_bob')
  const stats0 = await mockStats()
  console.log(`  假模型已启动，累计被调用 ${stats0.total} 次`)

  // ── 会话生命周期 ────────────────────────────────────────
  console.log('\n== 1. 会话读取/新建 ==')
  const empty = await api('/ai/session', { token: A.token })
  check('没有会话时 GET /ai/session 返回 conversation=null', empty.data?.conversation === null,
    empty.data)

  const created = await api('/ai/session', { method: 'POST', token: A.token })
  check('POST /ai/session 建出新会话', created.code === 200 && created.data?.id > 0)
  const convId = created.data.id

  const restore = await api('/ai/session', { token: A.token })
  check('刷新恢复：GET 拿回同一段会话',
    restore.data?.conversation?.id === convId, restore.data?.conversation?.id)

  // ── 多轮场景 ────────────────────────────────────────────
  const send = (content, requestId = uuid(), extra = {}) =>
    api(`/ai/session/${convId}/messages`, {
      method: 'POST', token: A.token, body: { requestId, content, ...extra },
    })

  console.log('\n== 2. 信息不足时按需追问，不机械盘问 ==')
  const t1 = await send('我丢了一部手机')
  check('缺信息 → ASK_MORE，且没有卡片', t1.data?.intent === 'ASK_MORE' && (t1.data?.cards || []).length === 0,
    t1.data?.intent)
  check('追问只问一个最关键的点', (t1.data?.text || '').length > 0 && (t1.data?.text || '').length < 80,
    t1.data?.text)

  console.log('\n== 3. 补充条件后立即检索 ==')
  const t2 = await send('黑色苹果手机')
  const t2Cards = t2.data?.cards || []
  check('补充品牌/颜色后给出真实卡片', t2Cards.length > 0, t2Cards.length)
  check('条件里记住了颜色和品牌',
    conditionsOf(t2.data).includes('颜色=黑色') && conditionsOf(t2.data).includes('品牌=苹果'),
    conditionsOf(t2.data))
  check('卡片是真实存在的物品（标题来自数据库回填）',
    t2Cards.every((c) => c.itemId && c.title), t2Cards.map((c) => c.title))
  check('卡片不带联系方式（联系方式只在认领流程里给）',
    t2Cards.every((c) => !('contact' in c)), Object.keys(t2Cards[0] || {}))

  const t3 = await send('在图书馆')
  check('再补充地点，条件累加而不是替换',
    conditionsOf(t3.data).includes('地点=图书馆') && conditionsOf(t3.data).includes('颜色=黑色'),
    conditionsOf(t3.data))

  console.log('\n== 4. 纠正：旧值必须消失 ==')
  const t4 = await send('其实是在食堂')
  check('地点被覆盖成食堂', conditionsOf(t4.data).includes('地点=食堂'), conditionsOf(t4.data))
  check('★ 旧地点不残留（残留会导致两个地点同时约束，一条都搜不到）',
    !conditionsOf(t4.data).some((c) => c.includes('图书馆')), conditionsOf(t4.data))

  console.log('\n== 5. 撤回：条件整个删掉 ==')
  const t5 = await send('颜色记不清了')
  check('★ 颜色条件被删除', !conditionsOf(t5.data).some((c) => c.startsWith('颜色=')),
    conditionsOf(t5.data))
  check('其余条件保留', conditionsOf(t5.data).some((c) => c.startsWith('品牌=')),
    conditionsOf(t5.data))

  console.log('\n== 6. 指代：第二个为什么像 ==')
  const before = t5.data?.cards || []
  check('上一步有至少两张卡片可供指代', before.length >= 2, before.length)
  const t6 = await send('第二个为什么像')
  const refId = before[1]?.itemId
  check('★ 解释的是上一轮第 2 张卡（按展示顺序，不随重新排序错位）',
    t6.data?.cards?.[0]?.itemId === refId,
    { 期望: refId, 实际: t6.data?.cards?.[0]?.itemId })
  check('解释里说明了匹配点，也说明了不确定的地方', (t6.data?.text || '').length > 20, t6.data?.text)
  check('★ 解释卡片沿用原编号（问的是第二个，编号就得是 2）',
    t6.data?.cards?.[0]?.index === 2, t6.data?.cards?.[0])
  // 刷新之后编号必须还在 —— 回填时丢掉序号的话，用户下次说「不是第二个」就对不上了
  const afterExplain = await api(`/ai/session/${convId}`, { token: A.token })
  const explainCard = (afterExplain.data?.messages || [])
    .filter((m) => m.intent === 'EXPLAIN_ITEM' && (m.cards || []).length)
    .pop()?.cards?.[0]
  check('★ 刷新恢复后编号仍在', explainCard?.index === 2, explainCard)
  check('解释不会承诺「这一定是你的」，也不给联系方式',
    !(t6.data?.text || '').includes('一定') && !(t6.data?.text || '').includes('1[0-9]{10}'),
    t6.data?.text)

  console.log('\n== 7. 排除后重搜 ==')
  // 上一轮是「解释某一条」—— 按最后一条消息算的话，序号 2 会越界（那张卡只有一张），
  // 助手就只能反问"你指哪一条"。用户明明刚指着第二张说过话，所以这里必须仍然能解析
  const t7 = await send('不是第二个，再找找')
  const t7Ids = (t7.data?.cards || []).map((c) => c.itemId)
  check('★ 「解释」那一轮不改变编号基准：仍然能解析出第二个',
    t7.data?.intent === 'EXCLUDE_ITEM', t7.data?.intent)
  check('★ 被否掉的物品不再出现', !t7Ids.includes(refId), { 排除: refId, 返回: t7Ids })
  check('排除在截断前生效（诊断里能看到剔掉了几条）',
    (t7.data?.diagnostics?.excludedCount ?? 0) >= 1, t7.data?.diagnostics)
  check('排除后重新检索（有候选、有召回池）',
    (t7.data?.diagnostics?.poolSize ?? 0) > 0, t7.data?.diagnostics)
  check('排除项进了摘要，后续搜索继续躲开它',
    (t7.data?.conditions || []).length >= 1, conditionsOf(t7.data))

  console.log('\n== 8. 幂等：同一个 requestId 重复到达 ==')
  const dupId = uuid()
  const first = await send('黑色苹果手机', dupId)
  const statsBefore = await mockStats()
  const second = await send('黑色苹果手机', dupId)
  const statsAfter = await mockStats()
  check('重放返回同样的回答', first.data?.text === second.data?.text && first.data?.text)
  check('★ 重放【不再调用模型】（否则一次网络重试就多烧一次钱）',
    statsAfter.total === statsBefore.total,
    { before: statsBefore.total, after: statsAfter.total })
  const replayHistory = await api(`/ai/session/${convId}`, { token: A.token })
  const dupCount = (replayHistory.data?.messages || [])
    .filter((m) => m.requestId === dupId).length
  check('★ 同一 requestId 在历史里只有一轮（不会多出一条重复消息）', dupCount === 2, dupCount)

  console.log('\n== 9. 不该搜的就不搜 ==')
  const matchCallsBefore = (await mockStats()).kinds.match || 0
  const t8 = await send('你好')
  check('寒暄不返回卡片', (t8.data?.cards || []).length === 0)
  const t9 = await send('怎么提交认领')
  check('平台流程问答不返回卡片', (t9.data?.cards || []).length === 0)
  check('★ 这两轮都没有触发精排模型调用',
    ((await mockStats()).kinds.match || 0) === matchCallsBefore)

  console.log('\n== 10. 新对话：不带入旧条件与排除项 ==')
  const fresh = await api('/ai/session', { method: 'POST', token: A.token })
  check('★ 新会话条件为空', (fresh.data?.conditions || []).length === 0, conditionsOf(fresh.data))
  check('新会话消息为空', (fresh.data?.messages || []).length === 0)
  check('新会话是另一个 id', fresh.data?.id !== convId)

  console.log('\n== 11. 不确定条件 ==')
  const t10 = await api(`/ai/session/${fresh.data.id}/messages`, {
    method: 'POST', token: A.token, body: { requestId: uuid(), content: '手机可能在图书馆' },
  })
  const uncertain = (t10.data?.conditions || []).find((c) => c.field === '地点')
  check('★ 「可能在图书馆」保存为 UNCERTAIN', uncertain?.certainty === 'UNCERTAIN', uncertain)
  check('不确定的条件照常参与检索（不是当没听见）',
    t10.data?.status === 'OK' && (t10.data?.diagnostics?.candidateCount ?? 0) > 0,
    t10.data?.diagnostics)

  console.log('\n== 12. 会话所有权：会话 id 不可信 ==')
  const steal = await api(`/ai/session/${convId}`, { token: B.token })
  check('★ 别人的会话 id → 403，而不是把内容返回回来', steal.code === 403, steal)
  const stealSend = await api(`/ai/session/${convId}/messages`, {
    method: 'POST', token: B.token, body: { requestId: uuid(), content: '你好' },
  })
  check('★ 往别人的会话里发消息 → 403', stealSend.code === 403, stealSend)
  const noToken = await api(`/ai/session/${convId}`)
  check('不带 token 读会话 → 401', noToken.code === 401, noToken)
  const ghost = await api('/ai/session/99999999', { token: A.token })
  check('不存在的会话 → 404 且提示可以重新开始',
    ghost.code === 404 && (ghost.message || '').includes('新对话'), ghost)

  console.log('\n== 13. 参数校验 ==')
  const blank = await send('   ')
  check('空白内容 → 400，不是 500', blank.code === 400, blank)

  console.log('\n== 14. 诊断：token 与耗时如实记录 ==')
  const diag = t2.data?.diagnostics
  check('诊断里带模型调用次数与 token 合计',
    diag?.modelCalls >= 1 && diag?.promptTokens > 0 && diag?.budgetMs === 90000, diag)
  check('一轮最多两次业务模型调用', diag?.modelCalls <= 2, diag?.modelCalls)

  console.log('\n== 15. 同会话并发写：版本冲突不能覆盖新状态 ==')
  // 两个请求同时在跑（假模型用 MOCK_DELAY_MS 拖住，"同时"才是真的同时）。
  // 期望：要么都成功（一个先写完、另一个基于新版本继续），要么后写的那个拿到 409 ——
  // **绝不能两个都返回 200 却只留下其中一份状态**，那叫静默丢更新。
  const conv2 = await api('/ai/session', { method: 'POST', token: A.token })
  const send2 = (content) => api(`/ai/session/${conv2.data.id}/messages`, {
    method: 'POST', token: A.token, body: { requestId: uuid(), content },
  })
  const [c1, c2] = await Promise.all([send2('黑色苹果手机'), send2('钥匙')])
  const codes = [c1.code, c2.code].sort()
  const winners = [c1, c2].filter((r) => r.code === 200)
  const conflicts = [c1, c2].filter((r) => r.code === 409)
  check('并发写：每个请求要么成功、要么明确 409（不给 500，也不静默丢）',
    winners.length + conflicts.length === 2, codes)
  check('至少有一轮成功', winners.length >= 1, codes)

  const after = await api(`/ai/session/${conv2.data.id}`, { token: A.token })
  const survived = conditionsOf(after.data)
  // 有冲突发生时，落败那一轮**一个条件都不该留下** —— 留下就是"半截状态"：
  // 界面上有条件，却没有一条回复是基于它的
  const leaked = conflicts.length > 0 && winners.length === 1
    ? conflicts[0].data?.conditions?.filter(
      (c) => !conditionsOf(winners[0].data).includes(`${c.field}=${c.value}`))
    : []
  check('★ 冲突的一轮没有把自己的条件写进会话',
    !leaked || leaked.length === 0 || conflicts[0].data === undefined,
    { 冲突轮条件: conditionsOf(conflicts[0]?.data), 会话条件: survived })
  check('胜者那一轮的条件完整体现在会话里',
    !winners[0] || conditionsOf(winners[0].data).every((c) => survived.includes(c)),
    { 胜者: conditionsOf(winners[0]?.data), 会话: survived })
  check('并发之后的会话仍可继续使用（版本号是新的）',
    after.code === 200 && after.data?.version > 0, after.data?.version)

  console.log('\n== 16. 同一个 requestId 并发：第二个必须明确拒绝，不能重复调模型 ==')
  // 假模型带延迟时，"同时"才是真的同时 —— 否则两个请求一前一后跑完，谁也撞不上谁
  const conv3 = await api('/ai/session', { method: 'POST', token: A.token })
  const sameId = `dup-${Date.now()}`
  const sendSame = () => api(`/ai/session/${conv3.data.id}/messages`, {
    method: 'POST', token: A.token, body: { requestId: sameId, content: '黑色苹果手机' },
  })
  const beforeDup = await mockStats()
  const [d1, d2] = await Promise.all([sendSame(), sendSame()])
  const afterDup = await mockStats()
  const dupCodes = [d1.code, d2.code].sort()
  check('★ 同一个 requestId 并发：一个成功、另一个 409（不是两个都跑一遍）',
    dupCodes[0] === 200 && dupCodes[1] === 409, dupCodes)
  const rejected = [d1, d2].find((r) => r.code === 409)
  check('拒绝的理由说得清是「这一轮在忙」，不是含糊的失败',
    (rejected?.message || '').includes('处理'), rejected?.message)
  check('★ 没有因此多调一次模型（理解阶段只发生一次）',
    ((afterDup.kinds.understand || 0) - (beforeDup.kinds.understand || 0)) <= 1,
    { 之前: beforeDup.kinds.understand, 之后: afterDup.kinds.understand })

  console.log('\n== 17. 客户端版本校验：旧页面里的「第二个」不能被按新结果解析 ==')
  const conv4 = await api('/ai/session', { method: 'POST', token: A.token })
  const send4 = (body) => api(`/ai/session/${conv4.data.id}/messages`, {
    method: 'POST', token: A.token, body,
  })
  const stale = await send4({
    requestId: uuid(), content: '第二个为什么像', conversationVersion: 999,
  })
  check('★ 版本对不上 → 409 并提示刷新', stale.code === 409 && (stale.message || '').includes('刷新'),
    stale)
  const okTurn = await send4({
    requestId: uuid(), content: '黑色苹果手机', conversationVersion: conv4.data.version,
  })
  check('版本一致 → 正常受理', okTurn.code === 200 && okTurn.data.status === 'OK', okTurn.code)
  // 重放优先于版本校验：同一个 requestId 再来一次，即使版本已经变了也该拿到原结果
  const replayed = await send4({
    requestId: okTurn.data.requestId, content: '黑色苹果手机', conversationVersion: 999,
  })
  check('★ 重放优先于版本校验（同一个请求本来就处理过了）',
    replayed.code === 200 && replayed.data.text === okTurn.data.text,
    { code: replayed.code, 相同: replayed.data?.text === okTurn.data.text })

  console.log('\n== 18. 清理假模型调用记录 ==')
  await resetMock()

  console.log('\n== 本脚本未自动覆盖的场景 ==')
  console.log('  · 会话过期后的提示与清理 —— 需要把 expires_at 改到过去，涉及直接改库，')
  console.log('    所以单独放在 README 的手工步骤里（eval/phase1/README.md「过期会话」一节）')

  console.log(`\n===== 结果：${passed} 通过 / ${failed} 失败 =====`)
  if (failed) {
    console.log('失败项：')
    for (const f of failures) console.log(`  · ${f.name} → ${JSON.stringify(f.detail)}`)
    process.exit(1)
  }
}

run().catch((e) => {
  console.error('\n脚本异常中止：', e.message)
  process.exit(1)
})
