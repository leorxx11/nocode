<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AppstoreOutlined,
  HomeOutlined,
  LogoutOutlined,
  MessageOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { message, type MenuProps } from 'ant-design-vue'
import { logout } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

// 菜单配置项，key 为路由路径，新增菜单只需在此追加配置
const originItems: MenuProps['items'] = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '首页',
    title: '首页',
  },
  {
    key: '/admin/userManage',
    icon: () => h(TeamOutlined),
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/appManage',
    icon: () => h(AppstoreOutlined),
    label: '应用管理',
    title: '应用管理',
  },
  {
    key: '/admin/chatManage',
    icon: () => h(MessageOutlined),
    label: '对话管理',
    title: '对话管理',
  },
]

// 根据登录用户权限过滤菜单：管理员页面仅对 admin 展示
const menuItems = computed<MenuProps['items']>(() =>
  originItems?.filter((item) => {
    const key = String(item?.key ?? '')
    if (key.startsWith('/admin')) {
      return loginUserStore.loginUser.userRole === 'admin'
    }
    return true
  }),
)

// 当前选中的菜单项，跟随路由变化
const selectedKeys = ref<string[]>([route.path])
watch(
  () => route.path,
  (path) => {
    selectedKeys.value = [path]
  },
)

// 点击菜单跳转到对应路由
const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(String(key))
}

// 用户注销
const doLogout = async () => {
  const res = await logout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({ userName: '未登录' })
    message.success('退出登录成功')
    router.push('/user/login')
  } else {
    message.error('退出登录失败：' + res.data.message)
  }
}
</script>

<template>
  <header class="global-header">
    <RouterLink to="/" class="header-left">
      <img class="logo" src="@/assets/logo.png" alt="" />
      <span class="site-title">NoCode</span>
    </RouterLink>
    <nav class="header-menu" aria-label="主导航">
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="horizontal"
        :items="menuItems"
        @click="handleMenuClick"
      />
    </nav>
    <a-dropdown v-if="loginUserStore.loginUser.id" :trigger="['click']">
      <button class="user-info" type="button" aria-label="账号菜单">
        <a-avatar :src="loginUserStore.loginUser.userAvatar" :size="30"
          ><template #icon><UserOutlined /></template
        ></a-avatar>
        <span>{{ loginUserStore.loginUser.userName ?? '无名' }}</span>
      </button>
      <template #overlay
        ><a-menu
          ><a-menu-item @click="doLogout"><LogoutOutlined /> 退出登录</a-menu-item></a-menu
        ></template
      >
    </a-dropdown>
    <a-button v-else type="primary" @click="router.push('/user/login')">登录 / 注册</a-button>
  </header>
</template>
<style scoped>
.global-header {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 0 32px;
  min-height: 68px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  color: var(--app-text);
}
.logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
}
.site-title {
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.03em;
}
.header-menu {
  flex: 1;
  min-width: 0;
}
.header-menu :deep(.ant-menu-horizontal) {
  border: 0;
  background: transparent;
  line-height: 66px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 180px;
  padding: 6px 10px;
  border: 1px solid var(--app-border);
  border-radius: 24px;
  background: var(--app-surface);
  color: var(--app-text);
  cursor: pointer;
}
.user-info span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 720px) {
  .global-header {
    gap: 8px;
    padding: 0 16px;
  }
  .site-title {
    display: none;
  }
  .user-info > span:last-child {
    display: none;
  }
}
</style>
