<template>
  <div class="category-tree-selector">
    <a-input-search
        v-model:value="searchKeyword"
        placeholder="搜索一级/二级/三级分类名称..."
        class="mb-3"
        allow-clear
        style="margin-bottom: 16px;"
    />

    <a-space class="mb-3" style="margin-bottom: 12px;">
      <a-button size="small" @click="handleSelectAll">
        <template #icon><CheckSquareOutlined /></template>
        全选
      </a-button>
      <a-button size="small" @click="handleClearAll">
        <template #icon><MinusSquareOutlined /></template>
        清除
      </a-button>
      <a-button size="small" @click="handleExpandAll">
        <template #icon><ExpandOutlined /></template>
        展开全部
      </a-button>
      <a-button size="small" @click="handleCollapseAll">
        <template #icon><CompressOutlined /></template>
        折叠全部
      </a-button>
      <a-divider type="vertical" />
      <span class="text-muted">已选: {{ selectedCount }}/{{ allLeafCount }}</span>
    </a-space>

    <div class="tree-container">
      <a-tree
          v-if="treeData.length > 0"
          :tree-data="filteredTreeData"
          :checked-keys="checkedKeys"
          :expanded-keys="expandedKeys"
          checkable
          block-node
          @update:expanded-keys="handleExpandedKeysChange"
          @check="handleCheck"
      >
        <template #title="nodeData">
          <span class="tree-title">
            <span v-if="nodeData.level === 1" class="badge level1-badge">L1</span>
            <span v-else-if="nodeData.level === 2" class="badge level2-badge">L2</span>
            <span v-else class="badge level3-badge">L3</span>

            <span class="title-text" :title="nodeData.title">{{ nodeData.title }}</span>

            <span v-if="nodeData.productCount !== undefined" class="product-count">
              {{ nodeData.productCount }} 件
            </span>
          </span>
        </template>
      </a-tree>
      <a-empty v-else description="无分类数据" />
    </div>

    <a-divider style="margin: 12px 0" />
    <a-row :gutter="16">
      <a-col :span="12">
        <a-statistic
            title="已选择分类"
            :value="selectedCount"
            :value-style="{ color: '#1890ff' }"
        />
      </a-col>
      <a-col :span="12">
        <a-statistic
            title="总分类数"
            :value="allLeafCount"
            :value-style="{ color: '#722ed1' }"
        />
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import {
  CheckSquareOutlined,
  MinusSquareOutlined,
  ExpandOutlined,
  CompressOutlined
} from '@ant-design/icons-vue'

// --- 常量定义 ---
const L2_ID_OFFSET = 1000000000
const L3_ID_OFFSET = 2000000000

// --- 类型定义 ---
export interface CategoryItem {
  id: number
  name: string
  level2Id: number
  level2Name: string
  level1Id: number
  level1Name: string
  totalProducts?: number
  crawlStatus?: string
  isPureLevel2?: boolean
}

interface TreeNode {
  key: string
  title: string
  level: 1 | 2 | 3
  originalId: number
  productCount?: number
  children?: TreeNode[]
  isLeaf?: boolean
}

interface Props {
  categories: CategoryItem[]
  selectedCategoryIds?: number[]
}

interface Emits {
  (e: 'update:selected', ids: number[]): void
}

const props = withDefaults(defineProps<Props>(), {
  selectedCategoryIds: () => [],
  categories: () => []
})

const emit = defineEmits<Emits>()

// --- 响应式状态 ---
const searchKeyword = ref('')
const expandedKeys = ref<string[]>([])
const checkedKeys = ref<string[]>([])
const treeData = ref<TreeNode[]>([])

const idToKeyMap = ref<Map<number, string>>(new Map())

// --- 计算属性 ---
const selectedCount = computed(() => {
  let count = 0
  checkedKeys.value.forEach(key => {
    if (key.startsWith('L3-')) count++
    else if (key.startsWith('L2-')) count++
  })
  return count
})

const allLeafCount = computed(() => {
  let count = 0
  const traverse = (nodes: TreeNode[]) => {
    nodes.forEach(node => {
      if (node.isLeaf) count++
      if (node.children) traverse(node.children)
    })
  }
  traverse(treeData.value)
  return count
})

const filteredTreeData = computed(() => {
  if (!searchKeyword.value) return treeData.value
  return filterNodes(treeData.value, searchKeyword.value.toLowerCase())
})

// --- 方法 ---

