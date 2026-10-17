<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import {
  AimOutlined,
  ArrowLeftOutlined,
  DesktopOutlined,
  MobileOutlined,
  ReloadOutlined,
  CodeOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  DownOutlined,
  DownloadOutlined,
  EditOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  LayoutOutlined,
  SendOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { deleteApp, deleteAppByAdmin, deployApp, getAppVoById, getGenStatus } from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { API_BASE_URL, getStaticPreviewUrl } from '@/config/env'
import { VUE_PROJECT_CODE_GEN_TYPE, formatCodeGenType } from '@/constants/app'
import { useLoginUserStore } from '@/stores/loginUser'
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'
import logo from '@/assets/logo.png'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

// 应用 id 保持字符串形式，避免雪花 id 超出 JS 安全整数范围导致精度丢失
const appId = String(route.params.id ?? '')

// 应用信息
const appInfo = ref<API.AppVO>()

// 后端仅允许创建者对话和部署
const isOwner = computed(
  () => !!appInfo.value?.userId && appInfo.value.userId === loginUserStore.loginUser.id,
)
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')

// —— 对话消息 ——
interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  // 等待 AI 返回第一个片段前展示加载状态
  loading?: boolean
  // 是否正在流式输出（此阶段渲染跳过代码高亮，降低开销）
  streaming?: boolean
}

const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isStreaming = ref(false)
const isBuilding = ref(false)
const appLoading = ref(true)
const generationError = ref('')
const hasNewContent = ref(false)
const activePane = ref('chat')
const previewDevice = ref('desktop')
const statusText = computed(() => {
  if (appLoading.value) return '正在打开作品'
  if (generationError.value) return generationError.value
  if (isStreaming.value) return '正在生成网页'
  if (isBuilding.value) return '正在准备预览'
  return previewUrl.value ? '预览就绪' : '等待创作'
})
const onMessageScroll = () => {
  const el = messageListRef.value!
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) hasNewContent.value = false
}
const handleInputKeydown = (event: KeyboardEvent) => {
  if (
    event.key === 'Enter' &&
    !event.shiftKey &&
    !event.ctrlKey &&
    !event.altKey &&
    !event.metaKey &&
    !event.isComposing
  ) {
    event.preventDefault()
    doSend()
  }
}
const messageListRef = ref<HTMLDivElement>()
let eventSource: EventSource | null = null
// 流式内容批量刷新的定时器
let flushTimer: number | null = null

// —— 对话历史 ——
const HISTORY_PAGE_SIZE = 10
const historyLoading = ref(false)
// 是否还有更早的历史消息可加载
const hasMoreHistory = ref(false)
// 游标：当前已加载的最早一条消息的创建时间
let historyCursor: string | undefined

