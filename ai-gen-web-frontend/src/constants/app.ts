/**
 * 应用相关常量，与后端 AppConstant / CodeGenTypeEnum 保持一致
 */

// 精选应用的优先级
export const GOOD_APP_PRIORITY = 99

// 默认应用优先级
export const DEFAULT_APP_PRIORITY = 0

// VUE 工程生成类型
export const VUE_PROJECT_CODE_GEN_TYPE = 'vue_project'

// 停止生成时后端追加在 AI 回复末尾的提示
export const GEN_STOPPED_MARK = '\n\n（已停止生成）'

/**
 * 给已生成的部分加上停止提示（与后端保存的对话历史一致）：
 * 停在代码块中间时先补上代码块的结束标记，否则提示会被渲染进代码块里
 */
export const withStoppedMark = (content: string) => {
  const partial = content.endsWith(GEN_STOPPED_MARK)
    ? content.slice(0, -GEN_STOPPED_MARK.length)
    : content
  const fenceCount = partial.split('\n').filter((line) => line.trimStart().startsWith('```')).length
  return (fenceCount % 2 === 1 ? partial + '\n```' : partial) + GEN_STOPPED_MARK
}

// 代码生成类型到展示文案的映射
export const CODE_GEN_TYPE_MAP: Record<string, string> = {
  html: '原生 HTML 模式',
  multi_file: '原生多文件模式',
  [VUE_PROJECT_CODE_GEN_TYPE]: 'VUE 工程模式',
}

// 格式化代码生成类型
export const formatCodeGenType = (codeGenType?: string) => {
  if (!codeGenType) {
    return '未知类型'
  }
  return CODE_GEN_TYPE_MAP[codeGenType] ?? codeGenType
}
