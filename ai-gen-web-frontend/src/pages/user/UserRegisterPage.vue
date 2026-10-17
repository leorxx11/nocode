<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { register } from '@/api/userController'

const router = useRouter()

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const submitting = ref(false)

// 提交注册表单
const handleSubmit = async (values: API.UserRegisterRequest) => {
  // 二次校验两次输入的密码是否一致
  if (values.userPassword !== values.checkPassword) {
    message.error('两次输入的密码不一致')
    return
  }
  submitting.value = true
  try {
    const res = await register(values)
    if (res.data.code === 0 && res.data.data) {
      message.success('注册成功')
      router.push({ path: '/user/login', replace: true })
    } else {
      message.error('注册失败：' + res.data.message)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div id="userRegisterPage" class="auth-page">
    <aside class="auth-story">
      <span class="auth-eyebrow">NoCode</span>
      <h2>一句话，<br />呈所想。</h2>
      <p>从灵感到网站，和 AI 一起把每一个细节打磨成形。</p>
      <div class="auth-steps"><span>01 描述</span><span>02 生成</span><span>03 发布</span></div>
    </aside>
    <div class="auth-form">
      <h1 class="title">开启你的创作</h1>
      <p class="desc">创建账号，从第一个想法开始。</p>
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
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item
          name="checkPassword"
          label="确认密码"
          :rules="[
            { required: true, message: '请确认密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.checkPassword"
            placeholder="请确认密码"
            autocomplete="new-password"
          />
        </a-form-item>
        <div class="tips">
          已有账号？
          <RouterLink to="/user/login">去登录</RouterLink>
        </div>
        <a-form-item>
          <a-button
            type="primary"
            size="large"
            :loading="submitting"
            html-type="submit"
            style="width: 100%"
            >注册</a-button
          >
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
