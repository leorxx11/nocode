import { message } from 'ant-design-vue'
import router from '@/router'
import { useLoginUserStore } from '@/stores/loginUser'
import { REGISTER_ENABLED } from '@/config/env'

// 是否已首次获取过登录用户信息（避免每次路由切换都请求后端）
let firstFetchLoginUser = true

/**
 * 全局权限校验：每次路由切换时判断当前用户能否访问目标页面
 */
router.beforeEach(async (to, from, next) => {
  const loginUserStore = useLoginUserStore()
  let loginUser = loginUserStore.loginUser

  // 首次访问时等待后端返回登录信息，保证刷新页面后权限判断正确
  if (firstFetchLoginUser) {
    await loginUserStore.fetchLoginUser()
    loginUser = loginUserStore.loginUser
    firstFetchLoginUser = false
  }

  const toUrl = to.fullPath
  // 关闭注册时，注册页直接跳到登录页
  if (!REGISTER_ENABLED && to.name === 'userRegister') {
    next('/user/login')
    return
  }
  // 管理员页面权限校验（以 /admin 开头或 meta.access 为 admin 的路由）
  if (toUrl.startsWith('/admin') || to.meta.access === 'admin') {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限访问该页面')
      next(`/user/login?redirect=${to.fullPath}`)
      return
    }
  }
  next()
})
