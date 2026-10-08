<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { relativeTime, fullTime, categoryStyle } from '../utils/format'

/**
 * 我的认领申请 —— 申请人视角。
 *
 * 这个页面承担整个流程的最后一步「确认收到」：
 * 审核通过只说明发布者认可了你的描述，物品要等你真的拿到手才算归还完成。
 * 所以「确认收到」不是走个流程的按钮，它会把物品标成「已认领」并自动关闭其他人的申请。
 */

const query = reactive({ page: 1, size: 10, status: '' })
const records = ref([])
const total = ref(0)
const loading = ref(true)
const acting = ref(0)

const STATUS_TEXT = {
  PENDING: '审核中',
  APPROVED: '已通过，待交接',
  REJECTED: '已驳回',
  CANCELLED: '已撤回',
  COMPLETED: '已完成',
}
const STATUS_TAG = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'info',
  CANCELLED: 'info',
  COMPLETED: 'success',
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/claim/my', { params: query })
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

// ── 流转时间线 ──────────────────────────────────────────────

const detailDialog = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

/** 时间线节点的颜色：成功/驳回/撤回各一档，其余用默认 */
const TIMELINE_TONE = {
  APPROVED: 'success',
  COMPLETED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'info',
}

/** 把「从什么状态变成什么状态」翻成人话。这条页面上只有申请人一个视角，所以不必区分"是不是我" */
function timelineText(node) {
  if (!node.fromStatus) return '提交了认领申请'
  switch (node.toStatus) {
    case 'APPROVED':
      return '发布者通过了申请，可以联系交接了'
    case 'REJECTED':
      return node.operatorRole === 'SYSTEM'
        ? '系统自动关闭（物品已完成归还）'
        : '发布者驳回了申请'
    case 'CANCELLED':
      return '撤回了申请'
    case 'COMPLETED':
      return '确认收到物品，归还完成'
    default:
      return `状态变更为 ${node.toStatus}`
  }
}

async function openDetail(claim) {
  detailDialog.value = true
  detail.value = null
  detailLoading.value = true
  try {
    const res = await request.get(`/claim/${claim.claimId}`)
    detail.value = res.data
  } finally {
    detailLoading.value = false
  }
}

