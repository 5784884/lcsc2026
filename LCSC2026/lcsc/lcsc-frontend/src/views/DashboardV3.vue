<template>
  <div class="dashboard-v3">
    <a-card class="panel-card mb-4" title="📂 分类管理">
      <a-alert
          v-if="!systemStatus.categoriesSynced"
          type="warning"
          message="系统检测到尚未同步分类信息"
          description="请先爬取官方分类数据，才能开始爬取产品"
          show-icon
          class="mb-3"
      />

      <a-row :gutter="16" v-if="!systemStatus.categoriesSynced">
        <a-col :span="12">
          <a-button
              type="primary"
              size="large"
              :loading="syncLoading"
              @click="handleSyncCategories"
              block
          >
            <template #icon><CloudSyncOutlined /></template>
            爬取官方分类
          </a-button>
        </a-col>
        <a-col :span="12">
          <a-typography-text type="secondary" style="line-height: 32px; display: block; text-align: center;">
            预计1-2分钟，爬取立创商城所有三级分类树
          </a-typography-text>
        </a-col>
      </a-row>

      <a-row :gutter="16" v-else>
        <a-col :span="8">
          <a-statistic
              title="总分类数"
              :value="allCategories.length"
              suffix="个分类"
          />
        </a-col>
        <a-col :span="8">
          <a-statistic
              title="已爬取产品"
              :value="systemStatus.totalProductsInDb"
              suffix="个产品"
          />
        </a-col>
        <a-col :span="8">
          <a-space>
            <a-button @click="handleSyncCategories" :loading="syncLoading">
              <template #icon><ReloadOutlined /></template>
              重新同步
            </a-button>
            <a-tag v-if="isRunning" color="processing">
              <template #icon><SyncOutlined spin /></template>
              运行中
            </a-tag>
            <a-tag v-else-if="isPaused" color="warning">
              <template #icon><PauseCircleOutlined /></template>
              已暂停
            </a-tag>
            <a-tag v-else color="success">
              <template #icon><CheckCircleOutlined /></template>
              空闲
            </a-tag>
          </a-space>
        </a-col>
      </a-row>
    </a-card>

    <a-card
        v-if="systemStatus.categoriesSynced"
        class="panel-card mb-4"
        title="🚀 爬取控制"
    >
      <a-row :gutter="16" class="mb-3">
        <a-col :span="24">
          <a-radio-group v-model:value="crawlMode" button-style="solid" size="large">
            <a-radio-button value="full">全量爬取</a-radio-button>
            <a-radio-button value="custom">自定义分类</a-radio-button>
          </a-radio-group>

          <span style="margin-left: 24px; font-size: 14px;">
            <span>PDF 文件下载：</span>
            <a-switch
                v-model:checked="savePdfEnabled"
                checked-children="开启"
                un-checked-children="关闭"
                :disabled="isRunning"
                @change="handlePdfToggle"
            />
            <span class="text-xs text-gray-500 ml-2" v-if="!savePdfEnabled">关闭可加快整体爬取速度</span>
          </span>
        </a-col>
      </a-row>

      <a-row :gutter="16" v-if="crawlMode === 'full'">
        <a-col :span="8">
          <a-alert type="info" :message="`全量爬取模式`" :description="`将爬取数据库中所有已同步的分类产品数据`" show-icon class="mb-3" />
        </a-col>
        <a-col :span="16">
          <a-space style="float: right;">
            <a-button v-if="!isRunning && (queueStatus.pending > 0 || queueStatus.completed > 0 || queueStatus.failed > 0)" size="large" @click="handleClearCrawler">
              <template #icon><DeleteOutlined /></template>
              清空队列
            </a-button>

            <a-button v-if="(isPaused || queueStatus.pending > 0) && !isRunning" type="primary" style="background-color: #52c41a; border-color: #52c41a;" size="large" @click="handleResumeCrawler">
              <template #icon><PlayCircleOutlined /></template>
              继续爬取
            </a-button>

            <a-button v-if="!isRunning && !isPaused" type="primary" size="large" @click="handleStartFullCrawl">
              <template #icon><PlayCircleOutlined /></template>
              {{ queueStatus.pending > 0 ? '清空旧任务重新全量' : '开始全量爬取' }}
            </a-button>

            <a-button v-if="isRunning && !isPaused" size="large" @click="handlePauseCrawler" style="background-color: #faad14; color: white; border-color: #faad14;">
              <template #icon><PauseCircleOutlined /></template>
              暂停
            </a-button>

            <a-button v-if="isRunning || isPaused" danger size="large" @click="handleStopCrawler">
              <template #icon><StopOutlined /></template>
              停止
            </a-button>
          </a-space>
        </a-col>
      </a-row>

      <a-row :gutter="16" v-else>
        <a-col :span="20">
          <a-button type="primary" size="large" @click="handleOpenCategorySelector" :disabled="isRunning">
            <template #icon><AppstoreOutlined /></template>
            选择要爬取的分类（{{ selectedCategories.length }}个已选）
          </a-button>

          <a-button v-if="!isRunning && (queueStatus.pending > 0 || queueStatus.completed > 0 || queueStatus.failed > 0)" size="large" class="ml-3" @click="handleClearCrawler">
            <template #icon><DeleteOutlined /></template>
            清空队列
          </a-button>

          <a-button v-if="(isPaused || queueStatus.pending > 0) && !isRunning" type="primary" style="background-color: #52c41a; border-color: #52c41a;" size="large" class="ml-3" @click="handleResumeCrawler">
            <template #icon><PlayCircleOutlined /></template>
            继续爬取
          </a-button>

          <a-button v-if="selectedCategories.length > 0 && !isRunning && !isPaused" type="primary" size="large" class="ml-3" @click="handleStartBatchCrawl">
            <template #icon><PlayCircleOutlined /></template>
            {{ queueStatus.pending > 0 ? '清空并爬取选中分类' : '开始爬取选中分类' }}
          </a-button>

          <a-button v-if="isRunning && !isPaused" size="large" class="ml-3" @click="handlePauseCrawler" style="background-color: #faad14; color: white; border-color: #faad14;">
            <template #icon><PauseCircleOutlined /></template>
            暂停
          </a-button>

          <a-button v-if="isRunning || isPaused" danger size="large" class="ml-3" @click="handleStopCrawler">
            <template #icon><StopOutlined /></template>
            停止
          </a-button>

          <a-button v-if="selectedCategories.length > 0 && !isRunning && !isPaused" size="large" class="ml-3" @click="handleClearMemory" title="清除选择记忆">
            <template #icon><DeleteOutlined /></template>
            清除记忆
          </a-button>
        </a-col>
        <a-col :span="4">
          <a-typography-text type="secondary" style="line-height: 32px; display: block; float: right;">
            已选择 {{ selectedCategories.length }} 个分类
          </a-typography-text>
        </a-col>
      </a-row>
    </a-card>

    <a-card
        v-if="isRunning || isPaused || queueStatus.completed > 0 || queueStatus.pending > 0"
        class="panel-card mb-4"
        title="📊 爬取进度监控"
    >
      <a-row :gutter="16" class="mb-3">
        <a-col :span="6">
          <a-card size="small" class="queue-stat-card">
            <a-statistic
                title="待处理"
                :value="queueStatus.pending"
                prefix="📋"
                :value-style="{ color: '#1890ff' }"
            />
            <div v-if="queueStatus.subTaskCount > 0" class="text-xs text-orange-500 mt-1">
              含 {{ queueStatus.subTaskCount }} 个拆分子任务
            </div>
          </a-card>
        </a-col>
        <a-col :span="6">
          <a-card size="small" class="queue-stat-card">
            <a-statistic
                title="处理中"
                :value="queueStatus.processing"
                prefix="⚙️"
                :value-style="{ color: '#faad14' }"
            />
          </a-card>
        </a-col>
        <a-col :span="6">
          <a-card size="small" class="queue-stat-card">
            <a-statistic
                title="已完成"
                :value="queueStatus.completed"
                prefix="✅"
                :value-style="{ color: '#52c41a' }"
            />
          </a-card>
        </a-col>
        <a-col :span="6">
          <a-card size="small" class="queue-stat-card">
            <a-statistic
                title="失败"
                :value="queueStatus.failed"
                prefix="❌"
                :value-style="{ color: '#ff4d4f' }"
            />
          </a-card>
        </a-col>
      </a-row>

      <a-progress
          :percent="overallProgress"
          :status="overallProgress === 100 ? 'success' : (isPaused ? 'exception' : 'active')"
          :show-info="true"
      >
        <template #format="percent">
          {{ percent }}% ({{ queueStatus.completed }} / {{ queueStatus.total }})
          <span v-if="queueStatus.subTaskCount > 0" class="text-orange-500 ml-2">
            (含 {{ queueStatus.subTaskCount }} 个拆分子任务)
          </span>
        </template>
      </a-progress>

      <div v-if="isRunning || isPaused || queueStatus.totalImages > 0 || queueStatus.totalPdfs > 0" class="resource-progress-section mt-4">
        <div class="mb-3">
          <div style="display: flex; justify-content: space-between; margin-bottom: 4px;">
            <span style="font-weight: 500;">
              🖼️ 图片下载进度
              <span class="text-gray-500 text-xs ml-1">({{ queueStatus.downloadedImages }} / {{ queueStatus.totalImages }})</span>
            </span>
            <span v-if="imgProgress === 100 && !isRunning && queueStatus.totalImages > 0" style="color: #52c41a; font-weight: bold;">
              <CheckCircleOutlined /> 图片已全部下载
            </span>
            <span v-else-if="isRunning && queueStatus.totalImages > 0" class="text-xs" style="color: #1890ff;">
              <SyncOutlined spin /> 正在提取并下载图片...
            </span>
            <span v-else-if="isPaused && queueStatus.totalImages > 0 && imgProgress < 100" class="text-xs" style="color: #faad14;">
              <PauseCircleOutlined /> 下载已暂停
            </span>
            <span v-else-if="!isRunning && !isPaused && queueStatus.totalImages > 0 && imgProgress < 100" class="text-xs" style="color: #ff4d4f;">
              <StopOutlined /> 任务已终止
            </span>
          </div>
          <a-progress
              :percent="imgProgress"
              :status="imgProgress === 100 ? 'success' : (isPaused ? 'exception' : 'active')"
              :stroke-color="{ '0%': '#108ee9', '100%': '#87d068' }"
          />
          <div v-if="queueStatus.failedImages > 0" class="text-xs text-red-500">
            ⚠️ 有 {{ queueStatus.failedImages }} 张图片下载失败
          </div>
        </div>

        <div v-if="savePdfEnabled">
          <div style="display: flex; justify-content: space-between; margin-bottom: 4px;">
            <span style="font-weight: 500;">
              📄 PDF 文件下载进度
              <span class="text-gray-500 text-xs ml-1">({{ queueStatus.downloadedPdfs }} / {{ queueStatus.totalPdfs }})</span>
            </span>
            <span v-if="pdfProgress === 100 && !isRunning && queueStatus.totalPdfs > 0" style="color: #52c41a; font-weight: bold;">
              <CheckCircleOutlined /> PDF 已全部下载
            </span>
            <span v-else-if="isRunning && queueStatus.totalPdfs > 0" class="text-xs" style="color: #faad14;">
              <SyncOutlined spin /> 正在提取并下载说明书...
            </span>
            <span v-else-if="isPaused && queueStatus.totalPdfs > 0 && pdfProgress < 100" class="text-xs" style="color: #faad14;">
              <PauseCircleOutlined /> 下载已暂停
            </span>
            <span v-else-if="!isRunning && !isPaused && queueStatus.totalPdfs > 0 && pdfProgress < 100" class="text-xs" style="color: #ff4d4f;">
              <StopOutlined /> 任务已终止
            </span>
          </div>
          <a-progress
              :percent="pdfProgress"
              :status="pdfProgress === 100 ? 'success' : (isPaused ? 'exception' : 'active')"
              :stroke-color="{ '0%': '#faad14', '100%': '#52c41a' }"
          />
          <div v-if="queueStatus.failedPdfs > 0" class="text-xs text-red-500">
            ⚠️ 有 {{ queueStatus.failedPdfs }} 个 PDF 下载失败
          </div>
        </div>
      </div>

      <a-alert
          v-if="splitTasks.length > 0"
          type="info"
          class="mt-3"
          closable
          @close="splitTasks = []"
      >
        <template #message>
          <div><strong>🔀 智能任务拆分</strong></div>
        </template>
        <template #description>
          <div v-for="(task, index) in splitTasks.slice(0, 3)" :key="index" class="mb-2">
            <div><strong>{{ task.catalogName }}</strong> ({{ task.totalProducts }} 个产品)</div>
            <div class="text-secondary">
              已拆分为 {{ task.splitCount }} 个子任务
              <span v-if="task.splitDimension">({{ task.splitDimension }})</span>
            </div>
            <div v-if="task.splitUnits && task.splitUnits.length > 0" class="text-secondary small">
              包含: {{ task.splitUnits.map((u: any) => u.filterValue).join(', ') }}
              <span v-if="task.splitCount > task.splitUnits.length">等{{ task.splitCount }}个</span>
            </div>
          </div>
          <div v-if="splitTasks.length > 3" class="text-secondary">
            还有 {{ splitTasks.length - 3 }} 个分类被拆分...
          </div>
        </template>
      </a-alert>
    </a-card>

    <a-card class="panel-card mb-4" title="💾 存储路径信息">
      <a-row :gutter="16">
        <a-col :span="24" class="mb-3">
          <a-typography-text strong>基础路径:</a-typography-text>
          <a-typography-text code>{{ storageInfo.basePath }}</a-typography-text>
          <a-button
              size="small"
              class="ml-3"
              :loading="storageLoading"
              @click="loadStoragePaths"
          >
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </a-col>
      </a-row>

      <a-row :gutter="16">
        <a-col :span="6">
          <a-statistic
              title="图片文件"
              :value="storageInfo.paths?.images?.fileCount || 0"
              suffix="个"
              :value-style="{ color: '#1890ff' }"
          />
          <div class="mt-2">
            <a-tag v-if="storageInfo.paths?.images?.exists" color="success">存在</a-tag>
            <a-tag v-else color="error">不存在</a-tag>
            <span class="ml-2">{{ (storageInfo.paths?.images?.sizeMB || 0).toFixed(1) }} MB</span>
          </div>
        </a-col>

        <a-col :span="6">
          <a-statistic
              title="PDF文件"
              :value="storageInfo.paths?.pdfs?.fileCount || 0"
              suffix="个"
              :value-style="{ color: '#52c41a' }"
          />
          <div class="mt-2">
            <a-tag v-if="storageInfo.paths?.pdfs?.exists" color="success">存在</a-tag>
            <a-tag v-else color="error">不存在</a-tag>
            <span class="ml-2">{{ (storageInfo.paths?.pdfs?.sizeMB || 0).toFixed(1) }} MB</span>
          </div>
        </a-col>

        <a-col :span="6">
          <a-statistic
              title="数据目录"
              value=""
              :value-style="{ color: '#faad14' }"
          />
          <div class="mt-2">
            <a-tag v-if="storageInfo.paths?.data?.exists" color="success">存在</a-tag>
            <a-tag v-else color="error">不存在</a-tag>
            <span class="ml-2">{{ storageInfo.paths?.data?.relativePath }}</span>
          </div>
        </a-col>

        <a-col :span="6">
          <a-statistic
              title="导出目录"
              value=""
              :value-style="{ color: '#722ed1' }"
          />
          <div class="mt-2">
            <a-tag v-if="storageInfo.paths?.exports?.exists" color="success">存在</a-tag>
            <a-tag v-else color="error">不存在</a-tag>
            <span class="ml-2">{{ storageInfo.paths?.exports?.relativePath }}</span>
          </div>
        </a-col>
      </a-row>

      <a-row class="mt-3">
        <a-col :span="24">
          <a-tag v-if="storageInfo.saveImages" color="green">
            <CheckCircleOutlined /> 图片保存已启用
          </a-tag>
          <a-tag v-else color="warning">
            <CloseCircleOutlined /> 图片保存已禁用
          </a-tag>
        </a-col>
      </a-row>
    </a-card>

    <a-card
        v-if="systemStatus.categoriesSynced"
        class="panel-card mb-4"
        title="📦 爬取结果管理"
    >
      <a-row :gutter="16" class="mb-3">
        <a-col :span="12">
          <a-space>
            <a-button @click="loadCategoriesWithStatus" :loading="loadingResults">
              <template #icon><ReloadOutlined /></template>
              刷新数据
            </a-button>

            <a-dropdown v-if="selectedResultCategories.length > 0">
              <a-button type="primary">
                <template #icon><DownloadOutlined /></template>
                导出选中 ({{ selectedResultCategories.length }})
                <DownOutlined />
              </a-button>
              <template #overlay>
                <a-menu @click="handleBatchExport">
                  <a-menu-item key="excel">
                    <FileExcelOutlined />
                    导出为Excel
                  </a-menu-item>
                  <a-menu-item key="csv">
                    <FileTextOutlined />
                    导出为CSV
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </a-space>
        </a-col>

        <a-col :span="12">
          <a-input-search
              v-model:value="resultSearchKeyword"
              placeholder="搜索分类名称"
              allow-clear
              style="float: right;"
          />
        </a-col>
      </a-row>

      <a-table
          :dataSource="filteredCategoriesWithStatus"
          :loading="loadingResults"
          :pagination="resultPagination"
          :row-selection="{
          selectedRowKeys: selectedResultCategories,
          onChange: onResultSelectionChange
        }"
          :scroll="{ y: 400 }"
          row-key="id"
          size="middle"
      >
        <a-table-column key="categoryName" title="分类名称 (L3)" width="200">
          <template #default="{ record }">
            <span style="font-weight: 500" v-html="highlightText(record.categoryName, resultSearchKeyword)"></span>
          </template>
        </a-table-column>

        <a-table-column key="parentInfo" title="所属分类 (L1/L2)" width="200">
          <template #default="{ record }">
            <a-breadcrumb separator=">">
              <a-breadcrumb-item class="text-xs">
                <span v-html="highlightText(record.categoryLevel1Name, resultSearchKeyword)"></span>
              </a-breadcrumb-item>
              <a-breadcrumb-item class="text-xs">
                <span v-html="highlightText(record.categoryLevel2Name, resultSearchKeyword)"></span>
              </a-breadcrumb-item>
            </a-breadcrumb>
          </template>
        </a-table-column>

        <a-table-column key="crawlStatus" title="爬取状态" width="120">
          <template #default="{ record }">
            <a-tag v-if="record.crawlStatus === 'completed'" color="success">
              <CheckCircleOutlined /> 已完成
            </a-tag>
            <a-tag v-else-if="record.crawlStatus === 'processing'" color="processing">
              <SyncOutlined spin /> 爬取中
            </a-tag>
            <a-tag v-else-if="record.crawlStatus === 'failed'" color="error">
              <CloseCircleOutlined /> 失败
            </a-tag>
            <a-tag v-else color="default">
              <MinusCircleOutlined /> 未爬取
            </a-tag>
          </template>
        </a-table-column>

        <a-table-column key="crawledCount" title="已爬取数量" dataIndex="crawledCount" width="120" align="right">
          <template #default="{ record }">
            <a-statistic
                :value="record.crawledCount"
                :value-style="{
                  fontSize: '14px',
                  color: '#52c41a'
                }"
            />
          </template>
        </a-table-column>

        <a-table-column key="lastCrawlTime" title="最后爬取时间" width="180">
          <template #default="{ record }">
            <span v-if="record.lastCrawlTime">
              {{ formatDateTime(record.lastCrawlTime) }}
            </span>
            <span v-else style="color: #8c8c8c">-</span>
          </template>
        </a-table-column>

        <a-table-column key="actions" title="操作" width="150" fixed="right">
          <template #default="{ record }">
            <a-space>
              <a-button
                  type="link"
                  size="small"
                  @click="viewCategoryProducts(record)"
                  :disabled="record.totalProducts === 0"
              >
                查看产品
              </a-button>
              <a-button
                  type="link"
                  size="small"
                  @click="crawlSingleCategory(record.id)"
                  :disabled="isRunning"
              >
                重新爬取
              </a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-modal
        v-model:open="showCategorySelector"
        title="📂 选择要爬取的分类"
        width="900px"
        :ok-text="'确认选择'"
        :cancel-text="'取消'"
        @ok="handleCategorySelectorOk"
    >
      <a-alert
          message="提示"
          type="info"
          description="支持按一级/二级/三级分类树形选择。爬取任务将基于三级分类执行。"
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
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import {
  CloudSyncOutlined,
  ReloadOutlined,
  PlayCircleOutlined,
  StopOutlined,
  PauseCircleOutlined,
  AppstoreOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  FileTextOutlined,
  DownOutlined,
  CheckCircleOutlined,
  SyncOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  DeleteOutlined
} from '@ant-design/icons-vue'
import CategoryTreeSelector from '@/components/CategoryTreeSelector.vue'
import {
  getSystemStatus,
  syncCategories,
  startFullCrawl,
  startBatchCrawl,
  stopCrawler,
  getCategoriesWithStatus,
  getAllCategories,
  exportProductsByCategories,
  downloadExportFile,
  getStoragePaths
} from '@/api/product'

