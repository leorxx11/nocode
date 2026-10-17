<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogin } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import { REGISTER_ENABLED } from '@/config/env'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const submitting = ref(false)

// 提交登录表单
const handleSubmit = async (values: API.UserLoginRequest) => {
  submitting.value = true
  try {
    const res = await userLogin(values)
    if (res.data.code === 0 && res.data.data) {
      loginUserStore.setLoginUser(res.data.data)
      message.success('登录成功')
      // 登录前被拦截的页面优先跳回，否则回首页
      const redirect = (route.query.redirect as string) ?? '/'
      router.push({ path: redirect, replace: true })
    } else {
      message.error('登录失败：' + res.data.message)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div id="userLoginPage" class="auth-page">
    <aside class="auth-story">
      <span class="auth-eyebrow">AI 应用生成</span>
      <h2>一句话，<br />呈所想。</h2>
      <p>从灵感到网站，和 AI 一起把每一个细节打磨成形。</p>
      <div class="auth-steps"><span>01 描述</span><span>02 生成</span><span>03 发布</span></div>
    </aside>
    <div class="auth-form">
      <h1 class="title">欢迎回来</h1>
      <p class="desc">继续你的创作，把想法变成作品。</p>
      <a-form :model="formState" layout="vertical" @finish="handleSubmit">
        <a-form-item
          label="账号"
          name="userAccount"
          :rules="[{ required: true, message: '请输入账号' }]"
        >
          <a-input
            v-model:value="formState.userAccount"
            placeholder="请输入账号"
            autocomplete="username"
          />
        </a-form-item>
        <a-form-item
          name="userPassword"
          label="密码"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.userPassword"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </a-form-item>
        <div v-if="REGISTER_ENABLED" class="tips">
          没有账号？
          <RouterLink to="/user/register">去注册</RouterLink>
        </div>
        <a-form-item>
          <a-button
            type="primary"
            size="large"
            :loading="submitting"
            html-type="submit"
            style="width: 100%"
            >登录</a-button
          >
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
