import { createRouter, createWebHistory } from 'vue-router'

// 路由表：路径 → 页面组件
// component 用 () => import(...) 懒加载，访问到才下载这个页面
const routes = [
  { path: '/', redirect: '/items' },
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/items', component: () => import('../views/ItemList.vue') },
  { path: '/items/:id', component: () => import('../views/ItemDetail.vue') },
  { path: '/publish', component: () => import('../views/Publish.vue') },
  { path: '/ai', component: () => import('../views/AiChat.vue') },
  { path: '/profile', component: () => import('../views/Profile.vue') },
  { path: '/claims/received', component: () => import('../views/ClaimReview.vue') },
  { path: '/claims/my', component: () => import('../views/MyClaims.vue') },
  { path: '/notifications', component: () => import('../views/Notifications.vue') },
]

const NEED_LOGIN = ['/publish', '/ai', '/profile', '/claims/received', '/claims/my', '/notifications']

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：访问需要登录的页面时，先检查有没有 token
router.beforeEach((to) => {
  const needLogin = NEED_LOGIN.includes(to.path)
  if (needLogin && !localStorage.getItem('token')) {
    return '/login'
  }
})

export default router
