import { api } from '@/utils/request'
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

/**
 * 高级导出请求DTO - 淘宝CSV格式
 */
export interface AdvancedExportRequest {
    shopId: number
    categoryIds?: number[]
    brands?: string[]
    hasImage?: boolean
    stockMin?: number
    stockMax?: number
    matchAny?: boolean
    discounts: number[]
}

/**
 * 导出任务项
 */
export interface ExportTaskItem {
    productCode: string
    model: string
    brand: string
    shopId: number
    shopName: string
    discounts: number[]
    addedAt: number
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
    currentTasks: ExportTaskItem[]
}

/**
 * 添加产品到任务列表（批量添加模式）
 */
export const addToTaskList = (request: AddTaskRequest): Promise<ExportTaskItem[]> => {
    return api.post('/export/add-task', request)
}

/**
 * 异步导出：提交任务，轮询状态，完成后下载
 * 🌟 新增 format 参数，默认值为 'excel'
 */
export const exportTaobaoExcel = async (tasks: ExportTaskItem[], splitSize: number = 1000, format: 'excel' | 'csv' = 'excel'): Promise<void> => {
    // 1. 提交异步任务
    // 🌟 根据传入的格式动态决定调用哪个后端接口
    const endpoint = format === 'csv' ? '/export/submit-taobao-csv' : '/export/submit-taobao-excel'

    // 💡 注意：这里改用原生的 axios.post 绕过拦截器，确保能拿到 message 字段
    const rawResponse = await axios.post(`${API_BASE_URL}/api${endpoint}?splitSize=${splitSize}`, tasks)

    // 这里的 rawResponse.data 就是你刚才发给我的那个 JSON 对象
    const resData = rawResponse.data
    let taskId: string = ''

    if (resData && resData.message) {
        taskId = resData.message
    }

    // 安全校验
    if (!taskId || taskId === 'null') {
        console.error('无法从响应中提取taskId，原始响应为:', resData)
        throw new Error('导出失败：后端返回的 message 字段为空')
    }

    console.log(`成功绕过拦截器抓取到 taskId: ${taskId} (格式: ${format})`)

    // 2. 轮询状态（轮询通常不传大对象，可以使用原来的 api 封装）
    const maxAttempts = 900
    for (let i = 0; i < maxAttempts; i++) {
        await new Promise(resolve => setTimeout(resolve, 2000))

        // 如果轮询也报任务不存在，就把下面这行也改成 axios.get
        const statusResult: any = await api.get(`/export/task-status/${taskId}`)

        // 兼容拦截器可能直接返回 data 或返回整个对象的情况
        const currentStatus = statusResult.status || statusResult.data?.status

        if (currentStatus === 'error') {
            const msg = statusResult.errorMsg || statusResult.data?.errorMsg
            throw new Error(msg || '导出失败')
        }

        if (currentStatus === 'done') {
            // 3. 下载文件
            const response = await axios.get(`${API_BASE_URL}/api/export/download/${taskId}`, {
                responseType: 'blob',
                timeout: 300000
            })

            // 🌟 动态处理默认文件名后缀
            const defaultExt = format === 'csv' ? 'csv' : 'xlsx'
            const filename = statusResult.filename || statusResult.data?.filename || `export.${defaultExt}`

            // 🌟 动态处理 MIME 类型，防止浏览器下载后无法正确识别文件
            const isZip = filename.endsWith('.zip')
            const isCsv = filename.endsWith('.csv')
            let mimeType = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
            if (isZip) {
                mimeType = 'application/zip'
            } else if (isCsv) {
                mimeType = 'text/csv'
            }

            const blob = new Blob([response.data], { type: mimeType })
            const url = window.URL.createObjectURL(blob)
            const link = document.createElement('a')
            link.href = url
            link.download = filename
            document.body.appendChild(link)
            link.click()
            document.body.removeChild(link)
            window.URL.revokeObjectURL(url)
            return
        }
    }

    throw new Error('导出超时，请检查服务器状态')
}