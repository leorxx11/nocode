import { VUE_PROJECT_CODE_GEN_TYPE } from '@/constants/app'

/**
 * 全局环境配置：域名统一从环境变量读取（见根目录 .env 文件），部署时无需改代码
 */

// 后端 API 根地址
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8123/api'

// 应用生成预览域名
export const STATIC_PREVIEW_DOMAIN =
  import.meta.env.VITE_STATIC_PREVIEW_DOMAIN || `${API_BASE_URL}/static`

// 应用部署域名
export const DEPLOY_DOMAIN = import.meta.env.VITE_DEPLOY_DOMAIN || 'http://localhost:8080'

// 是否开放注册（线上构建时设 VITE_REGISTER_ENABLED=false 隐藏注册入口，后端由 user.register.enabled 控制）
export const REGISTER_ENABLED = import.meta.env.VITE_REGISTER_ENABLED !== 'false'

// 获取生成应用的静态资源预览地址，VUE 工程需要访问构建后的 dist 目录
export const getStaticPreviewUrl = (codeGenType: string, appId: string | number) => {
  const outputDir = `${codeGenType}_${appId}`
  if (codeGenType === VUE_PROJECT_CODE_GEN_TYPE) {
    return `${STATIC_PREVIEW_DOMAIN}/${outputDir}/dist/index.html`
  }
  return `${STATIC_PREVIEW_DOMAIN}/${outputDir}/`
}

// 获取应用部署后的访问地址
export const getDeployUrl = (deployKey: string) => {
  return `${DEPLOY_DOMAIN}/${deployKey}`
}
