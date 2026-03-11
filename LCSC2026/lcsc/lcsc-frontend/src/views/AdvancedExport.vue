<template>
  <div class="advanced-export">
    <a-card title="高级导出配置" class="config-card">
      <a-form :model="filterForm" layout="vertical">

        <a-row :gutter="16" class="mb-3">
          <a-col :span="6">
            <a-form-item label="选择店铺" required>
              <a-select
                  v-model:value="filterForm.shopId"
                  placeholder="请选择店铺"
                  :options="shopOptions"
                  @change="handleShopChange"
                  allow-clear
              />
            </a-form-item>
          </a-col>

          <a-col :span="18" v-if="filterForm.shopId">
            <a-form-item label="配置方案管理">
              <a-input-group compact>
                <a-select
                    v-model:value="currentSchemeId"
                    style="width: 240px"
                    placeholder="选择已保存的方案..."
                    @change="handleApplyScheme"
                    allow-clear
                >
                  <a-select-option v-for="scheme in schemeList" :key="scheme.id" :value="scheme.id">
                    <div class="scheme-option">
                      <span class="scheme-name">{{ scheme.name }}</span>
                      <DeleteOutlined
                          class="delete-icon"
                          @click.stop="handleDeleteScheme(scheme.id)"
                          title="删除此方案"
                      />
                    </div>
                  </a-select-option>
                </a-select>

                <a-input
                    v-model:value="newSchemeName"
                    style="width: 200px"
                    placeholder="输入新方案名称"
                />

                <a-button type="primary" @click="handleSaveScheme">
                  <template #icon><SaveOutlined /></template>
                  保存本次方案
                </a-button>
              </a-input-group>
              <div class="text-xs text-gray-500 mt-1">
                * 保存方案将记录当前选中的分类、品牌、库存范围及折扣设置
              </div>
            </a-form-item>
          </a-col>
        </a-row>

        <a-divider style="margin: 12px 0 24px 0" />

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="产品分类">
              <a-button
                  block
                  @click="handleOpenCategorySelector"
              >
                <template #icon><AppstoreOutlined /></template>
                <span v-if="selectedCategories.length === 0">点击选择分类 (全部)</span>
                <span v-else>已选择 {{ selectedCategories.length }} 个分类</span>
              </a-button>
              <div v-if="selectedCategories.length > 0" class="text-xs text-gray-500 mt-1">
                支持无限层级混合多选
              </div>
            </a-form-item>
          </a-col>

          <a-col :span="8">
            <a-form-item label="品牌 (可多选)">
              <a-select
                  v-model:value="filterForm.brands"
                  mode="multiple"
                  placeholder="选择品牌(可多选)"
                  :options="brandOptions"
                  :max-tag-count="3"
                  show-search
                  option-filter-prop="label"
                  allow-clear
              >
              </a-select>
            </a-form-item>
          </a-col>

          <a-col :span="8">
            <a-form-item label="是否有图片">
              <a-select
                  v-model:value="filterForm.hasImage"
                  placeholder="选择图片筛选条件"
                  allow-clear
              >
                <a-select-option :value="undefined">全部</a-select-option>
                <a-select-option :value="true">有图片</a-select-option>
                <a-select-option :value="false">无图片</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="库存范围">
              <a-space>
                <a-input-number
                    v-model:value="filterForm.stockMin"
                    placeholder="最小值"
                    :min="0"
                    style="width: 120px"
                />
                <span>至</span>
                <a-input-number
                    v-model:value="filterForm.stockMax"
                    placeholder="最大值"
                    :min="0"
                    style="width: 120px"
                />
              </a-space>
            </a-form-item>
          </a-col>

          <a-col :span="16">
            <a-form-item label="折扣设置（%）" required>
              <a-space wrap>
                <div v-for="(_, index) in 6" :key="index" style="display: inline-block">
                  <div style="margin-bottom: 4px; font-size: 12px; color: #666">
                    {{ index + 1 }}级折扣
                  </div>
                  <a-input-number
                      v-model:value="filterForm.discounts[index]"
                      :min="1"
                      :max="100"
                      :precision="0"
                      style="width: 80px"
                  />
                </div>
              </a-space>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item>
          <a-space>
            <a-button type="primary" @click="handleAddTask" :loading="addLoading" :disabled="!filterForm.shopId">
              <template #icon><PlusOutlined /></template>
              确定添加
            </a-button>
            <a-button @click="handleResetFilter">
              <template #icon><ReloadOutlined /></template>
              重置筛选
            </a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card title="导出任务列表" class="task-list-card" style="margin-top: 16px">
      <template #extra>
        <a-space>
          <span>共 {{ taskList.length }} 个产品</span>
          <a-button
              type="primary"
              danger
              @click="handleExport"
              :loading="exportLoading"
              :disabled="taskList.length === 0"
          >
            <template #icon><DownloadOutlined /></template>
            确定导出
          </a-button>
          <a-button @click="handleClearTasks" :disabled="taskList.length === 0">
            <template #icon><DeleteOutlined /></template>
            清空列表
          </a-button>
        </a-space>
      </template>

      <a-table
          :columns="taskColumns"
          :data-source="taskList"
          :pagination="{ pageSize: 10 }"
          row-key="productCode"
          size="small"
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'index'">
            {{ index + 1 }}
          </template>
          <template v-else-if="column.key === 'categoryInfo'">
             <span v-if="record.categoryIds && record.categoryIds.length > 0">
               指定 {{ record.categoryIds.length }} 个分类
             </span>
            <span v-else>全部分类</span>
          </template>
          <template v-else-if="column.key === 'brand'">
             <span v-if="Array.isArray(record.brand) && record.brand.length > 0">
               {{ record.brand.join(', ') }}
             </span>
            <span v-else-if="record.brand && typeof record.brand === 'string'">
               {{ record.brand }}
             </span>
            <span v-else class="text-gray-500">不限</span>
          </template>
          <template v-else-if="column.key === 'discounts'">
            {{ record.discounts.join(', ') }}%
          </template>
          <template v-else-if="column.key === 'addedAt'">
            {{ formatTimestamp(record.addedAt) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" danger size="small" @click="handleRemoveTask(record.productCode)">
              删除
            </a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
        v-model:open="showCategorySelector"
        title="📂 选择导出分类"
        width="900px"
        :ok-text="'确认选择'"
        :cancel-text="'取消'"
        @ok="handleCategorySelectorOk"
    >
      <a-alert
          message="提示"
          type="info"
          description="支持无限多选。支持一/二/三级混选导出，系统会自动防重复、防超限压缩处理。"
          show-icon
          class="mb-4"
      />

      <CategoryTreeSelector
          ref="categoryTreeSelectorRef"
          :categories="allCategories"
          :selected-category-ids="selectedCategories"
          @update:selected="handleTreeSelectionChange"
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  PlusOutlined,
  DownloadOutlined,
  DeleteOutlined,
  ReloadOutlined,
  AppstoreOutlined,
  SaveOutlined
} from '@ant-design/icons-vue'
import { getAllShops } from '@/api/shop'
import { getAllCategories, getAllBrands } from '@/api/product'
import { addToTaskList, exportTaobaoExcel, type ExportTaskItem } from '@/api/export'
import type { Shop } from '@/types'
import CategoryTreeSelector from '@/components/CategoryTreeSelector.vue'

// --- 接口定义 ---
interface FilterFormState {
  shopId: number | undefined
  categoryIds: number[]
  brands: string[]
  hasImage: boolean | undefined
  stockMin: number | undefined
  stockMax: number | undefined
  discounts: number[]
}

interface ExportScheme {
  id: string
  name: string
  shopId: number
  config: Omit<FilterFormState, 'shopId'>
  updatedAt: number
}

// --- 响应式状态 ---
const filterForm = reactive<FilterFormState>({
  shopId: undefined,
  categoryIds: [],
  brands: [],
  hasImage: undefined,
  stockMin: undefined,
  stockMax: undefined,
  discounts: [90, 88, 85, 82, 80, 78]
})

const schemeList = ref<ExportScheme[]>([])
const currentSchemeId = ref<string | undefined>(undefined)
const newSchemeName = ref('')
const SCHEME_STORAGE_KEY_PREFIX = 'lcsc_export_scheme_'

const showCategorySelector = ref(false)
const allCategories = ref<any[]>([])
const selectedCategories = ref<number[]>([])
const categoryTreeSelectorRef = ref<InstanceType<typeof CategoryTreeSelector>>()

const taskList = ref<ExportTaskItem[]>([])
const addLoading = ref(false)
const exportLoading = ref(false)

const shopOptions = ref<{ label: string; value: number }[]>([])
const brandOptions = ref<{ label: string; value: string }[]>([])

const taskColumns = [
  { title: '序号', key: 'index', width: 60 },
  { title: '产品编号', dataIndex: 'productCode', key: 'productCode', width: 120 },
  { title: '分类筛选', key: 'categoryInfo', width: 150 },
  { title: '型号', dataIndex: 'model', key: 'model' },
  { title: '品牌', dataIndex: 'brand', key: 'brand', width: 150 },
  { title: '店铺', dataIndex: 'shopName', key: 'shopName', width: 150 },
  { title: '折扣配置', key: 'discounts', width: 200 },
  { title: '添加时间', key: 'addedAt', width: 160 },
  { title: '操作', key: 'action', width: 80, fixed: 'right' }
]

// --- 生命周期 ---
onMounted(() => {
  loadShops()
  loadAllCategoriesForSelector()
  loadBrands()
})

// --- 方案管理逻辑 ---
const loadSchemesForShop = (shopId: number) => {
  schemeList.value = []
  currentSchemeId.value = undefined
  newSchemeName.value = ''

  const key = `${SCHEME_STORAGE_KEY_PREFIX}${shopId}`
  const stored = localStorage.getItem(key)
  if (stored) {
    try {
      schemeList.value = JSON.parse(stored)
    } catch (e) {
      console.error('Failed to parse schemes', e)
    }
  }
}

const handleSaveScheme = () => {
  if (!filterForm.shopId) {
    message.warning('请先选择店铺')
    return
  }
  if (!newSchemeName.value.trim()) {
    message.warning('请输入方案名称')
    return
  }

  const configToSave: Omit<FilterFormState, 'shopId'> = {
    categoryIds: [...filterForm.categoryIds],
    brands: [...filterForm.brands],
    hasImage: filterForm.hasImage,
    stockMin: filterForm.stockMin,
    stockMax: filterForm.stockMax,
    discounts: [...filterForm.discounts]
  }

  const newScheme: ExportScheme = {
    id: Date.now().toString(),
    name: newSchemeName.value.trim(),
    shopId: filterForm.shopId,
    config: configToSave,
    updatedAt: Date.now()
  }

  schemeList.value.push(newScheme)

  const key = `${SCHEME_STORAGE_KEY_PREFIX}${filterForm.shopId}`
  localStorage.setItem(key, JSON.stringify(schemeList.value))

  message.success('方案保存成功')
  currentSchemeId.value = newScheme.id
  newSchemeName.value = ''
}

const handleApplyScheme = (schemeId: string) => {
  if (!schemeId) return

  const scheme = schemeList.value.find(s => s.id === schemeId)
  if (!scheme) return

  filterForm.categoryIds = [...scheme.config.categoryIds]
  filterForm.brands = [...scheme.config.brands]
  filterForm.hasImage = scheme.config.hasImage
  filterForm.stockMin = scheme.config.stockMin
  filterForm.stockMax = scheme.config.stockMax
  filterForm.discounts = [...scheme.config.discounts]

  selectedCategories.value = [...scheme.config.categoryIds]

  message.success(`已应用方案：${scheme.name}`)
}

const handleDeleteScheme = (schemeId: string) => {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这个导出方案吗？',
    onOk: () => {
      schemeList.value = schemeList.value.filter(s => s.id !== schemeId)

      if (filterForm.shopId) {
        const key = `${SCHEME_STORAGE_KEY_PREFIX}${filterForm.shopId}`
        localStorage.setItem(key, JSON.stringify(schemeList.value))
      }

      if (currentSchemeId.value === schemeId) {
        currentSchemeId.value = undefined
      }
      message.success('删除成功')
    }
  })
}