// 游标分页加载对话历史：首次加载最近 10 条，加载更多时取游标之前的一页
const loadChatHistory = async (loadMore = false) => {
  if (historyLoading.value) {
    return
  }
  historyLoading.value = true
  try {
    const res = await listAppChatHistory({
      appId: appId as unknown as number,
      pageSize: HISTORY_PAGE_SIZE,
      lastCreateTime: loadMore ? historyCursor : undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      // 接口按创建时间降序返回，反转为升序后插入消息列表头部
      const historyMessages: ChatMessage[] = [...records].reverse().map((record) => ({
        role: record.messageType === 'user' ? 'user' : 'ai',
        content: record.message ?? '',
      }))
      messages.value.unshift(...historyMessages)
      // 更新游标为已加载的最早一条消息的创建时间
      const oldest = records[records.length - 1]
      if (oldest) {
        historyCursor = oldest.createTime
      }
      // 取满一页说明可能还有更早的消息
      hasMoreHistory.value = records.length === HISTORY_PAGE_SIZE
    } else {
      message.error('加载对话历史失败：' + res.data.message)
    }
  } finally {
    historyLoading.value = false
  }
}

// 点击加载更多：加载后保持视口停留在原来阅读的位置
const loadMoreHistory = async () => {
  const el = messageListRef.value
  const prevScrollHeight = el?.scrollHeight ?? 0
  const prevScrollTop = el?.scrollTop ?? 0
  await loadChatHistory(true)
  nextTick(() => {
    if (el) {
      el.scrollTop = el.scrollHeight - prevScrollHeight + prevScrollTop
    }
  })
}

// 权限校验：只有本人能在自己的作品下对话
const inputDisabled = computed(() => !isOwner.value || isStreaming.value || isBuilding.value)
const inputPlaceholder = computed(() =>
  !isOwner.value ? '无法在别人的作品下对话哦~' : '描述越详细，页面越具体，可以一步一步完善生成效果',
)
// 非本人作品时，鼠标悬浮到输入框上给出提示（应用信息加载完成后才判断）
const inputTooltip = computed(() =>
  appInfo.value && !isOwner.value ? '无法在别人的作品下对话哦~' : '',
)

// —— 生成的网页预览 ——
const previewUrl = ref('')
const PREVIEW_POLL_INTERVAL_MS = 1000
const PREVIEW_POLL_MAX_ATTEMPTS = 480

// 生成完成后展示网站效果，附加时间戳强制 iframe 加载最新版本
const updatePreview = () => {
  if (!appInfo.value?.codeGenType) {
    return
  }
  previewUrl.value = getStaticPreviewUrl(appInfo.value.codeGenType, appId) + `?t=${Date.now()}`
}

// 应用此前生成过网站时直接加载预览；未生成则保持占位提示，避免 iframe 展示 404
const showExistingPreview = async (generatedAfter?: number): Promise<boolean> => {
  if (!appInfo.value?.codeGenType) {
    return false
  }
  const url = getStaticPreviewUrl(appInfo.value.codeGenType, appId)
  try {
    // 用 GET 探测（后端 CORS 未放行 HEAD 方法，HEAD 请求会被拦截）
    const resp = await fetch(url, { credentials: 'include', cache: 'no-store' })
    const lastModified = Date.parse(resp.headers.get('Last-Modified') ?? '')
    if (resp.ok && (!generatedAfter || lastModified > generatedAfter)) {
      previewUrl.value = `${url}?t=${Date.now()}`
      return true
    }
  } catch {
    // 探测失败视为未生成
  }
  return false
}

// VUE 工程在流式响应结束后异步构建，构建产物可访问后再刷新预览
const waitForVuePreview = async (generatedAfter: number) => {
  isBuilding.value = true
  for (let attempt = 0; attempt < PREVIEW_POLL_MAX_ATTEMPTS && isBuilding.value; attempt++) {
    if (await showExistingPreview(generatedAfter)) {
      isBuilding.value = false
      message.success('网站生成完成')
      return
    }
    await new Promise((resolve) => window.setTimeout(resolve, PREVIEW_POLL_INTERVAL_MS))
  }
  if (isBuilding.value) {
    isBuilding.value = false
    generationError.value = '预览准备超时'
    message.error('预览准备超时，请稍后重新打开作品')
  }
}

// 获取应用信息
const fetchAppInfo = async () => {
  try {
    const res = await getAppVoById({ id: appId as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data
    } else {
      generationError.value = '作品加载失败'
      message.error('获取应用信息失败：' + res.data.message)
    }
  } catch {
    generationError.value = '作品加载失败'
  } finally {
    appLoading.value = false
  }
}

// 进入页面：先加载应用信息和对话历史，再决定是否自动触发生成
onMounted(async () => {
  window.addEventListener('message', handleIframeMessage)
  await fetchAppInfo()
  if (!appInfo.value) {
    return
  }
  if (!isOwner.value) activePane.value = 'preview'
  // 对话历史仅应用创建者和管理员可见
  if (isOwner.value || isAdmin.value) {
    await loadChatHistory()
    scrollToBottom()
    // 自己的应用且没有任何对话历史，才自动发送初始提示词触发生成
    if (isOwner.value && messages.value.length === 0 && appInfo.value.initPrompt) {
      sendMessage(appInfo.value.initPrompt)
      return
    }
    // 已有至少 2 条对话记录（一问一答），说明生成过网站，直接展示
    if (messages.value.length >= 2) {
      showExistingPreview()
    }
    // 上次发送后离开了页面：生成仍在后台进行时接上，否则最后一条是用户消息说明那次生成没有完成
    if (isOwner.value && !(await resumeGeneration())) {
      if (messages.value.at(-1)?.role === 'user') {
        generationError.value = '上次生成未完成，可以重新发送'
      }
    }
  } else {
    // 非本人应用看不到历史，直接尝试展示已生成的网站
    showExistingPreview()
  }
})

// 消息更新后滚动到底部
const scrollToBottom = () => {
  hasNewContent.value = false
  nextTick(() => {
    const el = messageListRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

const closeEventSource = () => {
  eventSource?.close()
  eventSource = null
  if (flushTimer !== null) {
    window.clearInterval(flushTimer)
    flushTimer = null
  }
}

// 离开页面时断开 SSE 连接：只是不再观看，生成任务在后端继续，重新进入时可以接上
onBeforeUnmount(() => {
  window.removeEventListener('message', handleIframeMessage)
  closeEventSource()
  isBuilding.value = false
})

// 占位的 AI 消息，收到流式内容后逐段追加
const pushAiPlaceholder = () => {
  const aiMessage = reactive<ChatMessage>({
    role: 'ai',
    content: '',
    loading: true,
    streaming: true,
  })
  messages.value.push(aiMessage)
  return aiMessage
}

// 通过 SSE 与 AI 对话生成代码，流式展示回复
const sendMessage = (content: string) => {
  if (isStreaming.value) {
    return
  }
  generationError.value = ''
  const generationStartedAt = Math.floor(Date.now() / 1000) * 1000
  messages.value.push({ role: 'user', content })
  const aiMessage = pushAiPlaceholder()
  scrollToBottom()
  const url = `${API_BASE_URL}/app/chat/gen/code?appId=${appId}&message=${encodeURIComponent(content)}`
  streamGeneration(url, aiMessage, generationStartedAt)
}

// 重新进入页面时，如果上次的生成还在后台进行，就接上：先回放已生成的内容，再继续接收
const resumeGeneration = async () => {
  try {
    const res = await getGenStatus({ appId: appId as unknown as number })
    if (res.data.code !== 0 || !res.data.data) {
      return false
    }
  } catch {
    return false
  }
  generationError.value = ''
  const aiMessage = pushAiPlaceholder()
  scrollToBottom()
  streamGeneration(`${API_BASE_URL}/app/chat/gen/resume?appId=${appId}`, aiMessage, undefined, () => {
    // 查询状态后、接上之前生成刚好结束：去掉占位消息，以已保存的对话历史为准
    messages.value = []
    historyCursor = undefined
    void loadChatHistory().then(() => {
      scrollToBottom()
      void showExistingPreview()
    })
  })
  return true
}

/**
 * 接收 SSE 推送的生成内容并流式展示
 *
 * @param url SSE 地址（新对话或接上正在进行的生成）
 * @param aiMessage 承接内容的 AI 消息
 * @param generationStartedAt 本轮生成开始时间，VUE 工程据此判断构建产物是否为本轮生成；接上已有生成时不传
 * @param onEmptyDone 流结束时没有收到任何内容的回调（只在接上已有生成时使用）
 */
const streamGeneration = (
  url: string,
  aiMessage: ChatMessage,
  generationStartedAt?: number,
  onEmptyDone?: () => void,
) => {
  isStreaming.value = true

  // SSE 片段到达速度极快（每秒可达上百条），若每条都触发 Markdown 重渲染会阻塞主线程
  // 导致页面卡死。这里先把片段累积到普通变量，每 150ms 批量刷入响应式消息
  let buffer = ''
  const flushBuffer = () => {
    if (!buffer) {
      return
    }
    // 刷入前记录是否贴近底部：用户主动上翻阅读时不强制拉回
    const el = messageListRef.value
    const shouldStick = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 120
    aiMessage.content += buffer
    buffer = ''
    aiMessage.loading = false
    if (shouldStick) {
      scrollToBottom()
    } else {
      hasNewContent.value = true
    }
  }
  flushTimer = window.setInterval(flushBuffer, 150)

  // 结束流式输出：停止定时器，把剩余内容一次性刷入，并触发一次完整的高亮渲染
  const finishStream = () => {
    closeEventSource()
    flushBuffer()
    aiMessage.loading = false
    aiMessage.streaming = false
    isStreaming.value = false
  }

  eventSource = new EventSource(url, { withCredentials: true })
  eventSource.onmessage = (event) => {
    if (!event.data) {
      return
    }
    try {
      // 后端将内容包装为 {"d": "片段"}，防止流式传输丢失空格
      const chunk = JSON.parse(event.data)
      buffer += chunk.d ?? ''
    } catch (e) {
      console.error('解析 SSE 数据失败:', e)
    }
  }
  // 后端发送 done 事件表示网站文件全部生成完成
  eventSource.addEventListener('done', () => {
    finishStream()
    if (onEmptyDone && !aiMessage.content) {
      onEmptyDone()
      return
    }
    if (appInfo.value?.codeGenType === VUE_PROJECT_CODE_GEN_TYPE) {
      void waitForVuePreview(generationStartedAt ?? 0)
    } else {
      updatePreview()
      message.success('网站生成完成')
    }
  })
  // 后端的业务错误（限流、护轨拦截等）：在进入流之前就被拒绝，用 business-error 事件把具体原因带回来
  eventSource.addEventListener('business-error', (event) => {
    let errorMessage = '生成过程中出现错误'
    try {
      errorMessage = JSON.parse((event as MessageEvent).data).message || errorMessage
    } catch (e) {
      console.error('解析业务错误事件失败:', e)
    }
    finishStream()
    generationError.value = errorMessage
    aiMessage.content = `❌ ${errorMessage}`
    message.error(errorMessage)
  })
  eventSource.onerror = () => {
    finishStream()
    generationError.value = '本次生成未完成'
    if (!aiMessage.content) {
      aiMessage.content = '抱歉，本次生成失败，请稍后重试'
      message.error('对话失败，请稍后重试')
    }
  }
}

// —— 可视化编辑 ——
const previewFrameRef = ref<HTMLIFrameElement | null>(null)
const isEditMode = ref(false)
const selectedElement = ref<ElementInfo | null>(null)

const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo) => {
    selectedElement.value = elementInfo
  },
})

// 仅创建者在预览就绪、且没有在生成或构建时才能进入编辑模式
const canEdit = computed(
  () => isOwner.value && !!previewUrl.value && !isStreaming.value && !isBuilding.value,
)

const exitEditMode = () => {
  visualEditor.disableEditMode()
  isEditMode.value = false
}

const toggleEditMode = () => {
  if (isEditMode.value) {
    exitEditMode()
    return
  }
  visualEditor.init(previewFrameRef.value)
  if (!visualEditor.enableEditMode()) {
    message.warning('暂时无法进入编辑模式：预览尚未加载完成，或预览页面与主站不同源')
    return
  }
  isEditMode.value = true
}

// 清除已选中的元素（同时取消预览中的选中高亮）
const clearSelectedElement = () => {
  selectedElement.value = null
  visualEditor.clearSelection()
}

// 预览刷新后重新绑定 iframe，编辑模式下需要重新注入脚本
const onPreviewLoad = () => {
  visualEditor.init(previewFrameRef.value)
  if (isEditMode.value) {
    visualEditor.onIframeLoad()
    isEditMode.value = visualEditor.editing
  }
}

const handleIframeMessage = (event: MessageEvent) => visualEditor.handleIframeMessage(event)

// 开始生成或构建后预览会被替换，自动退出编辑模式并清除选中状态
watch([isStreaming, isBuilding], ([streaming, building]) => {
  if ((streaming || building) && (isEditMode.value || selectedElement.value)) {
    exitEditMode()
    selectedElement.value = null
  }
})

// 把选中的元素信息拼接到提示词里，让 AI 精确知道要改哪个元素
const buildElementContext = (element: ElementInfo) => {
  let context = '\n\n选中元素信息：'
  if (element.pagePath) {
    context += `\n- 页面路径: ${element.pagePath}`
  }
  context += `\n- 标签: ${element.tagName.toLowerCase()}\n- 选择器: ${element.selector}`
  if (element.textContent) {
    context += `\n- 当前内容: ${element.textContent.substring(0, 100)}`
  }
  return context
}

// 发送输入框中的消息
const doSend = () => {
  const text = userInput.value.trim()
  if (!text || inputDisabled.value) {
    return
  }
  const content = selectedElement.value ? text + buildElementContext(selectedElement.value) : text
  userInput.value = ''
  // 发送后清除选中元素并退出编辑模式
  if (selectedElement.value) {
    clearSelectedElement()
  }
  if (isEditMode.value) {
    exitEditMode()
  }
  sendMessage(content)
}

// —— 部署应用 ——
const deploying = ref(false)
const deployModalOpen = ref(false)
const deployUrl = ref('')

const doDeploy = async () => {
  deploying.value = true
  try {
    const res = await deployApp({ appId: appId as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      deployModalOpen.value = true
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } finally {
    deploying.value = false
  }
}

// —— 下载代码 ——
const downloading = ref(false)

const downloadCode = async () => {
  downloading.value = true
  try {
    // 后端直接返回文件流，这里用 fetch 读取响应并从响应头取文件名
    const response = await fetch(`${API_BASE_URL}/app/download/${appId}`, {
      method: 'GET',
      credentials: 'include',
    })
    const contentType = response.headers.get('Content-Type') || ''
    if (!response.ok || contentType.includes('application/json')) {
      // 业务异常时后端返回 JSON
      const body = contentType.includes('application/json')
        ? await response.json().catch(() => null)
        : null
      throw new Error(body?.message || `下载失败: ${response.status}`)
    }
    const contentDisposition = response.headers.get('Content-Disposition')
    const fileName = contentDisposition?.match(/filename="(.+)"/)?.[1] || `app-${appId}.zip`
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

// —— 应用详情 / 修改 / 删除 ——
const detailModalOpen = ref(false)

const goToEdit = () => {
  router.push(`/app/edit/${appId}`)
}

const doDelete = () => {
  Modal.confirm({
    title: '删除应用',
    content: '确定要删除该应用吗？删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    onOk: async () => {
      // 管理员可删除任意应用，创建者删除自己的应用
      const api = isOwner.value ? deleteApp : deleteAppByAdmin
      const res = await api({ id: appId as unknown as number })
      if (res.data.code === 0) {
        message.success('删除成功')
        router.push('/')
      } else {
        message.error('删除失败：' + res.data.message)
      }
    },
  })
}
</script>

<template>
  <div id="appChatPage">
    <!-- 顶部栏：左侧应用名称，右侧部署按钮 -->
    <div class="chat-header">
      <div class="workspace-identity">
        <RouterLink to="/" class="back-home" aria-label="返回首页"
          ><ArrowLeftOutlined
        /></RouterLink>
        <a-dropdown :trigger="['click']">
          <button type="button" class="app-title">
            <a-avatar :src="logo" :size="28" shape="square" />
            <span class="app-name">{{ appInfo?.appName || '未命名应用' }}</span>
            <DownOutlined class="app-title-arrow" />
          </button>
          <template #overlay>
            <a-menu>
              <a-menu-item key="detail" @click="detailModalOpen = true">
                <InfoCircleOutlined />
                应用详情
              </a-menu-item>
              <template v-if="isOwner || isAdmin">
                <a-menu-item key="edit" @click="goToEdit">
                  <EditOutlined />
                  修改应用
                </a-menu-item>
                <a-menu-item key="delete" danger @click="doDelete">
                  <DeleteOutlined />
                  删除应用
                </a-menu-item>
              </template>
            </a-menu>
          </template>
        </a-dropdown>
        <a-tag v-if="appInfo?.codeGenType" color="blue" class="code-gen-type-tag">
          {{ formatCodeGenType(appInfo.codeGenType) }}
        </a-tag>
        <span
          class="workspace-status"
          :class="{ 'status-error': generationError, 'status-active': isStreaming || isBuilding }"
          role="status"
          ><span></span>{{ statusText }}</span
        >
      </div>
      <a-space v-if="isOwner">
        <a-button
          :loading="downloading"
          :disabled="isStreaming || isBuilding || !previewUrl"
          @click="downloadCode"
        >
          <template #icon><DownloadOutlined /></template>
          下载代码
        </a-button>
        <a-button
          type="primary"
          :loading="deploying"
          :disabled="isStreaming || isBuilding || !previewUrl"
          @click="doDeploy"
        >
          <template #icon><CloudUploadOutlined /></template>
          部署
        </a-button>
      </a-space>
    </div>

    <div class="mobile-workspace-switch">
      <a-radio-group v-model:value="activePane" button-style="solid" aria-label="工作区"
        ><a-radio-button value="chat">对话</a-radio-button
        ><a-radio-button value="preview">预览</a-radio-button></a-radio-group
      >
    </div>
    <div class="chat-body" :class="`active-${activePane}`">
      <!-- 对话区域 -->
      <div class="chat-panel">
        <div class="panel-heading">
          <CodeOutlined /> 创作对话 <span>{{ isOwner ? '与 AI 一起打磨作品' : '作品预览' }}</span>
        </div>
        <div ref="messageListRef" class="message-list" @scroll="onMessageScroll">
          <a-skeleton
            v-if="appLoading || (historyLoading && messages.length === 0)"
            active
            :paragraph="{ rows: 5 }"
          />
          <!-- 加载更多历史消息 -->
          <div v-if="hasMoreHistory" class="load-more">
            <a-button type="link" size="small" :loading="historyLoading" @click="loadMoreHistory">
              加载更多历史消息
            </a-button>
          </div>
          <a-empty
            v-if="!appLoading && !historyLoading && messages.length === 0"
            :description="isOwner ? '写下你的想法，开始第一次创作' : '在预览中查看这个作品'"
            class="message-empty"
          />
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="message-item"
            :class="msg.role === 'user' ? 'message-user' : 'message-ai'"
          >
            <a-avatar v-if="msg.role === 'ai'" :src="logo" :size="36" class="message-avatar" />
            <div class="message-content">
              <template v-if="msg.loading"><LoadingOutlined /> AI 正在生成中...</template>
              <!-- AI 回复使用 Markdown 渲染，用户消息保持纯文本 -->
              <MarkdownRenderer
                v-else-if="msg.role === 'ai'"
                :content="msg.content"
                :streaming="msg.streaming"
              />
              <template v-else>{{ msg.content }}</template>
            </div>
            <a-avatar
              v-if="msg.role === 'user'"
              :src="appInfo?.user?.userAvatar ?? loginUserStore.loginUser.userAvatar"
              :size="36"
              class="message-avatar"
            >
              <template #icon><UserOutlined /></template>
            </a-avatar>
          </div>
        </div>
        <a-button
          v-if="hasNewContent"
          class="new-content-button"
          shape="round"
          @click="scrollToBottom"
          ><DownOutlined /> 有新内容</a-button
        >
        <!-- 可视化编辑：已选中的元素信息 -->
        <a-alert
          v-if="selectedElement"
          class="selected-element-alert"
          type="info"
          closable
          @close="clearSelectedElement"
        >
          <template #message>
            <div class="selected-element-info">
              <span class="selected-element-title"
                >已选中 &lt;{{ selectedElement.tagName.toLowerCase() }}&gt;
                <span v-if="selectedElement.id">#{{ selectedElement.id }}</span></span
              >
              <span v-if="selectedElement.textContent" class="selected-element-text">{{
                selectedElement.textContent
              }}</span>
              <code class="selected-element-selector">{{ selectedElement.selector }}</code>
            </div>
          </template>
        </a-alert>
        <!-- 用户消息输入框 -->
        <a-tooltip :title="inputTooltip" placement="topLeft">
          <div class="input-area">
            <a-textarea
              v-model:value="userInput"
              :placeholder="inputPlaceholder"
              :auto-size="{ minRows: 2, maxRows: 5 }"
              :disabled="inputDisabled"
              :maxlength="1000"
              aria-label="描述你想调整的内容"
              @keydown="handleInputKeydown"
            />
            <a-tooltip
              v-if="isOwner"
              :title="isEditMode ? '退出编辑模式' : '进入编辑模式：点击预览中的元素，再描述修改'"
            >
              <a-button
                shape="circle"
                class="edit-btn"
                :type="isEditMode ? 'primary' : 'default'"
                :danger="isEditMode"
                :disabled="!canEdit && !isEditMode"
                :aria-label="isEditMode ? '退出编辑模式' : '进入编辑模式'"
                :aria-pressed="isEditMode"
                @click="toggleEditMode"
              >
                <template #icon><AimOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-button
              type="primary"
              shape="circle"
              class="send-btn"
              aria-label="发送消息"
              :disabled="inputDisabled || !userInput.trim()"
              @click="doSend"
            >
              <template #icon><SendOutlined /></template>
            </a-button>
          </div>
        </a-tooltip>
        <div class="composer-hint">
          <span>{{
            isOwner ? 'Enter 发送 · Shift + Enter 换行' : '仅创建者可以继续编辑此作品'
          }}</span
          ><span>{{ userInput.length }}/1000</span>
        </div>
      </div>

      <!-- 网页展示区域 -->
      <div class="preview-panel">
        <div class="preview-header">
          <span class="preview-title">网页预览</span>
          <div class="preview-tools">
            <a-radio-group v-model:value="previewDevice" size="small" aria-label="预览尺寸"
              ><a-radio-button value="desktop" aria-label="桌面预览"
                ><DesktopOutlined /></a-radio-button
              ><a-radio-button value="mobile" aria-label="手机预览"
                ><MobileOutlined /></a-radio-button
            ></a-radio-group>
            <a-button
              type="text"
              size="small"
              :disabled="!previewUrl"
              aria-label="刷新预览"
              @click="updatePreview"
              ><ReloadOutlined
            /></a-button>
            <a-button
              v-if="previewUrl"
              type="text"
              size="small"
              :href="previewUrl"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="在新窗口打开预览"
              ><ExportOutlined
            /></a-button>
          </div>
        </div>
        <div
          v-if="isStreaming || isBuilding || generationError"
          class="preview-status"
          :class="{ 'status-error': generationError }"
          role="status"
        >
          <LoadingOutlined v-if="isStreaming || isBuilding" /> {{ statusText
          }}<span v-if="previewUrl && (isStreaming || isBuilding)"> · 当前显示上一版</span>
        </div>
        <div class="preview-stage" :class="{ 'mobile-preview': previewDevice === 'mobile' }">
          <iframe
            v-if="previewUrl"
            ref="previewFrameRef"
            :src="previewUrl"
            class="preview-frame"
            title="网站预览"
            @load="onPreviewLoad"
          ></iframe>
          <div v-else class="preview-placeholder">
            <div class="preview-empty-icon">
              <LoadingOutlined v-if="appLoading || isStreaming || isBuilding" /><LayoutOutlined
                v-else
              />
            </div>
            <h2>{{ statusText }}</h2>
            <p>
              {{
                generationError
                  ? '可以继续描述需求，或稍后重新打开作品'
                  : '你的想法，即将在这里呈现'
              }}
            </p>
          </div>
        </div>
      </div>
    </div>

    <!-- 应用详情弹窗 -->
    <AppDetailModal
      v-model:open="detailModalOpen"
      :app="appInfo"
      :show-actions="isOwner || isAdmin"
      @edit="goToEdit"
      @delete="doDelete"
    />

    <!-- 部署成功弹窗 -->
    <DeploySuccessModal v-model:open="deployModalOpen" :url="deployUrl" />
  </div>
</template>

<style scoped>
#appChatPage {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 0;
  height: 100%;
  background: var(--app-bg);
}
.chat-header {
  flex-shrink: 0;
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  gap: 12px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
}
.workspace-identity {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.back-home {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: 1px solid var(--app-border);
  border-radius: 9px;
  color: var(--app-muted);
}
.app-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  border: 0;
  padding: 4px;
  background: transparent;
  color: var(--app-text);
  cursor: pointer;
}
.app-name {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 600;
}
.app-title-arrow {
  font-size: 11px;
  color: var(--app-muted);
}
.code-gen-type-tag {
  margin-inline-end: 0;
  font-size: 12px;
}

.workspace-status {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--app-muted);
  font-size: 12px;
  white-space: nowrap;
}
.workspace-status > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.status-active {
  color: var(--app-primary);
}
.status-error {
  color: #b34436 !important;
}
.mobile-workspace-switch {
  display: none;
}
.chat-body {
  display: grid;
  grid-template-columns: minmax(320px, 36%) minmax(0, 1fr);
  flex: 1;
  min-height: 0;
}
.chat-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  border-right: 1px solid var(--app-border);
  background: var(--app-surface);
  padding: 0 20px 16px;
}
.panel-heading {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 18px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--app-border);
}
.panel-heading > span:last-child {
  margin-left: auto;
  color: var(--app-muted);
  font-size: 12px;
}
.message-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-anchor: none;
  padding: 22px 2px 12px;
  scrollbar-width: thin;
  scrollbar-color: #ccd9cf transparent;
}
.load-more {
  text-align: center;
  margin-bottom: 16px;
}
.message-empty {
  margin: 60px 0;
}
.message-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 24px;
}
.message-user {
  justify-content: flex-end;
}
.message-avatar {
  flex-shrink: 0;
}
.message-content {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.message-user .message-content {
  flex: initial;
  max-width: 85%;
  padding: 12px 15px;
  background: var(--app-soft);
  border-radius: 14px 14px 3px 14px;
  white-space: pre-wrap;
}
.message-user .message-avatar {
  display: none;
}
.message-ai .message-avatar {
  width: 25px !important;
  height: 25px !important;
  margin-top: 3px;
}
.input-area {
  position: relative;
  padding: 10px;
  margin-top: 10px;
  border: 1px solid var(--app-border);
  border-radius: 16px;
  transition:
    border-color 0.18s,
    box-shadow 0.18s;
}
.input-area:focus-within {
  border-color: #80ad92;
  box-shadow: 0 0 0 3px rgb(33 107 80 / 6%);
}
.input-area :deep(.ant-input) {
  padding: 2px 78px 8px 2px;
  border: none;
  box-shadow: none;
  background: transparent;
  resize: none;
}
.send-btn {
  position: absolute;
  right: 10px;
  bottom: 10px;
}
.edit-btn {
  position: absolute;
  right: 50px;
  bottom: 10px;
}
.selected-element-alert {
  margin-top: 10px;
  border-radius: 12px;
}
.selected-element-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.selected-element-title {
  font-weight: 600;
}
.selected-element-text {
  overflow: hidden;
  color: var(--app-muted, rgba(0, 0, 0, 0.55));
  text-overflow: ellipsis;
  white-space: nowrap;
}
.selected-element-selector {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.composer-hint {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 9px;
  font-size: 11px;
  color: var(--app-muted);
}
.new-content-button {
  flex-shrink: 0;
  align-self: center;
  margin: 4px 0;
  color: var(--app-primary);
}
.preview-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: 0 20px 20px;
}
.preview-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 59px;
}
.preview-title {
  font-size: 14px;
  color: var(--app-muted);
}
.preview-tools {
  display: flex;
  align-items: center;
  gap: 6px;
}
.preview-status {
  padding: 0 0 12px;
  font-size: 13px;
  color: var(--app-primary);
}
.preview-stage {
  display: flex;
  flex: 1;
  min-height: 0;
  justify-content: center;
}
.preview-frame {
  width: 100%;
  height: 100%;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: #fff;
}
.mobile-preview .preview-frame {
  width: min(390px, 100%);
}
.preview-placeholder {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
  border: 1px dashed #cbd9cf;
  border-radius: 16px;
}
.preview-empty-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--app-soft);
  color: var(--app-primary);
  font-size: 24px;
}
.preview-placeholder h2 {
  margin: 20px 0 6px;
  font-size: 18px;
  font-weight: 500;
}
.preview-placeholder p {
  margin: 0;
  color: var(--app-muted);
  font-size: 14px;
}
@media (max-width: 800px) {
  .chat-header {
    padding: 10px 14px;
  }
  .workspace-identity {
    gap: 8px;
    flex-wrap: wrap;
  }
  .workspace-status {
    display: none;
  }
  .app-name {
    max-width: min(40vw, 200px);
  }
  .app-title .ant-avatar {
    display: none;
  }
  .mobile-workspace-switch {
    display: block;
    padding: 10px 14px;
    background: var(--app-surface);
    border-bottom: 1px solid var(--app-border);
  }
  .mobile-workspace-switch :deep(.ant-radio-group) {
    display: flex;
  }
  .mobile-workspace-switch :deep(.ant-radio-button-wrapper) {
    flex: 1;
    text-align: center;
  }
  .chat-body {
    display: flex;
  }
  .chat-panel,
  .preview-panel {
    flex: 1;
    width: 100%;
  }
  .active-chat .preview-panel,
  .active-preview .chat-panel {
    display: none;
  }
  .chat-panel {
    border: 0;
    padding: 0 16px 12px;
  }
  .preview-panel {
    padding: 0 12px 12px;
  }
  .composer-hint {
    font-size: 12px;
  }
}
</style>
