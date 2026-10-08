<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { relativeTime } from '../utils/format'

/**
 * 审核台 —— 我发布的招领信息收到的认领申请。
 *
 * 页面的核心是「比对」：左边是申请人写的特征证明，右边是发布者自己知道的事实。
 * 所以证明文字要完整展示、图片要能点开放大，不能截断成一行摘要 ——
 * 截断了就没法判断真假，这个页面也就失去了意义。
 */

const query = reactive({ page: 1, size: 10, status: '' })
const records = ref([])
const total = ref(0)
const loading = ref(true)
const acting = ref(0) // 正在处理的申请ID，用来禁用按钮防重复点击

const STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
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
    const res = await request.get('/claim/received', { params: query })
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

async function approve(claim) {
  try {
    await ElMessageBox.confirm(
      '通过后申请人就能看到你的联系方式，你们线下核对并交接物品。' +
      '注意：通过之后物品仍然显示「认领中」，要等对方确认收到才会变成「已认领」。',
      '确认通过该认领申请',
      { type: 'warning', confirmButtonText: '通过', cancelButtonText: '再想想' }
    )
  } catch {
    return // 用户取消
  }
  acting.value = claim.claimId
  try {
    await request.post(`/claim/${claim.claimId}/approve`)
    ElMessage.success('已通过，申请人现在可以看到你的联系方式')
    load()
  } finally {
    acting.value = 0
  }
}

async function reject(claim) {
  let reason
  try {
    // 用 prompt 而不是 confirm：驳回理由不是走过场，
    // 它是申请人唯一能知道自己为什么没通过的信息，也是这条记录里最有价值的留痕
    const res = await ElMessageBox.prompt(
      '申请人会看到这条理由，也可能据此补充证明后重新申请',
      '驳回认领申请',
      {
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：描述的特征和实物对不上',
        inputValidator: (v) => (v && v.trim().length >= 2) || '请填写驳回理由',
      }
    )
    reason = res.value
  } catch {
    return // 用户取消
  }
  acting.value = claim.claimId
  try {
    await request.post(`/claim/${claim.claimId}/reject`, { reason: reason.trim() })
    ElMessage.success('已驳回')
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
      <h2 class="lf-page-title">收到的认领申请</h2>
      <p class="lf-page-sub">
        别人认为你拾到的物品是他的。核对特征证明后决定通过还是驳回。
      </p>
    </div>

    <div class="lf-panel filters">
      <el-radio-group v-model="query.status" @change="reload">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="PENDING">待审核</el-radio-button>
        <el-radio-button value="APPROVED">已通过</el-radio-button>
        <el-radio-button value="REJECTED">已驳回</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="loading" class="lf-panel skeleton-box">
      <el-skeleton :rows="4" animated />
    </div>

    <el-empty
      v-else-if="!records.length"
      :description="query.status === 'PENDING' ? '没有待审核的申请' : '还没有收到认领申请'"
    />

    <div v-else class="list">
      <div v-for="c in records" :key="c.claimId" class="lf-panel claim-card">
        <div class="card-head">
          <div class="who">
            <div class="avatar">{{ (c.applicantNickname || '匿')[0] }}</div>
            <div class="who-text">
              <div class="name">{{ c.applicantNickname || '匿名用户' }}</div>
              <div class="time">申请于 {{ relativeTime(c.createdAt) }}</div>
            </div>
          </div>
          <el-tag :type="STATUS_TAG[c.status] || 'info'" effect="light" round>
            {{ STATUS_TEXT[c.status] || c.status }}
          </el-tag>
        </div>

        <div class="item-line">
          <router-link :to="`/items/${c.itemId}`" class="item-title">
            {{ c.itemTitle || '物品已删除' }}
          </router-link>
          <el-tag v-if="c.itemCategory" size="small" type="info" effect="plain" round>
            {{ c.itemCategory }}
          </el-tag>
        </div>

        <!-- 特征证明是页面的主角，完整展示、不截断 -->
        <div class="proof">
          <div class="proof-label">物品特征证明</div>
          <p class="proof-text">{{ c.proofText }}</p>
          <el-image
            v-if="c.proofImage"
            :src="c.proofImage"
            :preview-src-list="[c.proofImage]"
            fit="cover"
            class="proof-img"
            preview-teleported
          />
        </div>

        <div v-if="c.contact" class="meta-line">
          <el-icon><Message /></el-icon>联系方式：<strong>{{ c.contact }}</strong>
        </div>
        <div v-if="c.rejectReason" class="meta-line reject">
          驳回理由：{{ c.rejectReason }}
        </div>

        <div v-if="c.status === 'PENDING'" class="actions">
          <el-button
            type="success"
            :loading="acting === c.claimId"
            @click="approve(c)"
          >
            <el-icon><Check /></el-icon>通过
          </el-button>
          <el-button
            type="danger"
            plain
            :disabled="acting === c.claimId"
            @click="reject(c)"
          >
            <el-icon><Close /></el-icon>驳回
          </el-button>
          <span class="hint">通过后对方才能看到你的联系方式</span>
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
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.who {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, var(--el-color-primary), #7c74ee);
  flex-shrink: 0;
}

.name {
  font-size: 14px;
  font-weight: 500;
}

.time {
  font-size: 12px;
  color: var(--lf-text-muted);
}

.item-line {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.item-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-color-primary);
}

.proof {
  margin-top: 14px;
  padding: 14px 16px;
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
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.proof-img {
  margin-top: 10px;
  width: 160px;
  height: 160px;
  border-radius: var(--lf-radius-sm);
  border: 1px solid var(--lf-border);
  cursor: zoom-in;
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

/* 窄屏：卡片内边距收一点 */
@media (max-width: 768px) {
  .claim-card {
    padding: 16px;
  }
}
</style>