// --- 基础业务逻辑 ---
const loadShops = async () => {
  try {
    const shops = await getAllShops()
    shopOptions.value = shops.map((shop: Shop) => ({
      label: shop.shopName,
      value: shop.id
    }))
  } catch (error) {
    message.error('加载店铺列表失败')
  }
}

const loadAllCategoriesForSelector = async () => {
  try {
    const data = await getAllCategories()
    if (data && data.length > 0) {
      let mappedData = data.map((item: any) => {

        // 🌟 终极精准提取：绝对不串级！优先拿自己的 id，如果没有再按级别拿专属字段
        let trueId = item.id;
        if (!trueId) {
          if (item.categoryLevel === 'level3') {
            trueId = item.categoryLevel3Id;
          } else if (item.categoryLevel === 'level2' || item.isPureLevel2) {
            trueId = item.categoryLevel2Id;
          } else {
            trueId = item.categoryLevel1Id;
          }
        }

        // 根据级别加上后端的偏移量 (20亿 / 10亿)
        let offsetId = trueId;
        if (item.categoryLevel === 'level3') {
          offsetId = trueId + 2000000000;
        } else if (item.categoryLevel === 'level2' || item.isPureLevel2) {
          offsetId = trueId + 1000000000;
        }

        return {
          id: offsetId, // 传给 Tree 组件和后端的带偏移量 ID
          rawId: trueId, // 保留真实的数据库 ID 备用
          name: item.categoryName || item.categoryLevel3Name || item.categoryLevel2Name,

          // 必须给 level2Id 也加上 10 亿的偏移量，树组件才能把 L3 挂在 L2 下面！
          level2Id: item.categoryLevel2Id ? item.categoryLevel2Id + 1000000000 : null,

          level2Name: item.categoryLevel2Name,
          level1Id: item.categoryLevel1Id,
          level1Name: item.categoryLevel1Name || `L1-${item.categoryLevel1Id}`,
          totalProducts: item.totalProducts || item.crawledProducts || item.crawled_products || item.crawledCount || item.savedCount || 0,
          isPureLevel2: item.categoryLevel === 'level2'
        };
      })

      // 下面的排序逻辑保持不变...
      mappedData.sort((a, b) => {
        const l1A = String(a.level1Name || '')
        const l1B = String(b.level1Name || '')
        const l1Compare = l1A.localeCompare(l1B, 'en')
        if (l1Compare !== 0) return l1Compare

        const l2A = String(a.level2Name || '')
        const l2B = String(b.level2Name || '')
        const l2Compare = l2A.localeCompare(l2B, 'en')
        if (l2Compare !== 0) return l2Compare

        const nA = String(a.name || '')
        const nB = String(b.name || '')
        return nA.localeCompare(nB, 'en')
      })

      allCategories.value = mappedData
    }
  } catch (error) {
    console.error('加载所有分类失败:', error)
  }
}