function initializeTreeData() {
  const l1Map = new Map<number, { name: string; l2Map: Map<number, { name: string; l3List: CategoryItem[] }> }>()
  idToKeyMap.value.clear()

  props.categories.forEach(item => {
    if (!l1Map.has(item.level1Id)) {
      l1Map.set(item.level1Id, {
        name: item.level1Name || `一级分类${item.level1Id}`,
        l2Map: new Map()
      })
    }
    const l1Entry = l1Map.get(item.level1Id)!

    // 1. 提取纯净的二级 ID (剔除10亿前缀)
    let pureL2Id = null;
    if (item.isPureLevel2) {
      pureL2Id = item.id > L2_ID_OFFSET ? item.id - L2_ID_OFFSET : item.id;
    } else if (item.level2Id != null) {
      pureL2Id = item.level2Id > L2_ID_OFFSET ? item.level2Id - L2_ID_OFFSET : item.level2Id;
    }

    if (pureL2Id == null) return;

    if (!l1Entry.l2Map.has(pureL2Id)) {
      l1Entry.l2Map.set(pureL2Id, {
        name: item.level2Name || item.name || `二级分类${pureL2Id}`,
        l3List: []
      })
    }
    const l2Entry = l1Entry.l2Map.get(pureL2Id)!

    if (item.id) {
      l2Entry.l3List.push(item)
    }
  })

  const tree: TreeNode[] = []

  Array.from(l1Map.entries()).forEach(([l1Id, l1Data]) => {
    const l1Node: TreeNode = {
      key: `L1-${l1Id}`,
      title: l1Data.name,
      level: 1,
      originalId: l1Id,
      children: [],
      isLeaf: false
    }
    idToKeyMap.value.set(l1Id, l1Node.key);

    Array.from(l1Data.l2Map.entries()).forEach(([l2Id, l2Data]) => {
      const l2Node: TreeNode = {
        key: `L2-${l2Id}`,
        title: l2Data.name,
        level: 2,
        originalId: l2Id,
        children: [],
        isLeaf: false
      }
      idToKeyMap.value.set(Number(l2Id) + L2_ID_OFFSET, l2Node.key);

      l2Data.l3List.forEach(item => {
        if (item.isPureLevel2) {
          l2Node.isLeaf = true
          l2Node.productCount = item.totalProducts || 0
          return
        }

        // 2. 提取纯净的三级 ID (强制剔除20亿前缀)
        let pureL3Id = item.id;
        if (pureL3Id > L3_ID_OFFSET) {
          pureL3Id -= L3_ID_OFFSET;
        }

        const l3Key = `L3-${pureL3Id}`;

        // 3. 严格防重：只有不重复的 Key 才塞入节点
        if (!l2Node.children!.some(child => child.key === l3Key)) {
          l2Node.children!.push({
            key: l3Key,
            title: item.name,
            level: 3,
            originalId: pureL3Id,
            productCount: item.totalProducts || 0,
            isLeaf: true
          })
          // 4. 重建标准回显映射
          idToKeyMap.value.set(pureL3Id + L3_ID_OFFSET, l3Key)
        }
      })

      if (l2Node.children!.length === 0) {
        l2Node.isLeaf = true
      }

      l1Node.children!.push(l2Node)
    })

    if (l1Node.children!.length > 0) {
      tree.push(l1Node)
    }
  })

  treeData.value = tree

  if (expandedKeys.value.length === 0) {
    expandedKeys.value = tree.map(t => t.key)
  }
}

function filterNodes(nodes: TreeNode[], keyword: string): TreeNode[] {
  return nodes.map(node => {
    const newNode = { ...node }
    if (newNode.children) {
      newNode.children = filterNodes(newNode.children, keyword)
    }
    const matchSelf = newNode.title.toLowerCase().includes(keyword)
    const hasMatchingChildren = newNode.children && newNode.children.length > 0
    if (matchSelf || hasMatchingChildren) {
      return newNode
    }
    return null
  }).filter(Boolean) as TreeNode[]
}

function handleCheck(keys: any, info: any) {
  const checkedKeyList = Array.isArray(keys) ? keys : keys.checked
  checkedKeys.value = checkedKeyList
  emitSelectedIds(checkedKeyList)
}

