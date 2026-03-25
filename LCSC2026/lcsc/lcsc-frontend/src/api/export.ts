import { api } from '@/utils/request'

/**
 * 高级导出请求DTO - 淘宝CSV格式
 */
export interface AdvancedExportRequest {
  shopId: number                    // 选择的店铺ID（单选，必填）
  categoryIds?: number[]            // 分类ID数组（支持多选）
  brands?: string[]                 // 品牌名称数组（支持多选）
  hasImage?: boolean                // 是否有图片（可选：true/false/null）
  stockMin?: number                 // 库存最小值（可选）
  stockMax?: number                 // 库存最大值（可选）
  matchAny?: boolean                // 任意满足标识
  discounts: number[]               // 6级价格折扣配置
}

/**
 * 导出任务项
 */
export interface ExportTaskItem {
  productCode: string               // 产品编号（唯一标识）
  model: string                     // 产品型号
  brand: string                     // 品牌名称
  shopId: number                    // 关联的店铺ID
  shopName: string                  // 店铺名称（用于前端显示）
  discounts: number[]               // 6级价格折扣配置
  addedAt: number                   // 添加时间戳
}

/**
 * 添加任务请求
 */
export interface AddTaskRequest {
  shopId: number
  categoryIds?: number[]
  brands?: string[]
  hasImage?: boolean
  stockMin?: number
  stockMax?: number
  matchAny?: boolean
  discounts: number[]
  currentTasks: ExportTaskItem[]    // 当前任务列表
}

/**
 * 添加产品到任务列表（批量添加模式）
 */
export const addToTaskList = (request: AddTaskRequest): Promise<ExportTaskItem[]> => {
  return api.post('/export/add-task', request)
}

/**
 * 导出任务列表为淘宝Excel格式 (支持超过 N 条自动打ZIP包)
 * @param tasks 任务列表
 * @param splitSize 每个表格切分的条数 (默认1000)
 */
export const exportTaobaoExcel = async (tasks: ExportTaskItem[], splitSize: number = 1000): Promise<void> => {
  // 1. 将 splitSize 拼接到 URL 后面传给后端
  const response = await api.post(`/export/export-taobao-excel?splitSize=${splitSize}`, tasks, {
    responseType: 'blob'
  })

  // 2. 动态判断后端回传的是单个 Excel 还是 ZIP 压缩包
  const isZip = tasks.length > splitSize
  const mimeType = isZip
      ? 'application/zip'
      : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  const extension = isZip ? '.zip' : '.xlsx'

  // 创建下载链接
  const blob = new Blob([response as any], { type: mimeType })
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url

  // 生成文件名
  const now = new Date()
  const dateStr = now.toISOString().slice(0, 10).replace(/-/g, '') + '_' +
      now.toTimeString().slice(0, 8).replace(/:/g, '')
  link.download = `${dateStr}_高级导出${extension}`

  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}