const loadBrands = async () => {
  try {
    const brands = await getAllBrands()
    brandOptions.value = brands.map((brand: string) => ({
      label: brand,
      value: brand
    }))
  } catch (error) {
    message.error('加载品牌列表失败')
  }
}

const handleOpenCategorySelector = async () => {
  if (allCategories.value.length === 0) {
    await loadAllCategoriesForSelector()
  }
  showCategorySelector.value = true
}

const handleTreeSelectionChange = (ids: number[]) => {
  selectedCategories.value = ids
}

const handleCategorySelectorOk = () => {
  if (categoryTreeSelectorRef.value) {
    selectedCategories.value = categoryTreeSelectorRef.value.getSelectedIds()
  }
  filterForm.categoryIds = selectedCategories.value
  showCategorySelector.value = false
}

const handleShopChange = (val: number) => {
  if (val) {
    loadSchemesForShop(val)
  } else {
    schemeList.value = []
    currentSchemeId.value = undefined
  }
}

const handleAddTask = async () => {
  if (!filterForm.shopId) {
    message.warning('请先选择店铺')
    return
  }
  if (filterForm.discounts.length !== 6 || filterForm.discounts.some(d => !d || d < 1 || d > 100)) {
    message.warning('请填写完整的6级折扣配置（1-100之间）')
    return
  }

  addLoading.value = true
  try {
    // 💡 直接发送包含前缀（10亿/20亿）的原始ID数组
    const updatedTasks = await addToTaskList({
      shopId: filterForm.shopId!,
      categoryIds: filterForm.categoryIds, // 不再调用前端清理方法，保留分类层级标识
      brands: filterForm.brands,
      hasImage: filterForm.hasImage,
      stockMin: filterForm.stockMin,
      stockMax: filterForm.stockMax,
      discounts: filterForm.discounts,
      currentTasks: taskList.value
    })

    const addedCount = updatedTasks.length - taskList.value.length
    taskList.value = updatedTasks
    message.success(`成功添加 ${addedCount} 个产品到任务列表（已自动去重）`)
  } catch (error: any) {
    message.error('添加任务失败: ' + (error.message || '未知错误'))
  } finally {
    addLoading.value = false
  }
}