function emitSelectedIds(keys: string[]) {
  const mixedIds: number[] = []

  keys.forEach(key => {
    if (key.startsWith('L3-')) {
      const rawId = parseInt(key.replace('L3-', ''))
      if (!isNaN(rawId)) mixedIds.push(rawId + L3_ID_OFFSET)
    }
    else if (key.startsWith('L2-')) {
      const rawId = parseInt(key.replace('L2-', ''))
      if (!isNaN(rawId)) mixedIds.push(rawId + L2_ID_OFFSET)
    }
    else if (key.startsWith('L1-')) {
      const rawId = parseInt(key.replace('L1-', ''))
      if (!isNaN(rawId)) mixedIds.push(rawId)
    }
  })

  const uniqueIds = Array.from(new Set(mixedIds))
  emit('update:selected', uniqueIds)
}

function handleExpandedKeysChange(keys: string[]) {
  expandedKeys.value = keys
}

function handleSelectAll() {
  const allKeys: string[] = []
  const traverse = (nodes: TreeNode[]) => {
    nodes.forEach(node => {
      if (node.isLeaf) {
        allKeys.push(node.key)
      }
      if (node.children) traverse(node.children)
    })
  }
  traverse(treeData.value)
  checkedKeys.value = allKeys
  emitSelectedIds(allKeys)
}

function handleClearAll() {
  checkedKeys.value = []
  emit('update:selected', [])
}

function handleExpandAll() {
  const allKeys: string[] = []
  const traverse = (nodes: TreeNode[]) => {
    nodes.forEach(node => {
      allKeys.push(node.key)
      if (node.children) traverse(node.children)
    })
  }
  traverse(treeData.value)
  expandedKeys.value = allKeys
}

function handleCollapseAll() {
  expandedKeys.value = []
}

defineExpose({
  getSelectedIds: () => {
    const mixedIds: number[] = []
    checkedKeys.value.forEach(key => {
      if (key.startsWith('L3-')) {
        const rawId = parseInt(key.replace('L3-', ''))
        if (!isNaN(rawId)) mixedIds.push(rawId + L3_ID_OFFSET)
      }
      else if (key.startsWith('L2-')) {
        const rawId = parseInt(key.replace('L2-', ''))
        if (!isNaN(rawId)) mixedIds.push(rawId + L2_ID_OFFSET)
      }
      else if (key.startsWith('L1-')) {
        const rawId = parseInt(key.replace('L1-', ''))
        if (!isNaN(rawId)) mixedIds.push(rawId)
      }
    })
    return Array.from(new Set(mixedIds))
  }
})

// --- 监听器 ---
watch(
    () => props.selectedCategoryIds,
    (newIds) => {
      if (newIds && newIds.length > 0) {
        const keysToCkeck: string[] = []
        newIds.forEach(id => {
          const key = idToKeyMap.value.get(id)
          if (key) keysToCkeck.push(key)
        })
        checkedKeys.value = keysToCkeck
      } else {
        checkedKeys.value = []
      }
    },
    { immediate: false }
)

watch(
    () => props.categories,
    (newVal) => {
      if (newVal && newVal.length > 0) {
        initializeTreeData()
        if (props.selectedCategoryIds && props.selectedCategoryIds.length > 0) {
          nextTick(() => {
            const keysToCkeck: string[] = []
            props.selectedCategoryIds!.forEach(id => {
              const key = idToKeyMap.value.get(id)
              if (key) keysToCkeck.push(key)
            })
            checkedKeys.value = keysToCkeck
          })
        }
      }
    },
    { immediate: true, deep: true }
)
</script>

<style scoped lang="scss">
.category-tree-selector {
  width: 100%;
}

.tree-container {
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 8px;
  max-height: 500px;
  overflow-y: auto;
}

.tree-title {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;

  .badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 20px;
    border-radius: 2px;
    font-size: 11px;
    font-weight: bold;
    color: white;
    min-width: 24px;
  }

  .level1-badge {
    background-color: #1890ff; /* 蓝色 */
  }

  .level2-badge {
    background-color: #52c41a; /* 绿色 */
  }

  .level3-badge {
    background-color: #722ed1; /* 紫色 */
  }

  .title-text {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .product-count {
    color: #999;
    font-size: 12px;
    margin-left: 8px;
    min-width: 60px;
    text-align: right;
  }
}

.text-muted {
  color: #8c8c8c;
  font-size: 12px;
}

:deep(.ant-tree-node-content-wrapper) {
  display: flex;
}
:deep(.ant-tree-title) {
  width: 100%;
}
</style>