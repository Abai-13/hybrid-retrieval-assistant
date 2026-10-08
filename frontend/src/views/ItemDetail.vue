<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { auth } from '../api/auth'
import { relativeTime, fullTime, categoryStyle } from '../utils/format'

const route = useRoute()
const router = useRouter()

const item = ref(null)
const publisherName = ref('')
const loading = ref(true)

// ── 认领申请弹窗 ──
const claimDialog = ref(false)
const submitting = ref(false)
const claimForm = ref({ proofText: '', contact: '' })

// 物品状态的中文名与配色。认领流程引入了中间的「认领中」，
// 原来「不是 UNCLAIMED 就是已认领」的二分会把它显示错
const STATUS_TEXT = { UNCLAIMED: '未认领', CLAIMING: '认领中', CLAIMED: '已认领' }
const STATUS_TAG = { UNCLAIMED: 'warning', CLAIMING: 'primary', CLAIMED: 'info' }
const MY_CLAIM_TEXT = { PENDING: '认领申请审核中', APPROVED: '认领申请已通过，请联系发布者' }

const statusText = (s) => STATUS_TEXT[s] || s
const statusTag = (s) => STATUS_TAG[s] || 'info'
const myClaimText = (s) => MY_CLAIM_TEXT[s] || s

async function load() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await request.get(`/item/${id}`)
    item.value = res.data.item
    publisherName.value = res.data.publisherName
  } finally {
    loading.value = false
  }
}

// 判断当前登录用户是不是发布者本人（userId 从 localStorage 拿，是字符串，要转数字比）
function isOwner() {
  return item.value && Number(auth.userId) === item.value.userId
}

function goReview() {
  router.push('/claims/received')
}

/**
 * 撤销「已认领」。
 *
 * 注意这里**没有**「标记为已认领」—— 那个按钮下线了。
 * 物品从「待认领」走到「已认领」必须经过认领流程（有人申请、有人审核、有人确认收到），
 * 否则库里会出现一条"已归还但没有任何申请记录"的物品，谁认领的、什么时候认领的都无从查起。
 * 保留撤销是为了纠错：物归原主后发现认错了人，得能改回来。
 */
async function undoClaimed() {
  try {
    await ElMessageBox.confirm(
      '撤销后这条物品会回到「待认领」，重新接受认领申请。已经产生的认领记录会保留。确定吗？',
      '撤销「已认领」',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '再想想' }
    )
  } catch {
    return // 用户点了取消
  }
  await request.put(`/item/${item.value.id}/status`, null, { params: { status: 'UNCLAIMED' } })
  ElMessage.success('已撤销')
  load()
}

// ── 认领申请 ──────────────────────────────────────────────

function openClaimDialog() {
  // 这个页面游客也能看，但申请必须登录（后端同样会拦，这里只是别让人白填一遍表单）
  if (!auth.isLogin) {
    ElMessage.info('登录后才能申请认领')
    router.push('/login')
    return
  }
  claimDialog.value = true
}

async function submitClaim() {
  const proofText = claimForm.value.proofText.trim()
  // 前端只挡最明显的一种：太短。其余规则（是不是自己的物品、有没有重复申请）
  // 一律以后端为准 —— 前端校验只是体验，不是防线
  if (proofText.length < 10) {
    ElMessage.warning('特征证明请至少写 10 个字，太短的描述没法用来核对')
    return
  }
  submitting.value = true
  try {
    await request.post('/claim', {
      itemId: item.value.id,
      proofText,
      contact: claimForm.value.contact.trim() || null,
    })
    ElMessage.success('申请已提交，等待发布者审核')
    claimDialog.value = false
    claimForm.value = { proofText: '', contact: '' }
    load()
  } finally {
    submitting.value = false
  }
}

