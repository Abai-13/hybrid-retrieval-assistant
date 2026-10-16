<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { categoryStyle } from '../utils/format'

const router = useRouter()

/**
 * AI 接口专用超时。后端一轮的预算是 90 秒（可能包含两次模型调用和重试），
 * 前端必须比它长 —— 反过来会出现「后端还在跑、前端先掐断」：
 * 用户看到失败，其实后端最后成功了，而这条消息也就白跑了。
 */
const AI_TIMEOUT_MS = 120000

/** 会话 id：全程由后端校验所有权，这里只是一个"往哪发"的地址 */
const conversationId = ref(null)
/** 消息列表。每条：{ role, text, cards?, requestId?, status?, degradeReason?, diagnostics?, retry? } */
const messages = ref([])
/** 当前条件 —— 让用户看清系统记住了什么 */
const conditions = ref([])
const version = ref(null)
const loading = ref(false)
const booting = ref(true)
const expiredNotice = ref(false)
const question = ref('')
const listRef = ref(null)

const isEmpty = computed(() => messages.value.length === 0)

const samples = [
  '我丢了一部手机',
  '一把银色的车钥匙，圆头的，落在二教阶梯教室',
  '绿色的罗技 G502 鼠标，昨天下午落在食堂了',
]

// ── 会话恢复：刷新后接着聊 ────────────────────────────────
// 只按「当前登录用户」读，前端存不存会话 id 都不影响结果，
// 也就不存在"改一下 localStorage 就能看别人对话"这种可能。
onMounted(restoreSession)

async function restoreSession() {
  booting.value = true
  try {
    const res = await request.get('/ai/session')
    const data = res.data || {}
    if (data.conversation) {
      applyConversation(data.conversation)
    } else {
      // 没有可用会话：可能是第一次来，也可能是上一段过期被清理了 —— 后者要说明白。
      // ⚠️ 这个提示不能被"顺手新建"清掉：先记下来，等用户真的开始聊了再撤
      expiredNotice.value = !!data.previousExpired
      await createSession()
    }
  } catch (e) {
    // 拦截器已经提示过；这里只把界面从 loading 里放出来
  } finally {
    booting.value = false
    scrollToBottom()
  }
}

function applyConversation(conv) {
  conversationId.value = conv.id
  version.value = conv.version
  conditions.value = conv.conditions || []

  const raw = conv.messages || []
  // 先按 requestId 建立「用户原话」索引 —— 刷新之后，失败的那一轮仍然要能重试，
  // 而重试需要原话 + （撤回场景下）结构化指令。这两样都只能从用户消息里取回来
  const userByRequest = {}
  raw.forEach((m) => {
    if (m.role === 'USER') userByRequest[m.requestId] = m
  })

  // 哪些用户消息已经有助手回复了 —— 没有的那些要在下面补一个可重试的提示。
  // 这种情况是真实存在的：并发冲突时服务端只落下了用户这一句，助手那行没写成
  const answered = new Set(
    raw.filter((m) => m.role === 'ASSISTANT').map((m) => m.requestId),
  )

  const built = []
  raw.forEach((m) => {
    const item = {
      role: m.role === 'USER' ? 'user' : 'assistant',
      text: m.text,
      cards: m.cards || [],
      requestId: m.requestId,
      status: m.status,
      degradeReason: m.degradeReason,
      diagnostics: m.diagnostics,
    }
    if (m.role === 'ASSISTANT' && m.status === 'FAILED') {
      const user = userByRequest[m.requestId]
      if (user) {
        item.retryContent = user.text
        item.retryRequestId = m.requestId
        // 撤回指令原样带回来 —— 不能把它当成一句自然语言再让模型猜一次
        item.retryRetract = user.retract || null
      }
    }
    built.push(item)

    // 孤儿轮：用户说了话，服务端却没留下助手那行（并发冲突时就长这样）。
    // 刷新之后它必须仍然能重试，否则用户只能说第二遍
    if (m.role === 'USER' && !answered.has(m.requestId)) {
      built.push({
        role: 'assistant',
        text: '这一轮没有完成（可能是同时发了两条消息）。你的条件都还在，点「重试」我再跑一次。',
        cards: [],
        status: 'FAILED',
        degradeReason: 'INCOMPLETE',
        requestId: m.requestId,
        retryContent: m.text,
        retryRequestId: m.requestId,
        retryRetract: m.retract || null,
      })
    }
  })
  messages.value = built
}

