<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { relativeTime, categoryStyle } from '../utils/format'

/**
 * 站内通知 —— 发布后自动匹配的结果。
 *
 * 一条通知是「汇总」的：一次发布可能匹配到多条，全部内联在 matches 里，
 * 所以这里不再为每条匹配单独请求一次详情 —— 点开就能看全。
 */
const router = useRouter()

const query = reactive({ page: 1, size: 10, unreadOnly: false })
const records = ref([])
const total = ref(0)
const loading = ref(true)

/** 匹配度分档上色，和 AI 页保持同一套配色 */
function scoreColor(score) {
  if (score >= 80) return '#10b981'
  if (score >= 60) return '#f59e0b'
  return '#9ca3af'
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/notification/my', { params: query })
    records.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.page = 1
  load()
}

/** 点进某条匹配：先标记这条通知已读，再跳物品详情 */
async function openItem(notification, match) {
  if (!notification.isRead) {
    try {
      await request.put(`/notification/${notification.id}/read`)
      notification.isRead = true
    } catch {
      // 标记失败不影响去看物品 —— 用户的目标是看东西，不是改状态
    }
  }
  router.push(`/items/${match.itemId}`)
}

async function markAllRead() {
  await request.put('/notification/read-all')
  ElMessage.success('已全部标记为已读')
  load()
}

onMounted(load)
</script>

<template>
  <div class="lf-page">
    <div class="lf-page-head">
      <h2 class="lf-page-title">站内通知</h2>
      <p class="lf-page-sub">
        你发布的失物，系统会在后台自动匹配已有的招领信息；匹配度高的会在这里提醒你。
      </p>
    </div>

    <div class="lf-panel toolbar">
      <el-radio-group v-model="query.unreadOnly" @change="reload">
        <el-radio-button :value="false">全部</el-radio-button>
        <el-radio-button :value="true">未读</el-radio-button>
      </el-radio-group>
      <el-button v-if="records.some((r) => !r.isRead)" link type="primary" @click="markAllRead">
        全部标记为已读
      </el-button>
    </div>

    <div v-if="loading" class="lf-panel skeleton-box">
      <el-skeleton :rows="4" animated />
    </div>

    <el-empty
      v-else-if="!records.length"
      :description="query.unreadOnly ? '没有未读通知' : '暂时没有通知'"
    >
      <span class="hint">
        也可以直接去
        <el-button link type="primary" @click="router.push('/ai')">AI 助手</el-button>
        用大白话描述一下你丢的东西
      </span>
    </el-empty>

    <div v-else class="list">
      <div v-for="n in records" :key="n.id" class="lf-panel card" :class="{ unread: !n.isRead }">
        <div class="card-head">
          <span v-if="!n.isRead" class="dot" />
          <span class="text">{{ n.content }}</span>
          <span class="time">{{ relativeTime(n.createdAt) }}</span>
        </div>

        <!-- 匹配明细：一条通知里可能有 1~5 条，点开就能看全，不用再点第二次 -->
        <div class="matches">
          <div
            v-for="m in n.matches"
            :key="m.itemId"
            class="match"
            @click="openItem(n, m)"
          >
            <div class="match-icon" :style="{ background: categoryStyle(m.category).bg }">
              <span>{{ categoryStyle(m.category).emoji }}</span>
            </div>
            <div class="match-main">
              <div class="match-top">
                <span class="match-title">{{ m.title || '物品已删除' }}</span>
                <span class="match-score" :style="{ color: scoreColor(m.score) }">
                  {{ m.score }}<i>分</i>
                </span>
              </div>
              <div class="match-from">
                你的失物《{{ m.myItemTitle || '（已删除）' }}》
              </div>
              <div v-if="m.reason" class="match-reason">{{ m.reason }}</div>
              <div class="match-meta">
                <span v-if="m.location"><el-icon><LocationInformation /></el-icon>{{ m.location }}</span>
                <el-tag v-if="m.itemStatus === 'CLAIMED'" size="small" type="info" effect="light" round>
                  已被认领
                </el-tag>
                <el-tag v-else-if="m.itemStatus === 'CLAIMING'" size="small" type="warning" effect="light" round>
                  认领中
                </el-tag>
              </div>
            </div>
            <el-icon class="match-arrow"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
    </div>

    <div v-if="total > query.size" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size"
        @current-change="load"
      />
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
}

.skeleton-box {
  padding: 24px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card {
  padding: 18px 20px;
}

/* 未读：左侧一道色条 + 文案加粗。不用大红点，避免整页都在"喊" */
.card.unread {
  border-left: 3px solid var(--el-color-primary);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-primary);
  flex-shrink: 0;
  margin-top: 8px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.text {
  flex: 1;
  font-size: 14.5px;
  line-height: 1.7;
}

.card.unread .text {
  font-weight: 600;
}

.time {
  font-size: 12px;
  color: var(--lf-text-muted);
  flex-shrink: 0;
  padding-top: 3px;
}

.matches {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.match {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--lf-radius-sm);
  background: #f7f8fb;
  border: 1px solid var(--lf-border);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.match:hover {
  border-color: var(--el-color-primary-light-5);
  background: #fff;
}

.match-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--lf-radius-sm);
  display: grid;
  place-items: center;
  font-size: 20px;
  flex-shrink: 0;
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
  font-size: 14.5px;
  font-weight: 500;
}

.match-score {
  font-size: 15px;
  font-weight: 600;
  flex-shrink: 0;
}

.match-score i {
  font-style: normal;
  font-size: 11px;
  font-weight: 400;
  margin-left: 1px;
}

.match-from {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--lf-text-muted);
}

.match-reason {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--lf-text-sub);
}

.match-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: var(--lf-text-muted);
  flex-wrap: wrap;
}

.match-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.match-arrow {
  color: var(--lf-text-muted);
  flex-shrink: 0;
}

.hint {
  font-size: 13px;
  color: var(--lf-text-muted);
}

.pager {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}

@media (max-width: 768px) {
  .card {
    padding: 14px;
  }

  .card-head {
    flex-wrap: wrap;
  }
}
</style>
