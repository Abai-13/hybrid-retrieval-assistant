<script setup>
import { ref, nextTick, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { categoryStyle } from '../utils/format'

const router = useRouter()

/**
 * AI 接口专用超时。默认的 30s 不够 —— 后端一次模型调用正常 20~33s，
 * 叠加读超时重试最坏能到 90s+。给足余量，避免「后端成功、前端报错」。
 */
const AI_TIMEOUT_MS = 120000

// chat = 自由问答，query = 按描述匹配招领信息
const mode = ref('query')
const question = ref('')
const loading = ref(false)
const listRef = ref(null)

/**
 * 消息列表。每条：{ role: 'user' | 'ai', text, matches?, meta?, degraded? }
 *
 * 改造前用的是一个 answer 字符串，只能显示最后一次回答 ——
 * 追问一次上一句就没了，而且「我发过什么」也看不到。
 */
const messages = ref([])

const MODE_META = {
  chat: {
    title: 'AI 问答',
    icon: 'ChatDotRound',
    tip: '随便问，AI 帮你解答校园失物招领相关的问题。',
    sub: '试试这样问：',
    placeholder: '比如：捡到东西应该交到哪里？',
    samples: ['捡到东西应该交到哪里？', '校园卡丢了怎么补办？', '贵重物品丢了要报警吗？'],
  },
  query: {
    title: 'AI 物品匹配',
    icon: 'MagicStick',
    tip: '说得越具体越容易找到 —— 颜色 / 品牌型号 / 在哪丢 / 一眼能认出的特征',
    sub: '这样说最容易找到：',
    placeholder: '比如：一把银色的钥匙，圆头的，落在二教阶梯教室',
    // ⚠️ 例子本身就是提示语，别写成「一个黑色双肩包，昨天在图书馆丢的」这种。
    //    召回的 2-gram 是靠字面撞的，例子必须示范「库里标题实际会写哪些词」：
    //    颜色、品牌型号、具体地点，以及最关键的一眼可辨特征（圆头 / 封面卷边 / G502）。
    //    用户会照着例子写，例子写成什么样，用户就描述成什么样。
    samples: [
      '一把银色的车钥匙，圆头的，落在二教阶梯教室',
      '绿色的罗技 G502 鼠标，昨天下午落在食堂了',
      '一本汤小丹的操作系统教材，封面有点卷边',
    ],
  },
}

const meta = computed(() => MODE_META[mode.value])
const isEmpty = computed(() => messages.value.length === 0)

// 切模式要清空对话 —— 两个模式的上下文不通用，
// 留着上一模式的记录用户会以为 AI「串台」了
function switchMode() {
  messages.value = []
  question.value = ''
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function ask(text) {
  const q = (text ?? question.value).trim()
  if (!q) {
    ElMessage.warning('请先输入内容')
    return
  }

  messages.value.push({ role: 'user', text: q })
  question.value = ''
  loading.value = true
  await scrollToBottom()

  try {
    // 两个接口路径不同，但请求体都是 { question }
    //
    // ⚠️ 这里必须单独放宽超时：axios 实例默认 30s，但后端一次模型调用
    //    正常就要 20~33s（免费档模型慢），且 read-timeout 30s × 最多 2 次重试，
    //    最坏能到 90s+。用默认值会导致「后端还在跑、前端先掐断」，
    //    用户看到"请求失败"，其实后端最后是成功的。
    //    其他接口保持 30s 快速失败 —— 这是 AI 接口独有的长耗时特征。
    const res = await request.post(
      `/ai/${mode.value}`, { question: q }, { timeout: AI_TIMEOUT_MS })
    const d = res.data

    messages.value.push({
      role: 'ai',
      text: d.answer || '（没有返回内容）',
      // 匹配模式才有 matches，问答模式是 undefined，模板里用 v-if 挡住
      matches: mode.value === 'query' ? d.matches || [] : null,
      // 把后端给的可观测字段带上：出了问题时能一眼看出是"没召回到"
      // 还是"大模型挂了"，而不是只能看到一句"AI 服务繁忙"
      meta: {
        candidates: d.candidateCount,
        elapsed: d.elapsedMs,
        degrade: d.degradeReason,
        // 被"最低匹配分"挡下的条数。它和"模型压根没匹配到"是两回事，
        // 后端已经把两者的文案分开生成了，这里只是额外标出来方便排查
        belowThreshold: d.belowThresholdCount || 0,
        minScore: d.minScore,
      },
      degraded: !!d.degradeReason,
    })
  } catch (e) {
    messages.value.push({
      role: 'ai',
      text: '请求失败了，稍后再试试。',
      error: true,
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function openItem(id) {
  router.push(`/items/${id}`)
}

/** 匹配度分档上色：高=绿、中=橙、低=灰 */
function scoreColor(score) {
  if (score >= 80) return '#10b981'
  if (score >= 60) return '#f59e0b'
  return '#9ca3af'
}
</script>

<template>
  <div class="lf-page ai-page">
    <div class="lf-page-head">
      <h1 class="lf-page-title">AI 助手</h1>
      <p class="lf-page-sub">用大白话描述你丢的东西，AI 帮你从全站招领信息里找</p>
    </div>

    <div class="lf-panel shell">
      <!-- 模式切换 -->
      <div class="shell-head">
        <el-radio-group v-model="mode" @change="switchMode">
          <el-radio-button value="query">
            <el-icon><MagicStick /></el-icon>AI 物品匹配
          </el-radio-button>
          <el-radio-button value="chat">
            <el-icon><ChatDotRound /></el-icon>AI 问答
          </el-radio-button>
        </el-radio-group>
        <span class="mode-tip">{{ meta.tip }}</span>
      </div>

      <!-- 消息区 -->
      <div ref="listRef" class="stream">
        <!-- 空状态：直接给几个能点的示例，比让用户对着空框发呆强 -->
        <div v-if="isEmpty" class="welcome">
          <div class="welcome-icon">
            <el-icon><component :is="meta.icon" /></el-icon>
          </div>
          <div class="welcome-title">{{ meta.title }}</div>
          <div class="welcome-sub">{{ meta.sub }}</div>
          <div class="samples">
            <button v-for="s in meta.samples" :key="s" class="sample" @click="ask(s)">
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
            <div v-if="m.role === 'ai'" class="bot-avatar">
              <el-icon><MagicStick /></el-icon>
            </div>

            <div class="bubble" :class="[m.role, { error: m.error }]">
              <div class="bubble-text">{{ m.text }}</div>

              <!-- 匹配结果卡片：后端返回的 matches 结构化数据，
                   改造前前端只取了 answer 那段文字，这块数据整个是丢掉的 -->
              <div v-if="m.matches && m.matches.length" class="matches">
                <div
                  v-for="mt in m.matches"
                  :key="mt.itemId"
                  class="match"
                  @click="openItem(mt.itemId)"
                >
                  <div class="match-icon" :style="{ background: categoryStyle(mt.category).bg }">
                    <span>{{ categoryStyle(mt.category).emoji }}</span>
                  </div>

                  <div class="match-main">
                    <div class="match-top">
                      <span class="match-title">{{ mt.title }}</span>
                      <span class="match-score" :style="{ color: scoreColor(mt.score) }">
                        {{ mt.score }}<i>分</i>
                      </span>
                    </div>

                    <!-- 匹配度进度条 -->
                    <div class="bar">
                      <div
                        class="bar-fill"
                        :style="{ width: mt.score + '%', background: scoreColor(mt.score) }"
                      />
                    </div>

                    <div class="match-meta">
                      <span v-if="mt.location"><el-icon><LocationInformation /></el-icon>{{ mt.location }}</span>
                      <span v-if="mt.category"><el-icon><Collection /></el-icon>{{ mt.category }}</span>
                    </div>

                    <div v-if="mt.reason" class="match-reason">{{ mt.reason }}</div>
                  </div>

                  <el-icon class="match-arrow"><ArrowRight /></el-icon>
                </div>
              </div>

              <!-- 匹配模式下一无所获。
                   ⚠️ 只在「真的没匹配上」时显示：如果是被最低分挡下的，
                   后端已经在气泡文案里说清「暂未发现高度匹配的物品 + 还有几条相似度低的」，
                   这里再补一句"没有匹配到"会把话说反，让用户以为平台上一定没有。 -->
              <div
                v-else-if="m.matches && m.matches.length === 0 && !m.error && !m.meta.belowThreshold"
                class="no-match"
              >
                没有匹配到招领信息，换个描述再试试
              </div>

              <!-- 可观测信息：平时不显眼，但排查时能看出问题出在哪一层 -->
              <div v-if="m.meta && !m.error" class="meta-line">
                <el-tag v-if="m.meta.degrade === 'LLM_ERROR'" size="small" type="danger" effect="light">
                  <el-icon><Warning /></el-icon> 大模型调用失败，本次已降级
                </el-tag>
                <el-tag v-else-if="m.meta.degrade === 'NO_CANDIDATES'" size="small" type="warning" effect="light">
                  召回为空，未调用大模型
                </el-tag>
                <span v-else>
                  从 {{ m.meta.candidates }} 条候选中匹配
                  <template v-if="m.meta.belowThreshold">
                    · 已按匹配度（≥{{ m.meta.minScore }} 分）筛掉 {{ m.meta.belowThreshold }} 条
                  </template>
                  · 耗时 {{ m.meta.elapsed }}ms
                </span>
              </div>
            </div>
          </div>

          <!-- 「正在思考」占位 -->
          <div v-if="loading" class="row row-ai">
            <div class="bot-avatar"><el-icon><MagicStick /></el-icon></div>
            <div class="bubble ai typing">
              <span /><span /><span />
            </div>
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
          :placeholder="meta.placeholder"
          @keydown.enter.exact.prevent="ask()"
        />
        <el-button type="primary" :loading="loading" class="send" @click="ask()">
          <el-icon><Promotion /></el-icon>发送
        </el-button>
      </div>
      <div class="hint">按 Enter 发送 · Shift + Enter 换行</div>
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

/* ── 顶部模式栏 ───────────────────────────────────────────── */
.shell-head {
  padding: 14px 18px;
  border-bottom: 1px solid var(--lf-border);
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.mode-tip {
  font-size: 12.5px;
  color: var(--lf-text-sub);
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

.sample:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
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

.bubble.ai {
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

/* ── 匹配结果卡片 ─────────────────────────────────────────── */
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
  font-size: 12px;
  color: var(--lf-text-sub);
}

.match-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
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

.no-match {
  margin-top: 10px;
  font-size: 13px;
  color: var(--lf-text-muted);
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