const handleExport = async () => {
  if (taskList.value.length === 0) {
    message.warning('任务列表为空，请先添加产品')
    return
  }
  exportLoading.value = true
  try {
    await exportTaobaoExcel(taskList.value)
    message.success('导出成功')
  } catch (error: any) {
    message.error('导出失败: ' + (error.message || '未知错误'))
  } finally {
    exportLoading.value = false
  }
}

const handleResetFilter = () => {
  filterForm.categoryIds = []
  selectedCategories.value = []
  filterForm.brands = []
  filterForm.hasImage = undefined
  filterForm.stockMin = undefined
  filterForm.stockMax = undefined
  filterForm.discounts = [90, 88, 85, 82, 80, 78]
  currentSchemeId.value = undefined
}

const handleClearTasks = () => {
  taskList.value = []
  message.success('已清空任务列表')
}

const handleRemoveTask = (productCode: string) => {
  taskList.value = taskList.value.filter(task => task.productCode !== productCode)
  message.success('已删除')
}

const formatTimestamp = (timestamp: number): string => {
  const date = new Date(timestamp)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
</script>

<style scoped>
.advanced-export {
  padding: 24px;
}
.config-card,
.task-list-card {
  margin-bottom: 16px;
}
.text-xs {
  font-size: 12px;
}
.text-gray-500 {
  color: #999;
}
.mt-1 {
  margin-top: 4px;
}
.mb-3 {
  margin-bottom: 12px;
}
.scheme-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.scheme-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.delete-icon {
  color: #ff4d4f;
  font-size: 14px;
  cursor: pointer;
  padding: 4px;
}
.delete-icon:hover {
  background-color: #fff1f0;
  border-radius: 4px;
}
</style>