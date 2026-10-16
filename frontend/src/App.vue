<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { auth } from './api/auth'
import request from './api/request'

const router = useRouter()
const route = useRoute()

// ── 未读通知角标 ─────────────────────────────────────────────
// 不做轮询、不用 WebSocket：进站拉一次 + 每次切换路由拉一次就够。
// 通知的来源是"自己发布物品后系统自动匹配"，那是低频事件，
// 为它挂一个常驻轮询不划算。
const unread = ref(0)

async function loadUnread() {
  if (!auth.isLogin) {
    unread.value = 0
    return
  }
  try {
    const res = await request.get('/notification/unread-count')
    unread.value = res.data?.count ?? 0
  } catch {
    // 角标拉不到不能影响页面本身 —— 它只是个提示，不是主流程
    unread.value = 0
  }
}

onMounted(loadUnread)
// 去通知页把消息读掉之后，回到别的页面角标要跟着清零
watch(() => route.path, loadUnread)

// 导航项集中定义，模板里 v-for 渲染 —— 加一个页面只改这一处
const navs = [
  { path: '/items', label: '物品列表', icon: 'Grid' },
  { path: '/publish', label: '发布物品', icon: 'Plus' },
  // 页面里只剩一个模式了（原来的「AI 问答 / AI 物品匹配」二选一已合并），
  // 导航名跟着改，免得用户点进来还在找另一个入口
  { path: '/ai', label: '找物助手', icon: 'MagicStick' },
  { path: '/claims/my', label: '我的申请', icon: 'Postcard' },
  { path: '/claims/received', label: '收到的申请', icon: 'Tickets' },
]

// 详情页 /items/3 也应该让「物品列表」保持高亮
function isActive(path) {
  return path === '/items' ? route.path.startsWith('/items') : route.path === path
}

// 昵称首字做头像
const avatarText = computed(() => (auth.nickname || '用')[0])

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app">
    <header class="nav">
      <div class="nav-inner">
        <router-link to="/items" class="logo">
          <span class="logo-mark"><el-icon><Search /></el-icon></span>
          <span class="logo-text">校园失物招领</span>
        </router-link>

        <nav class="links">
          <router-link
            v-for="n in navs"
            :key="n.path"
            :to="n.path"
            class="link"
            :class="{ active: isActive(n.path) }"
          >
            <el-icon><component :is="n.icon" /></el-icon>
            <span>{{ n.label }}</span>
          </router-link>
        </nav>

        <div class="right">
          <!-- 通知不做成第 6 个导航项（那一排已经够挤了），放用户区更贴近"给我个人的消息" -->
          <div
            v-if="auth.isLogin"
            class="bell"
            title="站内通知"
            @click="router.push('/notifications')"
          >
            <el-badge :value="unread" :hidden="!unread" :max="99">
              <el-icon :size="18"><Bell /></el-icon>
            </el-badge>
          </div>

          <el-button v-if="!auth.isLogin" type="primary" @click="router.push('/login')">
            登录 / 注册
          </el-button>

          <el-dropdown v-else trigger="click">
            <div class="user">
              <div class="avatar">{{ avatarText }}</div>
              <span class="nickname">{{ auth.nickname || '已登录' }}</span>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">
                  <el-icon><User /></el-icon>个人中心
                </el-dropdown-item>
                <el-dropdown-item divided @click="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <main class="content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <footer class="footer">
      校园失物招领平台 · 让每一件丢失的东西都能回家
    </footer>
  </div>
</template>

<style scoped>
.app {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* ── 顶部导航 ─────────────────────────────────────────────── */
.nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--lf-border);
}

.nav-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 16px;
  height: 60px;
  display: flex;
  align-items: center;
  gap: 32px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

/* 渐变方块 + 图标，比纯文字 logo 更像一个「产品」 */
.logo-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 17px;
  background: linear-gradient(135deg, var(--el-color-primary), #7c74ee);
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.35);
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.2px;
}

.links {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}

/* 导航项做成「胶囊」：hover 和选中都有底色，比单纯变字色更清楚 */
.link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--lf-text-sub);
  transition: background 0.15s, color 0.15s;
}

.link:hover {
  background: #f2f3f7;
  color: var(--lf-text);
}

.link.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

.right {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.bell {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  color: var(--lf-text-sub);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.bell:hover {
  background: #f2f3f7;
  color: var(--lf-text);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.15s;
}

.user:hover {
  background: #f2f3f7;
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, var(--el-color-primary), #7c74ee);
}

.nickname {
  font-size: 14px;
  color: var(--lf-text);
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  font-size: 12px;
  color: var(--lf-text-muted);
}

/* ── 内容区 ───────────────────────────────────────────────── */
.content {
  flex: 1;
}

.footer {
  padding: 24px 16px 32px;
  text-align: center;
  font-size: 12px;
  color: var(--lf-text-muted);
}

/* ── 路由切换的淡入淡出 ───────────────────────────────────── */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ── 窄屏：导航只留图标 ───────────────────────────────────── */
@media (max-width: 640px) {
  .nav-inner {
    gap: 12px;
  }

  .logo-text,
  .link span,
  .nickname {
    display: none;
  }

  .link {
    padding: 7px 10px;
  }
}
</style>