const router = useRouter()

const STORAGE_KEY = 'lcsc_crawler_selected_categories_l3'

const syncLoading = ref(false)
const loadingResults = ref(false)
const isRunning = ref(false)
const isPaused = ref(false)
const crawlMode = ref<'full' | 'custom'>('full')
const categoryTreeSelectorRef = ref<InstanceType<typeof CategoryTreeSelector>>()
const showCategorySelector = ref(false)
const resultSearchKeyword = ref('')

const systemStatus = ref({
  categoriesSynced: false,
  categoryStats: {
    level1Count: 0,
    level2Count: 0,
    level3Count: 0
  },
  totalProductsInDb: 0
})

const queueStatus = ref({
  pending: 0,
  processing: 0,
  completed: 0,
  failed: 0,
  total: 0,
  subTaskCount: 0,
  totalImages: 0,
  downloadedImages: 0,
  failedImages: 0,
  totalPdfs: 0,
  downloadedPdfs: 0,
  failedPdfs: 0
})

const resetLocalQueueStatus = () => {
  queueStatus.value = {
    pending: 0,
    processing: 0,
    completed: 0,
    failed: 0,
    total: 0,
    subTaskCount: 0,
    totalImages: 0,
    downloadedImages: 0,
    failedImages: 0,
    totalPdfs: 0,
    downloadedPdfs: 0,
    failedPdfs: 0
  }
}