async function createSession() {
  const res = await request.post('/ai/session')
  applyConversation(res.data)
}

async function onNewSession() {
  if (loading.value) return
  loading.value = true
  try {
    await createSession()
    expiredNotice.value = false
    ElMessage.success('已开始新对话，之前的条件不会带过来')
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 服务端状态变了之后，把当前会话重新拉一遍（条件、卡片顺序都要和服务端一致） */
async function refreshSession() {
  const res = await request.get('/ai/session')
  if (res.data?.conversation) {
    applyConversation(res.data.conversation)
  }
}

// ── 发一轮消息 ────────────────────────────────────────────
function newRequestId() {
  // 幂等键：重试时必须复用它，否则后端会当成新的一轮，多出一条消息、多花一次模型调用
  if (crypto?.randomUUID) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function ask(text) {
  const content = (text ?? question.value).trim()
  if (!content) {
    ElMessage.warning('请先输入内容')
    return
  }
  question.value = ''
  await runTurn({ content })
}

/** 撤回某个条件：带结构化指令，后端按字段删，不经过模型理解 */
async function retractCondition(field) {
  await runTurn({
    content: `去掉「${field}」这个条件`,
    retract: { field },
  })
}

/** 重试：复用同一个 requestId —— 后端会重放或重跑，而不是又追加一轮 */
async function retry(message) {
  const index = messages.value.indexOf(message)
  if (index >= 0) messages.value.splice(index, 1)
  await runTurn({
    content: message.retryContent,
    requestId: message.retryRequestId,
    retract: message.retryRetract || null,
    pushUser: false,
  })
}

async function runTurn({ content, requestId = newRequestId(), retract = null, pushUser = true }) {
  // ⚠️ 忙碌标记必须在**第一个 await 之前**立起来：放在 await 之后的话，
  //    连点两次发送 / 回车连按，两个调用会同时通过这道检查，
  //    结果就是重复提交（对同一段对话发两轮、烧两次模型调用）
  if (loading.value) return
  if (!conversationId.value) {
    ElMessage.warning('会话还没准备好，请稍候')
    return
  }
  loading.value = true

  try {
    if (pushUser) {
      messages.value.push({ role: 'user', text: content, requestId })
    }
    await scrollToBottom()

    // 带上"我看到的版本"：服务端拿它判断页面上的卡片顺序是不是最新的。
    // 不带的话，旧标签页里的「第二个」会被按另一批结果解析
    const body = { requestId, content, conversationVersion: version.value }
    if (retract) body.retract = retract
    const res = await request.post(
      `/ai/session/${conversationId.value}/messages`, body, { timeout: AI_TIMEOUT_MS })
    await applyTurn(res.data, { content, requestId, retract })
    expiredNotice.value = false
  } catch (e) {
    await onTurnError(e, content, requestId, retract)
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

async function applyTurn(turn, { content, requestId, retract } = {}) {
  if (!turn) return

  // 幂等重放（后端 replayed=true）：这份响应是那一轮的**历史快照** ——
  // 旧条件、旧卡片、当时的版本。照常应用会把页面退回旧状态
  // （黑色那轮的条件 + 白色那轮之后的版本），下一句「第二个」也就数错了。
  // 正确做法是以服务端会话为准重新同步：不重复追加这条消息，
  // 条件/版本/卡片顺序全部保留较新的那份，历史回复留在它原来的位置
  if (turn.replayed) {
    await refreshSession()
    return
  }

  version.value = turn.version
  conditions.value = turn.conditions || []
  const failed = turn.status === 'FAILED'
  messages.value.push({
    role: 'assistant',
    text: turn.text || '（没有返回内容）',
    cards: turn.cards || [],
    requestId: turn.requestId,
    status: turn.status,
    degradeReason: turn.degradeReason,
    diagnostics: turn.diagnostics,
    // ★ 失败必须带重试所需的一切：模型超时/坏 JSON 也是失败，
    //   以前只有"网络异常"那条路有重试入口，模型返回不可用反而没得点
    ...(failed ? {
      retryContent: content,
      retryRequestId: requestId || turn.requestId,
      retryRetract: retract || null,
    } : {}),
  })
}

async function onTurnError(e, content, requestId, retract) {
  const code = e?.code ?? e?.response?.data?.code
  const message = e?.message || e?.response?.data?.message || '请求失败了'

  if (code === 410) {
    // 会话过期/被清理：重试一百次也一样，只能重新开始
    messages.value.push({
      role: 'assistant',
      text: `${message}\n点下面的「新对话」就能重新开始。`,
      status: 'FAILED',
      degradeReason: 'SESSION_EXPIRED',
      needsNewSession: true,
    })
    return
  }
  if (code === 409) {
    // 并发/版本冲突：服务端状态已经变了。**先把最新会话拉回来**，
    // 让页面上的条件和卡片顺序与服务端对齐，再让用户重试这一轮。
    //
    // ⚠️ 这里绝不能一律建议"开新对话" —— 那会把用户辛苦说出来的条件全丢掉，
    //    而这一轮本来只差一次重试。409 该重试、410 才该重开，这正是两者要分开的原因
    await refreshSession()
    if (!messages.value.some((m) => m.role === 'user' && m.requestId === requestId)) {
      // 服务端没存下这条（冲突发生在写入之前）—— 补回来，否则用户看不到自己说过什么
      messages.value.push({ role: 'user', text: content, requestId })
    }
    messages.value.push({
      role: 'assistant',
      text: `${message}\n已经帮你同步到最新状态了，点「重试」会在最新状态上重跑这一轮。`,
      status: 'FAILED',
      degradeReason: 'SESSION_CONFLICT',
      retryContent: content,
      retryRequestId: requestId,
      retryRetract: retract,
    })
    return
  }
  if (code === 429) {
    messages.value.push({
      role: 'assistant',
      text: '问得有点快，稍等一分钟再试。',
      status: 'FAILED',
      degradeReason: 'RATE_LIMITED',
    })
    return
  }
  // 网络/超时：条件都还在服务端，重试一次就好
  messages.value.push({
    role: 'assistant',
    text: '这次没能连上服务。你的条件没有变，可以点「重试」。',
    status: 'FAILED',
    degradeReason: 'NETWORK',
    retryContent: content,
    retryRequestId: requestId,
    retryRetract: retract,
  })
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function openItem(id) {
  router.push(`/items/${id}`)
}

// ── 展示辅助 ──────────────────────────────────────────────
function scoreColor(score) {
  if (score >= 80) return '#10b981'
  if (score >= 60) return '#f59e0b'
  return '#9ca3af'
}

/** 物品状态 —— 卡片上的状态是后端读的时候现取的，不是展示当时的快照 */
function statusMeta(status) {
  if (status === 'CLAIMED') return { text: '已被认领', cls: 'done' }
  if (status === 'CLAIMING') return { text: '认领中', cls: 'claiming' }
  return { text: '待认领', cls: 'open' }
}

const DEGRADE_TEXT = {
  UNDERSTAND_FAILED: '这次没能理解你的意思',
  MATCH_FAILED: '检索没能完成',
  TURN_TIMEOUT: '等得太久，没查完',
  SESSION_CONFLICT: '这段对话刚被另一次操作更新过',
  SESSION_EXPIRED: '这段对话已过期',
  INCOMPLETE: '这一轮没有完成',
  RATE_LIMITED: '请求过于频繁',
  NETWORK: '网络请求失败',
}
</script>

<template>
  <div class="lf-page ai-page">
    <div class="lf-page-head">
      <h1 class="lf-page-title">找物助手</h1>
      <p class="lf-page-sub">直接说丢了什么、长什么样。可以随时补充、纠正，或者告诉我「不是第二个」。</p>
    </div>

    <div class="lf-panel shell">
      <!-- 顶部：当前条件 + 新对话 -->
      <div class="shell-head">
        <div class="cond-block">
          <span class="cond-label">当前条件</span>
          <div v-if="conditions.length" class="cond-list">
            <el-tag
              v-for="c in conditions"
              :key="c.field"
              size="small"
              :type="c.certainty === 'UNCERTAIN' ? 'warning' : 'primary'"
              effect="light"
              :title="c.source ? `来自你说过的话：${c.source}` : ''"
              closable
              :disable-transitions="true"
              @close="retractCondition(c.field)"
            >
              {{ c.field }}：{{ c.value }}
              <em v-if="c.certainty === 'UNCERTAIN'">（不确定）</em>
            </el-tag>
          </div>
          <span v-else class="cond-empty">还没有条件 —— 说说你丢了什么</span>
        </div>

        <el-button size="small" :disabled="loading" @click="onNewSession">
          <el-icon><Refresh /></el-icon>新对话
        </el-button>
      </div>

      <!-- 消息区 -->
      <div ref="listRef" class="stream">
        <el-alert
          v-if="expiredNotice"
          type="info"
          :closable="false"
          show-icon
          title="上一段对话已超过保留期，已经为你开了一段新的"
          class="notice"
        />

        <!-- 空状态：给几个能点的示例，比让用户对着空框发呆强 -->
        <div v-if="isEmpty && !booting" class="welcome">
          <div class="welcome-icon"><el-icon><MagicStick /></el-icon></div>
          <div class="welcome-title">找物助手</div>
          <div class="welcome-sub">说得越具体越容易找到 —— 颜色 / 品牌型号 / 在哪丢 / 一眼能认出的特征</div>
          <div class="samples">
            <button v-for="s in samples" :key="s" class="sample" :disabled="loading" @click="ask(s)">
              {{ s }}
            </button>
          </div>
        </div>

        <template v-else>
          <div
            v-for="(m, i) in messages"
            :key="i"
            class="row"
            :class="m.role === 'user' ? 'row-user' : 'row-ai'"
          >
            <div v-if="m.role === 'assistant'" class="bot-avatar">
              <el-icon><MagicStick /></el-icon>
            </div>

            <div class="bubble" :class="[m.role, { error: m.status === 'FAILED' }]">
              <div class="bubble-text">{{ m.text }}</div>

              <!-- 结果卡片：都是真实物品，点得开 -->
              <div v-if="m.cards && m.cards.length" class="matches">
                <div
                  v-for="(mt, idx) in m.cards"
                  :key="mt.itemId"
                  class="match"
                  @click="openItem(mt.itemId)"
                >
                  <div class="match-icon" :style="{ background: categoryStyle(mt.category).bg }">
                    <span>{{ categoryStyle(mt.category).emoji }}</span>
                  </div>

                  <div class="match-main">
                    <div class="match-top">
                      <span class="match-title">
                        <!-- 序号由后端给：解释某一条那一轮只有一张卡，
                             前端按下标数会把它写成 1，用户下次说「不是第二个」就对不上了 -->
                        <span class="match-index">{{ mt.index != null ? mt.index : idx + 1 }}</span>{{ mt.title }}
                      </span>
                      <span v-if="mt.score != null" class="match-score" :style="{ color: scoreColor(mt.score) }">
                        {{ mt.score }}<i>分</i>
                      </span>
                    </div>

                    <div v-if="mt.score != null" class="bar">
                      <div
                        class="bar-fill"
                        :style="{ width: mt.score + '%', background: scoreColor(mt.score) }"
                      />
                    </div>

                    <div class="match-meta">
                      <span v-if="mt.location"><el-icon><LocationInformation /></el-icon>{{ mt.location }}</span>
                      <span v-if="mt.category"><el-icon><Collection /></el-icon>{{ mt.category }}</span>
                      <!-- 状态是读的时候现取的：上轮还写着「未认领」的东西被认领走之后就改了 -->
                      <span class="status" :class="statusMeta(mt.status).cls">
                        <i class="dot" />{{ statusMeta(mt.status).text }}
                      </span>
                    </div>

                    <div v-if="mt.reason" class="match-reason">{{ mt.reason }}</div>
                  </div>

                  <el-icon class="match-arrow"><ArrowRight /></el-icon>
                </div>
              </div>

              <!-- 失败：必须和「没找到」长得不一样，并且给一个重试入口 -->
              <div v-if="m.status === 'FAILED'" class="fail-line">
                <el-tag size="small" type="danger" effect="light">
                  <el-icon><Warning /></el-icon>
                  {{ DEGRADE_TEXT[m.degradeReason] || '本次未完成' }}
                </el-tag>
                <el-button v-if="m.needsNewSession" size="small" type="primary" plain @click="onNewSession">
                  新对话
                </el-button>
                <el-button v-else-if="m.retryRequestId" size="small" type="primary" plain @click="retry(m)">
                  <el-icon><RefreshRight /></el-icon>重试
                </el-button>
              </div>

              <!-- 诊断：平时不显眼，排查时能看出问题出在哪一层 -->
              <div v-else-if="m.diagnostics" class="meta-line">
                <span>
                  模型调用 {{ m.diagnostics.modelCalls }} 次
                  · {{ m.diagnostics.promptTokens + m.diagnostics.completionTokens }} tokens
                  · 耗时 {{ m.diagnostics.elapsedMs }}ms
                  <template v-if="m.diagnostics.poolSize">
                    · 候选池 {{ m.diagnostics.poolSize }} 条取 {{ m.diagnostics.candidateCount }} 条
                  </template>
                  <template v-if="m.diagnostics.excludedCount">
                    · 已避开你否掉的 {{ m.diagnostics.excludedCount }} 条
                  </template>
                </span>
              </div>
            </div>
          </div>

          <!-- 「正在思考」占位 -->
          <div v-if="loading" class="row row-ai">
            <div class="bot-avatar"><el-icon><MagicStick /></el-icon></div>
            <div class="bubble ai typing"><span /><span /><span /></div>
          </div>
        </template>
      </div>

      <!-- 输入区 -->
      <div class="composer">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="booting"
          placeholder="比如：一把银色的钥匙，圆头的，落在二教阶梯教室"
          @keydown.enter.exact.prevent="ask()"
        />
        <el-button
          type="primary"
          class="send"
          :loading="loading"
          :disabled="loading || booting"
          @click="ask()"
        >
          <el-icon><Promotion /></el-icon>发送
        </el-button>
      </div>
      <div class="hint">
        按 Enter 发送 · Shift + Enter 换行 · 回答生成中无法再次发送
      </div>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 260px);
  min-height: 480px;
  overflow: hidden;
}

/* ── 顶部：当前条件 ───────────────────────────────────────── */
.shell-head {
  padding: 12px 18px;
  border-bottom: 1px solid var(--lf-border);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.cond-block {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}

.cond-label {
  font-size: 12.5px;
  color: var(--lf-text-sub);
  line-height: 24px;
  flex-shrink: 0;
}

.cond-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.cond-list em {
  font-style: normal;
  opacity: 0.75;
  font-size: 11px;
}

.cond-empty {
  font-size: 12.5px;
  color: var(--lf-text-muted);
  line-height: 24px;
}

.notice {
  margin-bottom: 14px;
}

/* ── 消息流 ───────────────────────────────────────────────── */
.stream {
  flex: 1;
  overflow-y: auto;
  padding: 20px 18px;
  background: #fbfbfd;
}

.welcome {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}

.welcome-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 26px;
  color: #fff;
  background: linear-gradient(135deg, var(--el-color-primary), #7c74ee);
  box-shadow: 0 6px 18px rgba(79, 70, 229, 0.3);
  margin-bottom: 6px;
}

.welcome-title {
  font-size: 17px;
  font-weight: 600;
}

.welcome-sub {
  font-size: 13px;
  color: var(--lf-text-muted);
  margin-top: 8px;
}

.samples {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
  width: 100%;
  max-width: 420px;
}

.sample {
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid var(--lf-border);
  background: #fff;
  font-size: 13.5px;
  color: var(--lf-text-sub);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
}

.sample:hover:not(:disabled) {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.sample:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ── 单条消息 ─────────────────────────────────────────────── */
.row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  align-items: flex-start;
}

.row-user {
  justify-content: flex-end;
}

.bot-avatar {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 9px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 16px;
  background: linear-gradient(135deg, var(--el-color-primary), #7c74ee);
}

.bubble {
  max-width: min(680px, 82%);
  padding: 11px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.65;
}

.bubble.user {
  background: var(--el-color-primary);
  color: #fff;
  border-top-right-radius: 3px;
}

.bubble.assistant {
  background: #fff;
  border: 1px solid var(--lf-border);
  border-top-left-radius: 3px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.04);
}

.bubble.error {
  border-color: #fca5a5;
  background: #fef2f2;
}

.bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.fail-line {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ── 结果卡片 ─────────────────────────────────────────────── */
.matches {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.match {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--lf-border);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.match:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.match-icon {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 19px;
  line-height: 1;
}

.match-main {
  flex: 1;
  min-width: 0;
}

.match-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.match-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--lf-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 序号：用户说「第二个」时数的就是它 */
.match-index {
  display: inline-grid;
  place-items: center;
  width: 17px;
  height: 17px;
  margin-right: 6px;
  border-radius: 5px;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
  vertical-align: 1px;
}

.match-score {
  flex-shrink: 0;
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.match-score i {
  font-size: 11px;
  font-style: normal;
  font-weight: 500;
  margin-left: 1px;
}

.bar {
  height: 4px;
  border-radius: 999px;
  background: #eef0f4;
  margin: 7px 0 8px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.4s ease;
}

.match-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  font-size: 12px;
  color: var(--lf-text-sub);
}

.match-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.status .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status.open {
  color: #d97706;
}

.status.claiming {
  color: #2563eb;
}

.status.done {
  color: var(--lf-text-muted);
}

.match-reason {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--lf-text-muted);
  line-height: 1.5;
}

.match-arrow {
  color: var(--lf-text-muted);
  flex-shrink: 0;
}

.meta-line {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--lf-border);
  font-size: 11.5px;
  color: var(--lf-text-muted);
}

/* ── 思考中的三个点 ───────────────────────────────────────── */
.typing {
  display: flex;
  gap: 5px;
  align-items: center;
  padding: 15px 16px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--lf-text-muted);
  animation: blink 1.2s infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  60%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

/* ── 输入区 ───────────────────────────────────────────────── */
.composer {
  display: flex;
  gap: 10px;
  padding: 14px 18px 8px;
  border-top: 1px solid var(--lf-border);
  background: #fff;
  align-items: flex-end;
}

.composer :deep(.el-textarea) {
  flex: 1;
}

.send {
  height: 40px;
  flex-shrink: 0;
}

.hint {
  padding: 0 18px 12px;
  font-size: 11.5px;
  color: var(--lf-text-muted);
  background: #fff;
}

@media (max-width: 640px) {
  .shell {
    height: calc(100vh - 220px);
  }

  .bubble {
    max-width: 90%;
  }
}
</style>