// 联系方式大多是手机号/微信号，让用户手动选中复制很烦，给个一键复制
async function copyContact() {
  const text = item.value?.contact
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('联系方式已复制')
  } catch {
    // 非 HTTPS 或浏览器不支持时 clipboard 会抛错，不能让页面卡住
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

onMounted(load)
</script>

<template>
  <div class="lf-page">
    <el-button link class="back" @click="router.back()">
      <el-icon><ArrowLeft /></el-icon>返回
    </el-button>

    <!-- 加载态 -->
    <div v-if="loading" class="lf-panel loading-box">
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="item">
      <div class="lf-panel detail">
        <!-- 左：图片 / 分类占位 -->
        <div class="media">
          <img v-if="item.imageUrl" :src="item.imageUrl" :alt="item.title" />
          <div v-else class="media-ph" :style="{ background: categoryStyle(item.category).bg }">
            <span class="media-emoji">{{ categoryStyle(item.category).emoji }}</span>
            <span class="media-hint">发布者未上传图片</span>
          </div>
        </div>

        <!-- 右：信息 -->
        <div class="info">
          <h1 class="title">{{ item.title }}</h1>

          <div class="tags">
            <span class="pill" :class="item.type === 'LOST' ? 'lost' : 'found'">
              {{ item.type === 'LOST' ? '寻物' : '招领' }}
            </span>
            <el-tag :type="statusTag(item.status)" effect="light" round>
              {{ statusText(item.status) }}
            </el-tag>
            <el-tag type="info" effect="plain" round>{{ item.category || '未分类' }}</el-tag>
          </div>

          <p v-if="item.description" class="desc">{{ item.description }}</p>

          <dl class="facts">
            <div class="fact">
              <dt><el-icon><LocationInformation /></el-icon>地点</dt>
              <dd>{{ item.location || '未填写' }}</dd>
            </div>
            <div class="fact">
              <dt><el-icon><User /></el-icon>发布者</dt>
              <dd>{{ publisherName || '匿名' }}</dd>
            </div>
            <div class="fact">
              <dt><el-icon><Clock /></el-icon>发布时间</dt>
              <dd>
                {{ relativeTime(item.createdAt) }}
                <span class="sub">（{{ fullTime(item.createdAt) }}）</span>
              </dd>
            </div>
          </dl>

          <!-- 联系方式单独强调：这是整个页面用户最想拿到的信息 -->
          <div class="contact" :class="{ empty: !item.contact }">
            <div class="contact-label">联系方式</div>
            <div class="contact-value">
              <span v-if="item.contact">{{ item.contact }}</span>
              <!-- 分清两种"看不到"：被规则藏起来了，还是发布者根本就没填 -->
              <span v-else-if="!item.contactVisible" class="muted">通过认领审核后可见</span>
              <span v-else class="muted">发布者未留下联系方式</span>
              <el-button v-if="item.contact" size="small" type="primary" plain @click="copyContact">
                <el-icon><CopyDocument /></el-icon>复制
              </el-button>
            </div>
            <div v-if="!item.contactVisible" class="contact-note">
              为保证物品真的交还到失主手里，联系方式只对发布者本人和认领审核通过的人开放。
            </div>
          </div>

          <!-- 非发布者：认领入口 -->
          <div v-if="!isOwner() && item.type === 'FOUND'" class="claim-actions">
            <template v-if="item.myClaimStatus">
              <el-tag
                :type="item.myClaimStatus === 'APPROVED' ? 'success' : 'warning'"
                effect="light"
                round
                size="large"
              >
                {{ myClaimText(item.myClaimStatus) }}
              </el-tag>
              <span v-if="item.myClaimStatus === 'PENDING'" class="hint">
                发布者审核通过后，这里会显示联系方式
              </span>
              <el-button
                v-else
                link
                type="primary"
                @click="router.push('/claims/my')"
              >
                拿到物品后去「我的申请」确认收到
              </el-button>
            </template>
            <el-tag v-else-if="item.status === 'CLAIMED'" type="info" effect="light" round size="large">
              该物品已被认领
            </el-tag>
            <template v-else>
              <el-button type="primary" size="large" @click="openClaimDialog">
                我要认领
              </el-button>
              <span class="hint">需要描述只有失主才知道的特征，由发布者审核</span>
            </template>
          </div>

          <div v-if="isOwner()" class="owner-actions">
            <!-- 物品进入「认领中」说明有人申请了，把发布者引到审核台去 -->
            <el-button v-if="item.status === 'CLAIMING'" type="primary" @click="goReview">
              <el-icon><Tickets /></el-icon>去审核认领申请
            </el-button>
            <el-button v-else link type="primary" @click="goReview">查看认领申请</el-button>

            <el-button v-if="item.status === 'CLAIMED'" link type="danger" @click="undoClaimed">
              撤销「已认领」
            </el-button>
            <span class="hint">只有发布者本人能看到这些按钮</span>
          </div>
        </div>
      </div>
    </template>

    <el-empty v-else description="物品不存在或已被删除">
      <el-button type="primary" @click="router.push('/items')">返回列表</el-button>
    </el-empty>

    <!-- 认领申请：填特征证明 → 提交 → 等发布者审核 -->
    <el-dialog v-model="claimDialog" title="申请认领" width="520px">
      <el-form label-position="top">
        <el-form-item label="物品特征证明" required>
          <el-input
            v-model="claimForm.proofText"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请描述只有失主才知道的特征：划痕位置、贴纸、内含物、锁屏壁纸……"
          />
          <div class="hint">至少 10 个字。这些内容只有发布者能看到，不会公开。</div>
        </el-form-item>
        <el-form-item label="联系方式（选填）">
          <el-input
            v-model="claimForm.contact"
            maxlength="100"
            placeholder="方便发布者联系你；留空则使用账号手机号"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitClaim">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.back {
  margin-bottom: 12px;
  color: var(--lf-text-sub);
}