const savePdfEnabled = ref(true)

const handlePdfToggle = async (checked: boolean | string | number) => {
  try {
    const response = await fetch(`/api/v3/crawler/config/pdf?enable=${checked}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    const res = await response.json();

    if (res.code === 200 || res.success) {
      message.success(res.data || `PDF 下载已${checked ? '开启' : '关闭'}`);
    } else {
      throw new Error(res.message || '设置失败');
    }
  } catch (error) {
    console.error('设置 PDF 开关失败:', error);
    message.error('设置 PDF 开关失败');
    savePdfEnabled.value = !checked;
  }
}

const overallProgress = computed(() => {
  if (queueStatus.value.total === 0) return 0
  return Math.round((queueStatus.value.completed / queueStatus.value.total) * 100)
})

const imgProgress = computed(() => {
  // 如果总数为 0，进度为 0
  if (!queueStatus.value.totalImages || queueStatus.value.totalImages === 0) return 0;

  // 已处理的总数 = 成功的 + 失败的
  const processedCount = queueStatus.value.downloadedImages + queueStatus.value.failedImages;

  // 计算真实处理进度百分比 (四舍五入到整数)
  const p = Math.round((processedCount / queueStatus.value.totalImages) * 100);

  // 防止计算误差导致超过 100
  return p > 100 ? 100 : p;
})

const pdfProgress = computed(() => {
  // 如果总数为 0，进度为 0
  if (!queueStatus.value.totalPdfs || queueStatus.value.totalPdfs === 0) return 0;

  // 已处理的总数 = 成功的 + 失败的
  const processedCount = queueStatus.value.downloadedPdfs + queueStatus.value.failedPdfs;

  // 计算真实处理进度百分比 (四舍五入到整数)
  const p = Math.round((processedCount / queueStatus.value.totalPdfs) * 100);

  // 防止计算误差导致超过 100
  return p > 100 ? 100 : p;
})

const splitTasks = ref<any[]>([])
const selectedCategories = ref<number[]>([])
const selectedResultCategories = ref<number[]>([])
const categoriesWithStatus = ref<any[]>([])
const allCategories = ref<any[]>([])

const storageLoading = ref(false)
const storageInfo = ref<any>({
  basePath: '',
  paths: {},
  saveImages: false
})

const resultPagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条数据`,
  onChange: (page: number, pageSize: number) => {
    resultPagination.current = page
    resultPagination.pageSize = pageSize
  }
})

const filteredCategoriesWithStatus = computed(() => {
  let filtered = categoriesWithStatus.value
  if (resultSearchKeyword.value) {
    const keyword = resultSearchKeyword.value.toLowerCase()
    filtered = filtered.filter(cat =>
        (cat.categoryName && cat.categoryName.toLowerCase().includes(keyword)) ||
        (cat.categoryLevel2Name && cat.categoryLevel2Name.toLowerCase().includes(keyword))
    )
  }
  resultPagination.total = filtered.length
  const start = (resultPagination.current - 1) * resultPagination.pageSize
  const end = start + resultPagination.pageSize
  return filtered.slice(start, end)
})

watch(resultSearchKeyword, () => {
  resultPagination.current = 1
})

const checkSystemStatus = async () => {
  try {
    const data = await getSystemStatus()
    if (data) {
      systemStatus.value = data
      isRunning.value = data.isRunning
      isPaused.value = data.isPaused || false
      queueStatus.value = data.queueStatus
      if (data.savePdfEnabled !== undefined) {
        savePdfEnabled.value = data.savePdfEnabled
      }
    }
  } catch (error) {
    console.error('检查系统状态失败:', error)
  }
}

const loadStoragePaths = async () => {
  storageLoading.value = true
  try {
    const res = await getStoragePaths()
    if (res && res.basePath) {
      storageInfo.value = res
    }
  } catch (error) {
    console.error('获取存储路径信息失败:', error)
  } finally {
    storageLoading.value = false
  }
}

const handleSyncCategories = async () => {
  syncLoading.value = true
  try {
    const res = await syncCategories()
    if (res && res.success) {
      message.success(`分类同步成功！`)
      await checkSystemStatus()
      await loadCategoriesWithStatus()
    } else {
      message.error(res?.message || '同步失败')
    }
  } catch (error) {
    console.error('同步分类失败:', error)
    message.error('同步失败，请重试')
  } finally {
    syncLoading.value = false
  }
}

const handleStartFullCrawl = async () => {
  try {
    resetLocalQueueStatus()
    const data = await startFullCrawl({ savePdf: savePdfEnabled.value, clearQueue: true })
    if (data && data.success) {
      message.success('全量爬取已启动！')
      isRunning.value = true
      isPaused.value = false
    } else {
      message.error(data?.message || '启动失败')
    }
  } catch (error) {
    console.error('启动全量爬取失败:', error)
    message.error('启动失败，请重试')
  }
}

// 已恢复，并直接使用原始带层级前缀的 ID 发送给后端
const handleStartBatchCrawl = async () => {
  if (selectedCategories.value.length === 0) {
    message.warning('请先选择要爬取的分类')
    return
  }

  try {
    resetLocalQueueStatus()

    const data = await startBatchCrawl(selectedCategories.value, { savePdf: savePdfEnabled.value, clearQueue: true })
    if (data && data.success) {
      message.success(`已创建爬取任务！`)
      isRunning.value = true
      isPaused.value = false
      showCategorySelector.value = false
    } else {
      message.error(data?.message || '启动失败')
    }
  } catch (error) {
    console.error('批量爬取失败:', error)
    message.error('启动失败，请重试')
  }
}

const handlePauseCrawler = async () => {
  try {
    const response = await fetch('/api/v3/crawler/pause', { method: 'POST' });
    const data = await response.json();
    if (data && data.success) {
      message.success(data.message || '爬虫已暂停');
      isRunning.value = false;
      isPaused.value = true;
      await checkSystemStatus();
    } else {
      message.error(data?.message || '暂停失败');
    }
  } catch (error) {
    console.error('暂停爬虫失败:', error);
    message.error('暂停失败，请检查网络');
  }
}

const handleClearCrawler = async () => {
  try {
    const response = await fetch('/api/v3/crawler/clear', { method: 'POST' });
    const data = await response.json();
    if (data && data.success) {
      message.success(data.message || '已清空爬虫状态');
      resetLocalQueueStatus();
      await checkSystemStatus();
    } else {
      message.error(data?.message || '清空失败');
    }
  } catch (error) {
    console.error('清空状态失败:', error);
    message.error('清空失败，请检查网络');
  }
}

const handleStopCrawler = async () => {
  try {
    const data = await stopCrawler()
    if (data && data.success) {
      message.success(data.message || '爬虫已停止')
      isRunning.value = false
      isPaused.value = false
      await checkSystemStatus()
    } else {
      message.error(data?.message || '停止失败')
    }
  } catch (error) {
    console.error('停止爬虫失败:', error)
  }
}

const handleResumeCrawler = async () => {
  try {
    const response = await fetch('/api/v3/crawler/resume', { method: 'POST' });
    const data = await response.json();

    if (data && data.success) {
      message.success(data.message || '爬虫已继续运行');
      isRunning.value = true;
      isPaused.value = false;
      await checkSystemStatus();
    } else {
      message.error(data?.message || '继续失败');
    }
  } catch (error) {
    console.error('继续爬虫失败:', error);
    message.error('继续失败，请检查网络');
  }
}

const loadCategoriesWithStatus = async () => {
  loadingResults.value = true
  try {
    const rawData = await getCategoriesWithStatus()

    if (rawData && Array.isArray(rawData)) {
      categoriesWithStatus.value = rawData.map((item: any) => ({
        id: item.id || item.categoryLevel3Id,
        categoryName: item.categoryName || item.categoryLevel3Name || item.name || '未知分类',
        categoryLevel1Name: item.categoryLevel1Name || item.level1Name || '-',
        categoryLevel2Name: item.categoryLevel2Name || item.level2Name || '-',
        crawlStatus: mapCrawlStatus(item.crawlStatus || item.status),
        totalProducts: item.totalProducts || item.count || 0,
        crawledCount: item.crawledCount || item.crawledProducts || item.savedCount || 0,
        lastCrawlTime: item.lastCrawlTime || item.updatedAt || item.createTime || null,
        raw: item
      }))
      resultPagination.current = 1
    } else {
      categoriesWithStatus.value = []
    }
  } catch (error) {
    console.error('加载分类状态失败:', error)
    message.error('获取数据失败，请检查网络或后端日志')
  } finally {
    loadingResults.value = false
  }
}

const mapCrawlStatus = (status: any) => {
  if (!status) return 'default'
  if (typeof status === 'number') {
    if (status === 1) return 'processing'
    if (status === 2) return 'completed'
    if (status === 3) return 'failed'
    return 'default'
  }
  const s = String(status).toLowerCase()
  if (s === 'success' || s === 'finished') return 'completed'
  if (s === 'running' || s === 'pending') return 'processing'
  if (s === 'error' || s === 'fail') return 'failed'
  return s
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

const handleOpenCategorySelector = async () => {
  await loadAllCategoriesForSelector()
  showCategorySelector.value = true
}

const handleBatchExport = async ({ key }: { key: string }) => {
  if (selectedResultCategories.value.length === 0) {
    message.warning('请先选择要导出的分类')
    return
  }
  const format = key === 'excel' ? 'excel' : 'csv'
  try {
    const result = await exportProductsByCategories(selectedResultCategories.value, format)
    message.success(`导出成功！共 ${result.recordCount} 条记录`)
    setTimeout(() => {
      downloadExportFile(result.filename)
    }, 500)
  } catch (error) {
    console.error('导出失败:', error)
    message.error('导出失败')
  }
}

const handleTreeSelectionChange = (selectedIds: number[]) => {
  selectedCategories.value = selectedIds
  localStorage.setItem(STORAGE_KEY, JSON.stringify(selectedIds))
}

const onResultSelectionChange = (selectedRowKeys: number[]) => {
  selectedResultCategories.value = selectedRowKeys
}

const handleCategorySelectorOk = () => {
  if (categoryTreeSelectorRef.value) {
    selectedCategories.value = categoryTreeSelectorRef.value.getSelectedIds()
  }
  showCategorySelector.value = false
}

const viewCategoryProducts = (record: any) => {
  router.push(`/products?categoryId=${record.id}`)
}

const crawlSingleCategory = async (categoryId: number) => {
  // 👇 核心修复 2：表格里的数据是三级分类，直接补上 20 亿的偏移量发给后端
  selectedCategories.value = [categoryId + 2000000000]
  await handleStartBatchCrawl()
}

const formatDateTime = (dateTime: string) => {
  if (!dateTime) return '-'
  return new Date(dateTime).toLocaleString('zh-CN')
}

const handleClearMemory = () => {
  localStorage.removeItem(STORAGE_KEY)
  selectedCategories.value = []
  message.success('已清除选择记忆')
}

const highlightText = (text: string, keyword: string) => {
  if (!text) return ''
  if (!keyword || keyword.trim() === '') return text
  const regex = new RegExp(`(${keyword})`, 'gi')
  return text.replace(regex, '<span style="color: #f50; font-weight: bold;">$1</span>')
}

let statusInterval: any = null

onMounted(() => {
  const savedSelection = localStorage.getItem(STORAGE_KEY)
  if (savedSelection) {
    try {
      const savedIds = JSON.parse(savedSelection)
      if (Array.isArray(savedIds)) {
        selectedCategories.value = savedIds
      }
    } catch (e) {
      localStorage.removeItem(STORAGE_KEY)
    }
  }

  checkSystemStatus()
  loadCategoriesWithStatus()
  loadStoragePaths()
  loadAllCategoriesForSelector()

  statusInterval = setInterval(() => {
    checkSystemStatus()
    if (isRunning.value || isPaused.value) {
      loadCategoriesWithStatus()
    }
  }, 5000)
})

onUnmounted(() => {
  if (statusInterval) clearInterval(statusInterval)
})
</script>

<style scoped>
.dashboard-v3 {
  padding: 24px;
}
.panel-card {
  margin-bottom: 16px;
}
.mb-3 {
  margin-bottom: 12px;
}
.mb-4 {
  margin-bottom: 16px;
}
.ml-3 {
  margin-left: 12px;
}
.mt-2 {
  margin-top: 8px;
}
.mt-3 {
  margin-top: 12px;
}
.text-xs {
  font-size: 12px;
}
.resource-progress-section {
  padding-top: 16px;
  border-top: 1px dashed #e8e8e8;
}
.text-gray-500 {
  color: #8c8c8c;
}
</style>