async function cancel(claim) {
  try {
    await ElMessageBox.confirm(
      '撤回后这条申请作废，同一物品上其他人的申请不受影响。需要时可以重新提交。',
      '撤回认领申请',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  acting.value = claim.claimId
  try {
    await request.post(`/claim/${claim.claimId}/cancel`)
    ElMessage.success('已撤回该认领申请')
    load()
  } finally {
    acting.value = 0
  }
}

async function complete(claim) {
  try {
    await ElMessageBox.confirm(
      '请确认你真的已经拿到物品。确认后：\n' +
      '· 这条物品会标记为「已认领」\n' +
      '· 同一物品上其他人的申请会自动关闭\n' +
      '· 该物品不再出现在 AI 匹配的候选里',
      '确认已收到物品',
      { type: 'warning', confirmButtonText: '我已收到', cancelButtonText: '还没拿到' }
    )
  } catch {
    return // 用户取消
  }
  acting.value = claim.claimId
  try {
    const res = await request.post(`/claim/${claim.claimId}/complete`)
    ElMessage.success(res.message || '已确认收到')
    load()
  } finally {
    acting.value = 0
  }
}

onMounted(load)
</script>

<template>
  <div class="lf-page">
    <div class="lf-page-head">
      <h2 class="lf-page-title">我的认领申请</h2>
      <p class="lf-page-sub">
        你提交的认领申请。发布者审核通过后，双方线下核对交接，拿到物品后在这里确认。
      </p>
    </div>

    <div class="lf-panel filters">
      <el-radio-group v-model="query.status" @change="reload">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="PENDING">审核中</el-radio-button>
        <el-radio-button value="APPROVED">待交接</el-radio-button>
        <el-radio-button value="COMPLETED">已完成</el-radio-button>
        <el-radio-button value="REJECTED">已驳回</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="loading" class="lf-panel skeleton-box">
      <el-skeleton :rows="4" animated />
    </div>

    <el-empty v-else-if="!records.length" description="还没有提交过认领申请">
      <el-button type="primary" @click="$router.push('/items')">去物品列表看看</el-button>
    </el-empty>

    <div v-else class="list">
      <div v-for="c in records" :key="c.claimId" class="lf-panel claim-card">
        <div class="card-head">
          <div class="item-line">
            <div
              class="thumb"
              :style="{ background: categoryStyle(c.itemCategory).bg }"
            >
              <img v-if="c.itemImageUrl" :src="c.itemImageUrl" :alt="c.itemTitle" />
              <span v-else class="thumb-emoji">{{ categoryStyle(c.itemCategory).emoji }}</span>
            </div>
            <div class="item-text">
              <router-link :to="`/items/${c.itemId}`" class="item-title">
                {{ c.itemTitle || '物品已删除' }}
              </router-link>
              <div class="time">
                申请于 {{ relativeTime(c.createdAt) }}
                <span v-if="c.itemLocation" class="sub">· {{ c.itemLocation }}</span>
              </div>
            </div>
          </div>
          <el-tag :type="STATUS_TAG[c.status] || 'info'" effect="light" round>
            {{ STATUS_TEXT[c.status] || c.status }}
          </el-tag>
        </div>

        <div class="proof">
          <div class="proof-label">我提交的特征证明</div>
          <p class="proof-text">{{ c.proofText }}</p>
        </div>

        <!-- 被自动关闭的也要说清原因，否则用户会以为自己的申请莫名其妙没了 -->
        <div v-if="c.rejectReason" class="meta-line reject">
          驳回理由：{{ c.rejectReason }}
        </div>
        <div v-if="c.completedAt" class="meta-line">
          <el-icon><CircleCheck /></el-icon>完成于 {{ fullTime(c.completedAt) }}
        </div>

        <div class="actions">
          <el-button
            v-if="c.status === 'APPROVED'"
            type="success"
            :loading="acting === c.claimId"
            @click="complete(c)"
          >
            <el-icon><Check /></el-icon>确认收到
          </el-button>
          <el-button link type="primary" @click="openDetail(c)">
            <el-icon><Clock /></el-icon>查看进度
          </el-button>
          <el-button
            v-if="c.status === 'PENDING'"
            link
            type="danger"
            :disabled="acting === c.claimId"
            @click="cancel(c)"
          >
            撤回申请
          </el-button>
          <span v-if="c.status === 'PENDING'" class="hint">
            发布者审核通过后，这里会出现「确认收到」按钮
          </span>
          <span v-else-if="c.status === 'APPROVED'" class="hint">
            拿到物品后再确认，确认后本条物品会标记为已认领
          </span>
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

    <!-- 认领进度：直接读 claim_log，看到的是这条申请真实发生过的每一步 -->
    <el-dialog v-model="detailDialog" title="认领进度" width="560px">
      <div v-if="detailLoading" class="skeleton-box">
        <el-skeleton :rows="4" animated />
      </div>
      <template v-else-if="detail">
        <div class="d-head">
          <el-tag :type="STATUS_TAG[detail.status] || 'info'" effect="light" round>
            {{ STATUS_TEXT[detail.status] || detail.status }}
          </el-tag>
          <span class="d-item">{{ detail.itemTitle || '物品已删除' }}</span>
        </div>
        <el-timeline class="d-timeline">
          <el-timeline-item
            v-for="(node, i) in detail.timeline"
            :key="i"
            :type="TIMELINE_TONE[node.toStatus] || 'primary'"
            :timestamp="fullTime(node.createdAt)"
            placement="top"
          >
            <div class="d-text">{{ timelineText(node) }}</div>
            <div v-if="node.remark" class="d-remark">{{ node.remark }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filters {
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

.claim-card {
  padding: 20px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.item-line {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.thumb {
  width: 52px;
  height: 52px;
  border-radius: var(--lf-radius-sm);
  display: grid;
  place-items: center;
  overflow: hidden;
  flex-shrink: 0;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-emoji {
  font-size: 24px;
}

.item-text {
  min-width: 0;
}

.item-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-color-primary);
}

.time {
  margin-top: 2px;
  font-size: 12px;
  color: var(--lf-text-muted);
}

.proof {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--lf-radius-sm);
  background: #f7f8fb;
  border: 1px solid var(--lf-border);
}

.proof-label {
  font-size: 12px;
  color: var(--lf-text-muted);
}

.proof-text {
  margin-top: 6px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.meta-line {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  color: var(--lf-text-sub);
}

.meta-line.reject {
  color: var(--lf-danger);
}

.actions {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: var(--lf-text-muted);
}

.pager {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}

/* ── 进度弹窗 ─────────────────────────────────────────────── */
.d-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.d-item {
  font-size: 14px;
  color: var(--lf-text-sub);
}

.d-timeline {
  padding-left: 4px;
}

.d-text {
  font-size: 14px;
}

.d-remark {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--lf-text-muted);
}

@media (max-width: 768px) {
  .claim-card {
    padding: 16px;
  }
}
</style>