.loading-box {
  padding: 24px;
}

.detail {
  display: grid;
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
  gap: 28px;
  padding: 24px;
}

/* ── 左：媒体区 ───────────────────────────────────────────── */
.media img {
  width: 100%;
  border-radius: var(--lf-radius-sm);
  display: block;
}

.media-ph {
  width: 100%;
  aspect-ratio: 4 / 3;
  border-radius: var(--lf-radius-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.media-emoji {
  font-size: 80px;
  line-height: 1;
  filter: drop-shadow(0 3px 10px rgba(16, 24, 40, 0.14));
}

.media-hint {
  font-size: 13px;
  color: var(--lf-text-muted);
}

/* ── 右：信息区 ───────────────────────────────────────────── */
.title {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.3px;
}

.tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.pill {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
}

.pill.lost {
  background: var(--lf-danger);
}

.pill.found {
  background: var(--lf-success);
}

.desc {
  margin-top: 16px;
  font-size: 14px;
  line-height: 1.75;
  color: var(--lf-text-sub);
  white-space: pre-wrap;
}

/* 用 dl/dt/dd 而不是 el-descriptions：这里是「标签在上、值在下」的竖排，
   语义上就是描述列表，用原生标签比套组件更轻 */
.facts {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.fact {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.fact dt {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 88px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--lf-text-muted);
}

.fact dd {
  font-size: 14px;
}

.sub {
  color: var(--lf-text-muted);
  font-size: 12.5px;
}

/* ── 联系方式 ─────────────────────────────────────────────── */
.contact {
  margin-top: 22px;
  padding: 14px 16px;
  border-radius: var(--lf-radius-sm);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
}

.contact.empty {
  background: #fafafa;
  border-color: var(--lf-border);
}

.contact-label {
  font-size: 12px;
  color: var(--lf-text-sub);
}

.contact-value {
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.muted {
  font-size: 14px;
  font-weight: 400;
  color: var(--lf-text-muted);
}

.contact-note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--lf-text-muted);
}

/* ── 认领入口 ─────────────────────────────────────────────── */
.claim-actions {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

/* ── 发布者操作 ───────────────────────────────────────────── */
.owner-actions {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.hint {
  font-size: 12px;
  color: var(--lf-text-muted);
}

/* ── 窄屏：变上下布局 ─────────────────────────────────────── */
@media (max-width: 768px) {
  .detail {
    grid-template-columns: 1fr;
    gap: 20px;
    padding: 16px;
  }

  .title {
    font-size: 20px;
  }
}
</style>
