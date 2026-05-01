<template>
  <div class="brand-management">
    <a-card title="品牌管理">
      <a-row :gutter="16" class="mb-4">
        <a-col :span="10">
          <a-input
            v-model:value="keyword"
            placeholder="搜索原名称或自定义名称"
            allow-clear
            @change="handleSearch"
          />
        </a-col>
        <a-col :span="8">
          <a-radio-group v-model:value="editedFilter" @change="handleSearch" button-style="solid">
            <a-radio-button :value="undefined">全部</a-radio-button>
            <a-radio-button :value="true">已编辑</a-radio-button>
            <a-radio-button :value="false">未编辑</a-radio-button>
          </a-radio-group>
        </a-col>
        <a-col :span="6" style="text-align: right; color: #888;">
          共 {{ filteredList.length }} 个品牌
        </a-col>
      </a-row>

      <a-table
        :data-source="filteredList"
        :loading="loading"
        :pagination="pagination"
        @change="handleTableChange"
        row-key="originalName"
        size="small"
        bordered
      >
        <a-table-column title="原品牌名称" data-index="originalName" key="originalName" :width="300" />
        <a-table-column title="自定义名称" key="customName">
          <template #default="{ record }">
            <a-input
              v-model:value="record.customName"
              placeholder="输入自定义名称（留空则使用原名称）"
              allow-clear
              style="max-width: 400px"
            />
          </template>
        </a-table-column>
        <a-table-column title="打折方案" key="discountScheme" :width="150">
          <template #default="{ record }">
            <a-select v-model:value="record.discountScheme" style="width: 100%">
              <a-select-option :value="0">默认折扣</a-select-option>
              <a-select-option :value="1">品牌折扣</a-select-option>
              <a-select-option :value="2">立创折扣</a-select-option>
            </a-select>
          </template>
        </a-table-column>
        <a-table-column title="操作" key="action" :width="100">
          <template #default="{ record }">
            <a-button
              type="primary"
              size="small"
              :loading="savingMap[record.originalName]"
              @click="handleSave(record)"
            >
              保存
            </a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { getBrandList, saveBrandCustomName } from '@/api/brand'

const keyword = ref('')
const editedFilter = ref<boolean | undefined>(undefined)
const loading = ref(false)

const pagination = reactive({ current: 1, pageSize: 20, showSizeChanger: true, showQuickJumper: true, pageSizeOptions: ['20', '50', '100', '200'] })
const handleTableChange = (pag: any) => {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
}

// 🌟 修正点 1：只保留这一个包含 discountScheme 字段的定义，删掉底下的重复项
const brandList = ref<Array<{ originalName: string; customName: string; discountScheme: number; edited: boolean }>>([])
const savingMap = reactive<Record<string, boolean>>({})

const filteredList = computed(() => {
  let list = brandList.value
  if (editedFilter.value === true) list = list.filter(b => b.edited)
  else if (editedFilter.value === false) list = list.filter(b => !b.edited)
  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    list = list.filter(b =>
        b.originalName.toLowerCase().includes(kw) ||
        (b.customName && b.customName.toLowerCase().includes(kw))
    )
  }
  return list
})

const loadBrands = async () => {
  loading.value = true
  try {
    const res: any = await getBrandList()
    let list: any[] = []
    if (Array.isArray(res)) list = res
    else if (res?.data && Array.isArray(res.data)) list = res.data
    else if (res?.data?.data && Array.isArray(res.data.data)) list = res.data.data
    brandList.value = list
  } catch (e) {
    message.error('加载品牌列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  // filteredList is computed, no action needed
}

// 🌟 修正点 2：保留带 discountScheme 的保存方法
const handleSave = async (record: any) => {
  savingMap[record.originalName] = true
  try {
    await saveBrandCustomName({
      originalName: record.originalName,
      customName: record.customName || '',
      discountScheme: record.discountScheme || 0
    })
    record.edited = !!(record.customName && record.customName.trim())
    message.success('保存成功')
  } catch (e) {
    message.error('保存失败')
  } finally {
    savingMap[record.originalName] = false
  }
}

onMounted(loadBrands)
</script>
