<template>
  <div class="product-management">
    <a-card class="mb-4 search-card">
      <a-form :model="searchForm" layout="horizontal" class="advanced-search-form">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12" :md="8" :lg="6">
            <a-form-item label="产品编号">
              <a-textarea
                  v-model:value="searchForm.productCode"
                  placeholder="多个编号请换行"
                  allow-clear
                  :auto-size="{ minRows: 1, maxRows: 1 }"
                  style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12" :md="8" :lg="5">
            <a-form-item label="品牌">
              <a-select
                  v-model:value="searchForm.brands"
                  mode="tags"
                  placeholder="选择或输入品牌(回车/粘贴换行)"
                  :max-tag-count="2"
                  :token-separators="[',']"
                  show-search
                  allow-clear
                  style="width: 100%"
                  @paste="handleBrandPaste"
              >
                <a-select-option v-for="item in brandOptions" :key="item" :value="item">{{ item }}</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12" :md="8" :lg="5">
            <a-form-item label="型号">
              <a-input
                  v-model:value="searchForm.model"
                  placeholder="输入型号"
                  allow-clear
                  style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12" :md="12" :lg="8">
            <a-form-item label="所属分类">
              <a-input-group compact style="display: flex; width: 100%">
                <a-button
                    @click="handleOpenCategorySelector"
                    style="flex: 1; text-align: left; overflow: hidden; text-overflow: ellipsis;"
                    :title="selectedCategories.length > 0 ? `已选 ${selectedCategories.length} 个分类` : '点击选择分类'"
                >
                  <template #icon><AppstoreOutlined /></template>
                  <span style="margin-left: 4px;">
                    {{ selectedCategories.length > 0 ? `已选 ${selectedCategories.length} 个` : '选择分类' }}
                  </span>
                </a-button>
                <a-button
                    v-if="selectedCategories.length > 0"
                    @click="clearSelectedCategories"
                    title="清除分类"
                >
                  <template #icon><DeleteOutlined style="color: #ff4d4f;"/></template>
                </a-button>
              </a-input-group>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16" type="flex" align="middle" class="second-row">
          <a-col :xs="24" :sm="24" :md="24" :lg="16">
            <div class="combined-condition-box">
              <span class="condition-label">联合过滤：</span>

              <a-select
                  v-model:value="searchForm.hasImage"
                  placeholder="图片状态"
                  allow-clear
                  style="width: 110px; margin-right: 12px;"
              >
                <a-select-option :value="true">有图</a-select-option>
                <a-select-option :value="false">无图</a-select-option>
              </a-select>

              <a-input-group compact class="compact-group">
                <a-input-number
                    v-model:value="searchForm.minStock"
                    class="stock-input"
                    placeholder="最小库存"
                    :min="0"
                />
                <a-input
                    class="stock-separator"
                    placeholder="~"
                    disabled
                />
                <a-input-number
                    v-model:value="searchForm.maxStock"
                    class="stock-input border-left-0"
                    placeholder="最大库存"
                    :min="0"
                />
              </a-input-group>

              <a-checkbox v-model:checked="searchForm.matchAny" class="match-any-checkbox">
                <a-tooltip title="勾选后，满足【图片状态】或【库存区间】其中一项即可查出">
                  <span class="highlight-text">任意满足</span>
                </a-tooltip>
              </a-checkbox>
            </div>
          </a-col>

          <a-col :xs="24" :sm="24" :md="24" :lg="8">
            <div class="search-actions">
              <a-space>
                <a-button type="primary" @click="handleSearch" :disabled="isExporting">
                  <template #icon><SearchOutlined /></template>
                  搜索
                </a-button>
                <a-button @click="handleReset" :disabled="isExporting">
                  <template #icon><ReloadOutlined /></template>
                  重置
                </a-button>

                <a-dropdown :disabled="isExporting">
                  <template #overlay>
                    <a-menu @click="handleExportMenuClick">
                      <a-menu-item key="excel-current">
                        <FileExcelOutlined />导出当前结果 (Excel)
                      </a-menu-item>
                      <a-menu-item key="excel-all">
                        <FileExcelOutlined />导出所有产品 (Excel)
                      </a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="csv-current">
                        <FileTextOutlined />导出当前结果 (CSV)
                      </a-menu-item>
                    </a-menu>
                  </template>
                  <a-button class="btn-success" :loading="isExporting">
                    <template #icon><DownloadOutlined /></template>
                    {{ isExporting ? '导出中...' : '导出' }} <DownOutlined style="font-size: 10px; margin-left: 4px;" />
                  </a-button>
                </a-dropdown>

                <a-button v-if="isExporting" danger @click="handleCancelExport">
                  停止导出
                </a-button>
              </a-space>
            </div>
          </a-col>
        </a-row>
      </a-form>
    </a-card>

    <a-card>
      <a-table
          :dataSource="tableData"
          :loading="loading && !isExporting"
          :pagination="false"
          bordered
          :scroll="{ x: 1400 }"
          row-key="id"
      >
        <a-table-column key="thumbnail" title="主图" width="80" fixed="left">
          <template #default="{ record }">
            <img
                v-if="record.productImageUrlBig"
                :src="record.productImageUrlBig"
                class="product-thumbnail"
                @click="handleImagePreview(record.productImageUrlBig)"
                @error="handleImageError"
                alt="产品主图"
            />
            <span v-else class="no-image">无图</span>
          </template>
        </a-table-column>
        <a-table-column key="productCode" data-index="productCode" title="产品编号" width="120" fixed="left" />
        <a-table-column key="brand" data-index="brand" title="品牌" width="100" />
        <a-table-column key="model" data-index="model" title="型号" width="130" :ellipsis="true" />
        <a-table-column key="packageName" data-index="packageName" title="封装" width="90" />
        <a-table-column key="categoryLevel1Name" data-index="categoryLevel1Name" title="一级分类" width="100">
          <template #default="{ record }">
            <a-tag v-if="record.categoryLevel1Name" color="blue" class="category-tag">
              {{ record.categoryLevel1Name }}
            </a-tag>
            <span v-else>-</span>
          </template>
        </a-table-column>
        <a-table-column key="categoryLevel2Name" data-index="categoryLevel2Name" title="二级分类" width="120">
          <template #default="{ record }">
            <a-tag v-if="record.categoryLevel2Name" color="cyan" class="category-tag">
              {{ record.categoryLevel2Name }}
            </a-tag>
            <span v-else>-</span>
          </template>
        </a-table-column>
        <a-table-column key="categoryLevel3Name" data-index="categoryLevel3Name" title="三级分类" width="120">
          <template #default="{ record }">
            <a-tag v-if="record.categoryLevel3Name" color="green" class="category-tag">
              {{ record.categoryLevel3Name }}
            </a-tag>
            <span v-else>-</span>
          </template>
        </a-table-column>
        <a-table-column key="totalStockQuantity" data-index="totalStockQuantity" title="库存" width="80" />
        <a-table-column key="ladderPrice1" title="阶梯价1" width="110">
          <template #default="{ record }">
            <a-tooltip v-if="record.ladderPrice1Quantity && record.ladderPrice1Price" placement="top">
              <template #title>
                <div class="price-tooltip">
                  <div v-if="record.ladderPrice1Quantity">阶梯1: {{ record.ladderPrice1Quantity }}+ = ￥{{ record.ladderPrice1Price }}</div>
                  <div v-if="record.ladderPrice2Quantity">阶梯2: {{ record.ladderPrice2Quantity }}+ = ￥{{ record.ladderPrice2Price }}</div>
                  <div v-if="record.ladderPrice3Quantity">阶梯3: {{ record.ladderPrice3Quantity }}+ = ￥{{ record.ladderPrice3Price }}</div>
                  <div v-if="record.ladderPrice4Quantity">阶梯4: {{ record.ladderPrice4Quantity }}+ = ￥{{ record.ladderPrice4Price }}</div>
                  <div v-if="record.ladderPrice5Quantity">阶梯5: {{ record.ladderPrice5Quantity }}+ = ￥{{ record.ladderPrice5Price }}</div>
                  <div v-if="record.ladderPrice6Quantity">阶梯6: {{ record.ladderPrice6Quantity }}+ = ￥{{ record.ladderPrice6Price }}</div>
                </div>
              </template>
              <span class="price-cell">{{ record.ladderPrice1Quantity }}+: ￥{{ record.ladderPrice1Price }}</span>
            </a-tooltip>
            <span v-else>-</span>
          </template>
        </a-table-column>
        <a-table-column key="lastCrawledAt" data-index="lastCrawledAt" title="最后爬取时间" width="160">
          <template #default="{ record }">
            {{ formatDateTime(record.lastCrawledAt || record.createdAt) }}
          </template>
        </a-table-column>
        <a-table-column key="action" title="操作" width="320" fixed="right">
          <template #default="{ record }">
            <a-space >
              <a-button size="small" @click="handleEdit(record)" :disabled="isExporting">
                <template #icon>
                  <EditOutlined />
                </template>
                编辑
              </a-button>
              <a-button size="small" danger @click="handleDelete(record)" :disabled="isExporting">
                <template #icon>
                  <DeleteOutlined />
                </template>
                删除
              </a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>

      <div class="mt-4 text-right">
        <a-pagination
            v-model:current="pagination.current"
            v-model:page-size="pagination.size"
            :page-size-options="['10', '20', '50', '100']"
            :total="pagination.total"
            show-size-changer
            show-quick-jumper
            show-total
            @change="handleCurrentChange"
            @show-size-change="handleSizeChange"
            :disabled="isExporting"
        >
          <template #buildOptionText="props">
            <span>{{ props.value }}条/页</span>
          </template>
        </a-pagination>
      </div>
    </a-card>

    <a-modal
        v-model:open="showCategorySelector"
        title="📂 选择要查询的分类"
        width="900px"
        :ok-text="'确认选择'"
        :cancel-text="'取消'"
        @ok="handleCategorySelectorOk"
    >
      <a-alert
          message="提示"
          type="info"
          description="支持按一级/二级/三级分类树形选择。确认后将按选中的分类进行查询。"
          show-icon
          class="mb-4"
      />

      <CategoryTreeSelector
          ref="categoryTreeSelectorRef"
          :categories="allCategories"
          :selected-category-ids="tempSelectedCategories"
          @update:selected="handleTreeSelectionChange"
      />
    </a-modal>
    <a-modal
        v-model:open="showAddDialog"
        :title="editingProduct.id ? '编辑产品' : '新增产品'"
        width="800px"
        @ok="handleSaveProduct"
        @cancel="resetEditingProduct"
    >
      <a-form
          ref="productFormRef"
          :model="editingProduct"
          :rules="productRules"
          :label-col="{ span: 6 }"
          :wrapper-col="{ span: 18 }"
      >
        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="产品编号" name="productCode">
              <a-input v-model:value="editingProduct.productCode" placeholder="如：C123456" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="品牌" name="brand">
              <a-input v-model:value="editingProduct.brand" placeholder="输入品牌名称" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="型号" name="model">
              <a-input v-model:value="editingProduct.model" placeholder="输入型号" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="封装" name="packageName">
              <a-input v-model:value="editingProduct.packageName" placeholder="输入封装名称" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="一级分类" name="categoryLevel1Id">
              <a-select
                  v-model:value="editingProduct.categoryLevel1Id"
                  placeholder="选择一级分类"
                  style="width: 100%"
                  @change="handleEditLevel1Change"
              >
                <a-select-option
                    v-for="item in level1Categories"
                    :key="item.id"
                    :label="item.categoryLevel1Name"
                    :value="item.id"
                >
                  {{ item.categoryLevel1Name }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="二级分类" name="categoryLevel2Id">
              <a-select
                  v-model:value="editingProduct.categoryLevel2Id"
                  placeholder="选择二级分类"
                  style="width: 100%"
                  :disabled="!editingProduct.categoryLevel1Id"
              >
                <a-select-option
                    v-for="item in editLevel2Categories"
                    :key="item.id"
                    :label="item.categoryLevel2Name"
                    :value="item.id"
                >
                  {{ item.categoryLevel2Name }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="20">
          <a-col :span="12">
            <a-form-item label="库存数量">
              <a-input-number
                  v-model:value="editingProduct.totalStockQuantity"
                  :min="0"
                  style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="图片名称">
              <a-input v-model:value="editingProduct.imageName" placeholder="如：C123456_001.jpg" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="简介" :label-col="{ span: 3 }" :wrapper-col="{ span: 21 }">
          <a-textarea
              v-model:value="editingProduct.briefDescription"
              :rows="3"
              placeholder="产品简介，最多200字"
              :maxlength="200"
              show-count
          />
        </a-form-item>

        <a-divider orientation="left">阶梯价格</a-divider>
        <div class="ladder-price-section">
          <a-row :gutter="20" class="ladder-price-row">
            <a-col :span="12">
              <a-form-item label="阶梯1 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice1Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯1 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice1Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="20" class="ladder-price-row">
            <a-col :span="12">
              <a-form-item label="阶梯2 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice2Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯2 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice2Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="20" class="ladder-price-row">
            <a-col :span="12">
              <a-form-item label="阶梯3 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice3Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯3 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice3Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="20" class="ladder-price-row">
            <a-col :span="12">
              <a-form-item label="阶梯4 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice4Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯4 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice4Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="20" class="ladder-price-row">
            <a-col :span="12">
              <a-form-item label="阶梯5 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice5Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯5 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice5Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="阶梯6 数量" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice6Quantity"
                    :min="0"
                    style="width: 100%"
                    placeholder="起订量"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="阶梯6 价格" :label-col="{ span: 8 }" :wrapper-col="{ span: 16 }">
                <a-input-number
                    v-model:value="editingProduct.ladderPrice6Price"
                    :min="0"
                    :precision="4"
                    style="width: 100%"
                    placeholder="单价 (USD)"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </div>
      </a-form>
    </a-modal>

    <a-modal
        v-model:open="showCrawlerDialog"
        title="爬取产品"
        width="500px"
        @ok="handleCrawlerSubmit"
    >
      <a-form>
        <a-form-item label="产品编号">
          <a-input
              v-model:value="crawlerForm.productCode"
              placeholder="输入产品编号，如：C123456"
          />
        </a-form-item>
        <a-form-item label="批量爬取">
          <a-textarea
              v-model:value="crawlerForm.batchCodes"
              :rows="4"
              placeholder="多个产品编号用换行分隔"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
        v-model:open="showImagePreview"
        title="产品主图"
        width="800px"
        :footer="null"
    >
      <div class="image-preview-container">
        <img :src="previewImageUrl" style="width: 100%; max-height: 600px; object-fit: contain;" @error="handleImageError" />
      </div>
    </a-modal>

    <a-modal
        v-model:open="showResourcesDialog"
        :title="`产品资源 - ${currentProductCode}`"
        width="1000px"
        :footer="null"
    >
      <div v-if="productResources.total === 0" class="empty-resources">
        <a-empty description="暂无资源文件" />
      </div>
      <div v-else class="resources-gallery">
        <a-tabs v-model:activeKey="activeResourceTab" type="card">
          <a-tab-pane key="all" :tab="`全部 (${productResources.total})`">
            <div class="resource-grid">
              <div
                  v-for="(resource, index) in productResources.all"
                  :key="index"
                  class="resource-item"
                  :class="{'pdf-item': resource.category === 'pdf'}"
              >
                <div class="resource-info">
                  <div class="resource-name">{{ resource.filename }}</div>
                  <div class="resource-meta">
                    <span class="resource-category" :class="resource.category">
                      {{ resource.category === 'image' ? '图片' : 'PDF' }}
                    </span>
                    <span class="resource-type">{{ resource.type }}</span>
                    <span class="resource-size">{{ formatFileSize(resource.size) }}</span>
                  </div>
                </div>
                <div class="resource-preview" @click="handleResourcePreview(resource)">
                  <div v-if="resource.category === 'image'" class="image-preview">
                    <img
                        :src="`http://localhost:8080/api${resource.url}`"
                        :alt="resource.filename"
                        @error="handleImageError"
                    />
                  </div>
                  <div v-else class="pdf-preview">
                    <FileOutlined style="font-size: 48px; color: #ff4d4f;" />
                    <div class="pdf-label">PDF文档</div>
                    <a-button type="link" size="small" @click.stop="openPdfInNewTab(resource)">
                      <template #icon><EyeOutlined /></template>
                      预览
                    </a-button>
                  </div>
                </div>
              </div>
            </div>
          </a-tab-pane>
          <a-tab-pane key="images" :tab="`图片 (${productResources.images.length})`">
            <div class="resource-grid">
              <div
                  v-for="(image, index) in productResources.images"
                  :key="index"
                  class="resource-item"
              >
                <div class="resource-info">
                  <div class="resource-name">{{ image.filename }}</div>
                  <div class="resource-meta">
                    <span class="resource-category image">图片</span>
                    <span class="resource-type">{{ image.type }}</span>
                    <span class="resource-size">{{ formatFileSize(image.size) }}</span>
                  </div>
                </div>
                <div class="resource-preview" @click="handleResourcePreview(image)">
                  <img
                      :src="`http://localhost:8080/api${image.url}`"
                      :alt="image.filename"
                      @error="handleImageError"
                  />
                </div>
              </div>
            </div>
          </a-tab-pane>
          <a-tab-pane key="pdfs" :tab="`PDF (${productResources.pdfs.length})`">
            <div class="resource-grid">
              <div
                  v-for="(pdf, index) in productResources.pdfs"
                  :key="index"
                  class="resource-item pdf-item"
              >
                <div class="resource-info">
                  <div class="resource-name">{{ pdf.filename }}</div>
                  <div class="resource-meta">
                    <span class="resource-category pdf">PDF</span>
                    <span class="resource-type">{{ pdf.type }}</span>
                    <span class="resource-size">{{ formatFileSize(pdf.size) }}</span>
                  </div>
                </div>
                <div class="resource-preview" @click="openPdfInNewTab(pdf)">
                  <FileOutlined style="font-size: 48px; color: #ff4d4f;" />
                  <div class="pdf-label">PDF文档</div>
                  <a-button type="link" size="small">
                    <template #icon><EyeOutlined /></template>
                    预览
                  </a-button>
                </div>
              </div>
            </div>
          </a-tab-pane>
        </a-tabs>
      </div>
    </a-modal>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import {
  SearchOutlined,
  ReloadOutlined,
  EditOutlined,
  DeleteOutlined,
  FileOutlined,
  EyeOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  FileTextOutlined,
  DownOutlined,
  AppstoreOutlined
} from '@ant-design/icons-vue'
import * as XLSX from 'xlsx'

import CategoryTreeSelector from '@/components/CategoryTreeSelector.vue'
import { getAllCategories, getAllBrands } from '@/api/product'

import {
  getProductPage,
  deleteProduct,
  crawlProduct,
  crawlProductBatch,
  addProduct,
  updateProduct,
  getProductStatistics,
  getProductResources
} from '@/api/product'
import { getCategoryLevel1List, getCategoryLevel2ListByLevel1Id } from '@/api/category'
import type { Product, CategoryLevel1Code, CategoryLevel2Code, ProductResources, ResourceFile } from '@/types'

const route = useRoute()

// 🌟 核心状态控制：增加导出中和取消导出的标志
const isExporting = ref(false);
const cancelExportFlag = ref(false);

const handleCancelExport = () => {
  cancelExportFlag.value = true;
};

// ================= 新增：智能解析选中的分类层级 =================
const parseSelectedCategories = () => {
  const result = {
    categoryLevel1Id: [] as number[],
    categoryLevel2Id: [] as number[],
    categoryLevel3Id: [] as number[]
  }

  if (!selectedCategories.value || selectedCategories.value.length === 0) return result;

  selectedCategories.value.forEach(id => {
    if (id > 2000000000) {
      result.categoryLevel3Id.push(id - 2000000000);
    } else if (id > 1000000000) {
      result.categoryLevel2Id.push(id - 1000000000);
    } else {
      result.categoryLevel1Id.push(id);
    }
  });

  result.categoryLevel1Id = Array.from(new Set(result.categoryLevel1Id));
  result.categoryLevel2Id = Array.from(new Set(result.categoryLevel2Id));
  result.categoryLevel3Id = Array.from(new Set(result.categoryLevel3Id));

  return result;
}

// 响应式数据
const loading = ref(false)
const tableData = ref<Product[]>([])
const showCrawlerDialog = ref(false)
const showAddDialog = ref(false)
const showResourcesDialog = ref(false)
const showImagePreview = ref(false)
const previewImageUrl = ref('')
const productFormRef = ref()
const currentProductCode = ref('')
const productResources = ref<ProductResources>({
  all: [],
  images: [],
  pdfs: [],
  total: 0
})
const activeResourceTab = ref('all')

const showCategorySelector = ref(false)
const categoryTreeSelectorRef = ref<InstanceType<typeof CategoryTreeSelector>>()
const allCategories = ref<any[]>([])
const selectedCategories = ref<number[]>([])
const tempSelectedCategories = ref<number[]>([])

const level1Categories = ref<CategoryLevel1Code[]>([])
const editLevel2Categories = ref<CategoryLevel2Code[]>([])

const brandOptions = ref<string[]>([])

const searchForm = reactive({
  productCode: '',
  brands: [] as string[],
  model: '',
  categoryLevel1Id: undefined as number | undefined,
  categoryLevel2Id: undefined as number | undefined,
  categoryLevel3Id: undefined as number | undefined,
  hasImage: undefined as boolean | undefined,
  minStock: undefined as number | undefined,
  maxStock: undefined as number | undefined,
  matchAny: false
})

const pagination = reactive({
  current: 1,
  size: 20,
  total: 0
})

const crawlerForm = reactive({
  productCode: '',
  batchCodes: ''
})

const editingProduct = reactive<Product>({
  productCode: '',
  categoryLevel1Id: 0,
  categoryLevel2Id: 0,
  brand: '',
  model: '',
  packageName: '',
  totalStockQuantity: 0,
  briefDescription: '',
  tierPrices: '',
  ladderPrice1Quantity: undefined,
  ladderPrice1Price: undefined,
  ladderPrice2Quantity: undefined,
  ladderPrice2Price: undefined,
  ladderPrice3Quantity: undefined,
  ladderPrice3Price: undefined,
  ladderPrice4Quantity: undefined,
  ladderPrice4Price: undefined,
  ladderPrice5Quantity: undefined,
  ladderPrice5Price: undefined,
  ladderPrice6Quantity: undefined,
  ladderPrice6Price: undefined
})

const productRules = {
  productCode: [{ required: true, message: '请输入产品编号', trigger: 'blur' }],
  categoryLevel1Id: [{ required: true, message: '请选择一级分类', trigger: 'change' }],
  categoryLevel2Id: [{ required: true, message: '请选择二级分类', trigger: 'change' }]
}

const buildBaseQueryParams = (isBatchMode: boolean, productCodeParam: string) => {
  const parsedCategoryParams = parseSelectedCategories()
  const formatParam = (arr: number[], formVal: number | undefined) => {
    if (arr && arr.length > 0) return arr.join(',')
    return formVal ? String(formVal) : undefined
  }
  return {
    current: isBatchMode ? 1 : pagination.current,
    size: pagination.size,
    productCode: productCodeParam,
    brand: searchForm.brands.length > 0 ? searchForm.brands.join(',') : undefined,
    model: searchForm.model,
    categoryLevel1Id: formatParam(parsedCategoryParams.categoryLevel1Id, searchForm.categoryLevel1Id),
    categoryLevel2Id: formatParam(parsedCategoryParams.categoryLevel2Id, searchForm.categoryLevel2Id),
    categoryLevel3Id: formatParam(parsedCategoryParams.categoryLevel3Id, searchForm.categoryLevel3Id),
    hasImage: searchForm.hasImage,
    minStock: searchForm.minStock,
    maxStock: searchForm.maxStock,
    matchAny: searchForm.matchAny
  }
}

const fetchData = async () => {
  loading.value = true
  try {
    let productCodeParam = searchForm.productCode
    let isBatchMode = false
    let allCodes: string[] = []

    if (searchForm.productCode) {
      allCodes = Array.from(new Set(searchForm.productCode.split(/[\n\r,\s]+/).map(c => c.trim()).filter(Boolean)))
      if (allCodes.length > pagination.size) {
        isBatchMode = true
        const start = (pagination.current - 1) * pagination.size
        productCodeParam = allCodes.slice(start, start + pagination.size).join('\n')
        pagination.total = allCodes.length
      }
    }

    const params = buildBaseQueryParams(isBatchMode, productCodeParam)
    const result = await getProductPage(params)
    tableData.value = result.records
    if (!isBatchMode) pagination.total = result.total
  } catch (error) {
    console.error('获取产品数据失败:', error)
    message.error('获取产品数据失败')
  } finally {
    loading.value = false
  }
}

const handleExportMenuClick = async ({ key }: { key: string }) => {
  if (isExporting.value || loading.value) return;
  let exportType: 'xlsx' | 'csv' = key.includes('excel') ? 'xlsx' : 'csv';
  let isExportAll = key === 'excel-all';
  startFrontendStreamExport(exportType, isExportAll);
}

const startFrontendStreamExport = async (format: 'xlsx' | 'csv', isAll: boolean) => {
  loading.value = true;
  isExporting.value = true;
  cancelExportFlag.value = false;

  const msgKey = 'export-progress-modal';
  message.loading({ content: `正在准备导出 ${format.toUpperCase()} 任务...`, key: msgKey, duration: 0 });

  let worksheet: XLSX.WorkSheet | null = null;
  let csvRows: string[] = [];
  let totalExportedCount = 0;
  let isFirstChunk = true;

  try {
    const parsedCategoryParams = parseSelectedCategories();
    const formatParam = (arr: number[], formVal: number | undefined) => {
      if (arr && arr.length > 0) return arr.join(',');
      return formVal ? String(formVal) : undefined;
    };

    let allCodes: string[] = [];
    if (!isAll && searchForm.productCode) {
      allCodes = Array.from(new Set(searchForm.productCode.split(/[\n\r,\s]+/).map(c => c.trim()).filter(Boolean)));
    }
    const isBatchCodeMode = allCodes.length > 0;

    const exportBatchSize = 2000; // 安全分批大小
    let currentPage = 1;
    let hasMoreData = true;
    let totalRecords = isBatchCodeMode ? allCodes.length : 0;
    let currentCodeIndex = 0;

    while (hasMoreData) {
      if (cancelExportFlag.value) {
        message.warning({ content: '已手动中止导出任务！即将为您打包已获取的数据...', key: msgKey, duration: 3 });
        break;
      }

      let params: any = {};
      if (isBatchCodeMode) {
        const chunkCodes = allCodes.slice(currentCodeIndex, currentCodeIndex + exportBatchSize).join('\n');
        params = {
          current: 1,
          size: exportBatchSize,
          productCode: chunkCodes,
          brand: searchForm.brands.length > 0 ? searchForm.brands.join(',') : undefined,
          model: searchForm.model,
          categoryLevel1Id: formatParam(parsedCategoryParams.categoryLevel1Id, searchForm.categoryLevel1Id),
          categoryLevel2Id: formatParam(parsedCategoryParams.categoryLevel2Id, searchForm.categoryLevel2Id),
          categoryLevel3Id: formatParam(parsedCategoryParams.categoryLevel3Id, searchForm.categoryLevel3Id),
          hasImage: searchForm.hasImage,
          minStock: searchForm.minStock,
          maxStock: searchForm.maxStock,
          matchAny: searchForm.matchAny
        };
      } else {
        params = {
          current: currentPage,
          size: exportBatchSize,
          productCode: isAll ? undefined : searchForm.productCode,
          brand: isAll ? undefined : (searchForm.brands.length > 0 ? searchForm.brands.join(',') : undefined),
          model: isAll ? undefined : searchForm.model,
          categoryLevel1Id: isAll ? undefined : formatParam(parsedCategoryParams.categoryLevel1Id, searchForm.categoryLevel1Id),
          categoryLevel2Id: isAll ? undefined : formatParam(parsedCategoryParams.categoryLevel2Id, searchForm.categoryLevel2Id),
          categoryLevel3Id: isAll ? undefined : formatParam(parsedCategoryParams.categoryLevel3Id, searchForm.categoryLevel3Id),
          hasImage: isAll ? undefined : searchForm.hasImage,
          minStock: isAll ? undefined : searchForm.minStock,
          maxStock: isAll ? undefined : searchForm.maxStock,
          matchAny: isAll ? false : searchForm.matchAny
        };
      }

      const result = await getProductPage(params);
      const records = result.records || [];
      if (!isBatchCodeMode) totalRecords = result.total || 0;

      if (records.length === 0) {
        hasMoreData = false;
        break;
      }

      const formattedRecords = records.map((p: any) => formatProductForExport(p));

      if (format === 'csv') {
        const headers = Object.keys(formattedRecords[0]);
        if (isFirstChunk) {
          csvRows.push('\ufeff' + headers.join(','));
        }
        formattedRecords.forEach(row => {
          csvRows.push(headers.map(header => {
            let cell = row[header] === null || row[header] === undefined ? "" : String(row[header]);
            if (cell.includes(",") || cell.includes('"') || cell.includes("\n") || cell.includes("\r")) {
              cell = `"${cell.replace(/"/g, '""')}"`;
            }
            return cell;
          }).join(','));
        });
      } else {
        if (isFirstChunk) {
          worksheet = XLSX.utils.json_to_sheet(formattedRecords);
        } else {
          XLSX.utils.sheet_add_json(worksheet, formattedRecords, { skipHeader: true, origin: -1 });
        }
      }

      totalExportedCount += formattedRecords.length;
      isFirstChunk = false;

      const percent = totalRecords > 0 ? Math.floor(((isBatchCodeMode ? currentCodeIndex + exportBatchSize : totalExportedCount) / totalRecords) * 100) : 100;
      message.loading({
        content: `正在打包数据... 进度: ${percent > 100 ? 100 : percent}% (已处理 ${totalExportedCount} 条)`,
        key: msgKey,
        duration: 0
      });

      if (isBatchCodeMode) {
        currentCodeIndex += exportBatchSize;
        if (currentCodeIndex >= allCodes.length) hasMoreData = false;
      } else {
        if (totalExportedCount >= totalRecords || records.length < exportBatchSize) {
          hasMoreData = false;
        } else {
          currentPage++;
        }
      }
    }

    if (totalExportedCount === 0) {
      message.warning({ content: '未查询到数据', key: msgKey, duration: 3 });
      loading.value = false;
      isExporting.value = false;
      return;
    }

    message.loading({ content: '正在生成最终文件，请稍候...', key: msgKey, duration: 0 });

    setTimeout(() => {
      const timestamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
      const filename = `产品导出_${timestamp}.${format}`;

      if (format === 'csv') {
        const blob = new Blob([csvRows.join('\n')], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = filename;
        link.click();
        URL.revokeObjectURL(url);
      } else {
        const workbook = XLSX.utils.book_new();
        XLSX.utils.book_append_sheet(workbook, worksheet, "产品数据");
        XLSX.writeFile(workbook, filename);
      }

      message.success({ content: `成功导出 ${totalExportedCount} 条数据`, key: msgKey, duration: 3 });
      loading.value = false;
      isExporting.value = false;
    }, 300);

  } catch (error) {
    console.error("导出出错:", error);
    message.error({ content: "导出失败，请检查网络或后端状态", key: msgKey, duration: 5 });
    loading.value = false;
    isExporting.value = false;
  }
};

const formatProductForExport = (p: any) => {
  const intro = `${p.model || ''} ${p.packageName || ''} ${p.categoryLevel3CustomName || ''} ${p.categoryLevel2CustomName || ''} ${p.categoryLevel1CustomName || ''}`.trim().replace(/\s+/g, ' ');

  let imgUrl = p.productImageUrlBig || p.shopNoImageUrl || 'https://assets.lcsc.com/images/no-image.jpg';

  return {
    "产品编号": p.productCode || '',
    "型号": p.model || '',
    "品牌": p.brand || '',
    "封装": p.packageName || '',
    "简介": intro,
    "库存数量": p.totalStockQuantity || 0,
    "一级分类名称": p.categoryLevel1Name || '',
    "二级分类名称": p.categoryLevel2Name || '',
    "三级分类名称": p.categoryLevel3Name || '',
    "图片名称": p.imageName || '',
    "主图URL": `:1:0|${imgUrl}`,
    "PDF URL": p.pdfUrl || '',
    "阶梯价1_数量": p.ladderPrice1Quantity || '',
    "阶梯价1_价格": p.ladderPrice1Price || '',
    "阶梯价2_数量": p.ladderPrice2Quantity || '',
    "阶梯价2_价格": p.ladderPrice2Price || '',
    "阶梯价3_数量": p.ladderPrice3Quantity || '',
    "阶梯价3_价格": p.ladderPrice3Price || '',
    "阶梯价4_数量": p.ladderPrice4Quantity || '',
    "阶梯价4_价格": p.ladderPrice4Price || '',
    "阶梯价5_数量": p.ladderPrice5Quantity || '',
    "阶梯价5_价格": p.ladderPrice5Price || '',
    "阶梯价6_数量": p.ladderPrice6Quantity || '',
    "阶梯价6_价格": p.ladderPrice6Price || '',
    "宝贝描述": formatBabyDescriptionHtml(p.parametersText),
    "额外参数": p.parametersText || ''
  }
}

const formatBabyDescriptionHtml = (parametersText: string) => {
  if (!parametersText) return "";
  let html = `<span style="color:#E53333;"><h1>主要参数: <br />`;
  const params = parametersText.split(/\s+/);
  for (const param of params) {
    if (param.includes(":")) {
      const kv = param.split(":");
      if (kv.length >= 2) {
        html += `${kv[0].trim()} : ${kv[1].trim()}<br />`;
      }
    }
  }
  html += `</h1></span>`;
  return html;
}

const loadAllCategoriesForSelector = async () => {
  try {
    const data = await getAllCategories()
    if (data && data.length > 0) {
      let mappedData = data.map((item: any) => {
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
        let offsetId = trueId;
        if (item.categoryLevel === 'level3') {
          offsetId = trueId + 2000000000;
        } else if (item.categoryLevel === 'level2' || item.isPureLevel2) {
          offsetId = trueId + 1000000000;
        }
        return {
          id: offsetId,
          rawId: trueId,
          name: item.categoryName || item.categoryLevel3Name || item.categoryLevel2Name,
          level2Id: item.categoryLevel2Id ? item.categoryLevel2Id + 1000000000 : null,
          level2Name: item.categoryLevel2Name,
          level1Id: item.categoryLevel1Id,
          level1Name: item.categoryLevel1Name || `L1-${item.categoryLevel1Id}`,
          totalProducts: item.totalProducts || item.crawledProducts || item.crawled_products || item.crawledCount || item.savedCount || 0,
          isPureLevel2: item.categoryLevel === 'level2'
        };
      })
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
  if (allCategories.value.length === 0) {
    await loadAllCategoriesForSelector()
  }
  tempSelectedCategories.value = [...selectedCategories.value]
  showCategorySelector.value = true
}

const handleTreeSelectionChange = (selectedIds: number[]) => {
  tempSelectedCategories.value = selectedIds
}

const handleCategorySelectorOk = () => {
  if (categoryTreeSelectorRef.value) {
    selectedCategories.value = categoryTreeSelectorRef.value.getSelectedIds()
  } else {
    selectedCategories.value = [...tempSelectedCategories.value]
  }
  showCategorySelector.value = false
}

const clearSelectedCategories = () => {
  selectedCategories.value = []
  tempSelectedCategories.value = []
}

const loadCategories = async () => {
  try {
    level1Categories.value = await getCategoryLevel1List()
  } catch (error) {
    console.error('获取分类数据失败:', error)
  }
}

const handleEditLevel1Change = async (categoryLevel1Id: number | undefined) => {
  editingProduct.categoryLevel2Id = 0
  editLevel2Categories.value = []
  if (categoryLevel1Id) {
    try {
      editLevel2Categories.value = await getCategoryLevel2ListByLevel1Id(categoryLevel1Id)
    } catch (error) {
      console.error('获取二级分类失败:', error)
    }
  }
}

const handleBrandPaste = (e: ClipboardEvent) => {
  e.preventDefault()
  const text = e.clipboardData?.getData('text')
  if (text) {
    const pastedBrands = text.split(/[\n\r,\t]+/).map(b => b.trim()).filter(Boolean)
    if (pastedBrands.length > 0) {
      searchForm.brands = Array.from(new Set([...(searchForm.brands || []), ...pastedBrands]))
    }
  }
}

const handleSearch = () => {
  pagination.current = 1
  fetchData()
}

const handleReset = () => {
  searchForm.productCode = ''
  searchForm.brands = []
  searchForm.model = ''
  searchForm.categoryLevel1Id = undefined
  searchForm.categoryLevel2Id = undefined
  searchForm.categoryLevel3Id = undefined
  searchForm.hasImage = undefined
  searchForm.minStock = undefined
  searchForm.maxStock = undefined
  searchForm.matchAny = false
  clearSelectedCategories()
  pagination.current = 1
  fetchData()
}

const handleEdit = async (row: Product) => {
  Object.assign(editingProduct, row)
  if (row.categoryLevel1Id) {
    try {
      editLevel2Categories.value = await getCategoryLevel2ListByLevel1Id(row.categoryLevel1Id)
    } catch (error) {
      console.error('获取二级分类失败:', error)
    }
  }
  showAddDialog.value = true
}

const handleSaveProduct = async () => {
  if (!productFormRef.value) return
  try {
    await productFormRef.value.validate()
    if (editingProduct.id) {
      await updateProduct(editingProduct.id, editingProduct)
      message.success('更新产品成功')
    } else {
      await addProduct(editingProduct)
      message.success('新增产品成功')
    }
    showAddDialog.value = false
    resetEditingProduct()
    fetchData()
  } catch (error) {
    console.error('保存产品失败:', error)
  }
}

const resetEditingProduct = () => {
  Object.assign(editingProduct, {
    id: undefined,
    productCode: '',
    categoryLevel1Id: 0,
    categoryLevel2Id: 0,
    brand: '',
    model: '',
    packageName: '',
    totalStockQuantity: 0,
    briefDescription: '',
    tierPrices: '',
    ladderPrice1Quantity: undefined,
    ladderPrice1Price: undefined,
    ladderPrice2Quantity: undefined,
    ladderPrice2Price: undefined,
    ladderPrice3Quantity: undefined,
    ladderPrice3Price: undefined,
    ladderPrice4Quantity: undefined,
    ladderPrice4Price: undefined,
    ladderPrice5Quantity: undefined,
    ladderPrice5Price: undefined,
    ladderPrice6Quantity: undefined,
    ladderPrice6Price: undefined
  })
  editLevel2Categories.value = []
}

const showStatistics = async () => {
  try {
    const stats = await getProductStatistics()
    Modal.info({
      title: '产品统计信息',
      content: `总产品数：${stats.totalProducts}\n有库存产品：${stats.productsWithStock}\n无库存产品：${stats.productsWithoutStock}`,
      okText: '确定'
    })
  } catch (error) {
    message.error('获取统计信息失败')
  }
}

const handleCrawl = async (productCode: string) => {
  try {
    await crawlProduct(productCode)
    message.success('开始爬取产品信息')
    setTimeout(() => { fetchData() }, 5000)
  } catch (error) {
    message.error('爬取失败')
  }
}

const handleViewResources = async (productCode: string) => {
  try {
    currentProductCode.value = productCode
    const resources = await getProductResources(productCode)
    productResources.value = resources || { all: [], images: [], pdfs: [], total: 0 }
    activeResourceTab.value = 'all'
    showResourcesDialog.value = true
  } catch (error) {
    message.error('获取资源列表失败')
  }
}

const handleResourcePreview = (resource: ResourceFile) => {
  if (resource.category === 'image') {
    const imageUrl = `http://localhost:8080/api${resource.url}`
    window.open(imageUrl, '_blank')
  } else {
    openPdfInNewTab(resource)
  }
}

const openPdfInNewTab = (resource: ResourceFile) => {
  const pdfUrl = `http://localhost:8080/api${resource.url}`
  window.open(pdfUrl, '_blank')
}

const handleDelete = async (row: Product) => {
  Modal.confirm({
    title: '确定删除此产品吗？',
    content: '删除后无法恢复',
    okText: '确定',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteProduct(row.id!)
        message.success('删除成功')
        fetchData()
      } catch (error) {
        message.error('删除失败')
      }
    }
  })
}

const handleCrawlerSubmit = async () => {
  try {
    if (crawlerForm.productCode) {
      await crawlProduct(crawlerForm.productCode)
      message.success('开始爬取单个产品')
    }
    if (crawlerForm.batchCodes) {
      const codes = crawlerForm.batchCodes.split('\n').filter(code => code.trim())
      await crawlProductBatch(codes)
      message.success(`开始批量爬取 ${codes.length} 个产品`)
    }
    showCrawlerDialog.value = false
    crawlerForm.productCode = ''
    crawlerForm.batchCodes = ''
    setTimeout(() => { fetchData() }, 5000)
  } catch (error) {
    message.error('爬取失败')
  }
}

const handleSizeChange = (current: number, size: number) => {
  pagination.size = size
  fetchData()
}

const handleCurrentChange = (current: number) => {
  pagination.current = current
  fetchData()
}

const formatDateTime = (dateTime: string | undefined) => {
  if (!dateTime) return '-'
  return new Date(dateTime).toLocaleString('zh-CN')
}

const formatFileSize = (bytes: number) => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const handleImageError = (event: Event) => {
  const img = event.target as HTMLImageElement
  img.src = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHZpZXdCb3g9IjAgMCA2NCA2NCIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHJlY3Qgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0IiBmaWxsPSIjRjVGNUY1Ii8+CjxwYXRoIGQ9Ik0yMS4zMzMzIDIxLjMzMzNIMzIuMDAwMCIgc3Ryb2tlPSIjQkZCRkJGIiBzdHJva2Utd2lkdGg9IjIiLz4KPHA+'
}

const handleImagePreview = (imageUrl: string) => {
  previewImageUrl.value = imageUrl
  showImagePreview.value = true
}

onMounted(async () => {
  const categoryLevel2Id = route.query.categoryLevel2Id
  if (categoryLevel2Id) {
    const categoryId = Number(categoryLevel2Id)
    if (!isNaN(categoryId)) {
      searchForm.categoryLevel2Id = categoryId
    }
  }

  await loadCategories()
  try {
    const res: any = await getAllBrands()
    let list: any[] = []
    if (Array.isArray(res)) list = res
    else if (res?.data && Array.isArray(res.data)) list = res.data
    else if (res?.data?.data && Array.isArray(res.data.data)) list = res.data.data
    brandOptions.value = list.filter(Boolean).map(String)
  } catch (e) { }

  if (searchForm.categoryLevel2Id) {
    try {
      const allLevel2Categories = await Promise.all(
          level1Categories.value.map(level1 =>
              getCategoryLevel2ListByLevel1Id(level1.id).catch(() => [])
          )
      )

      for (let i = 0; i < level1Categories.value.length; i++) {
        const level2List = allLevel2Categories[i]
        const found = level2List.find(cat => cat.id === searchForm.categoryLevel2Id)
        if (found) {
          searchForm.categoryLevel1Id = level1Categories.value[i].id
          break
        }
      }
    } catch (error) {
      console.error('加载分类数据失败:', error)
    }
  }

  fetchData()
})
</script>
<style scoped>
.product-management {
  padding: 0;
}

.mb-4 {
  margin-bottom: 16px;
}

.mt-4 {
  margin-top: 16px;
}

.text-right {
  text-align: right;
}

.price-item {
  margin-bottom: 4px;
}

.product-thumbnail {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.3s ease;
  border: 1px solid #d9d9d9;
}

.product-thumbnail:hover {
  transform: scale(1.1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  border-color: #1890ff;
}

.no-image {
  display: inline-block;
  width: 50px;
  height: 50px;
  line-height: 50px;
  text-align: center;
  background: #f5f5f5;
  color: #999;
  font-size: 12px;
  border-radius: 4px;
}

.category-tag {
  font-size: 12px;
  padding: 2px 8px;
  margin: 0;
}

.price-cell {
  font-family: 'Courier New', monospace;
  color: #ff4d4f;
  font-weight: 500;
  font-size: 13px;
}

.price-tooltip {
  max-width: 250px;
}

.price-tooltip div {
  margin-bottom: 4px;
  font-size: 12px;
}

.image-preview-container {
  display: flex;
  justify-content: center;
  align-items: center;
  background: #f5f5f5;
  padding: 20px;
  border-radius: 4px;
}

.ladder-price-section {
  background: #fafafa;
  padding: 16px;
  border-radius: 4px;
  margin-bottom: 16px;
  border: 1px solid #e8e8e8;
}

.ladder-price-row {
  margin-bottom: 0;
}

.search-actions {
  margin-top: 16px;
  text-align: right;
}

.search-actions .ant-btn {
  margin-left: 8px;
}

.btn-success {
  background-color: #52c41a;
  border-color: #52c41a;
  color: white;
}

.btn-success:hover {
  background-color: #73d13d;
  border-color: #73d13d;
}

.empty-resources {
  text-align: center;
  padding: 40px 0;
}

.resources-gallery {
  max-height: 600px;
  overflow-y: auto;
}

.resource-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  padding: 16px 0;
}

.resource-item {
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  overflow: hidden;
  background: #fafafa;
  cursor: pointer;
  transition: all 0.3s ease;
}

.resource-item:hover {
  border-color: #1890ff;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.resource-item.pdf-item {
  border-left: 4px solid #ff4d4f;
}

.resource-info {
  padding: 8px 12px;
  border-bottom: 1px solid #e8e8e8;
}

.resource-name {
  font-size: 12px;
  font-weight: 500;
  color: #262626;
  margin-bottom: 4px;
  word-break: break-all;
}

.resource-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 11px;
}

.resource-category {
  padding: 2px 6px;
  border-radius: 4px;
  color: #fff;
  font-weight: 500;
}

.resource-category.image {
  background: #52c41a;
}

.resource-category.pdf {
  background: #ff4d4f;
}

.resource-type {
  background: #f0f0f0;
  padding: 2px 6px;
  border-radius: 4px;
  color: #595959;
}

.resource-size {
  color: #8c8c8c;
}

.resource-preview {
  height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  position: relative;
}

.resource-preview img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.pdf-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
}

.pdf-label {
  font-size: 12px;
  color: #666;
  font-weight: 500;
}

/* --- 搜索表单专属优化 --- */
.advanced-search-form .ant-form-item {
  margin-bottom: 16px;
}

/* 分类按钮组优化防溢出 */
.category-select-wrapper {
  display: flex;
  align-items: center;
  width: 100%;
}
.category-btn {
  flex: 1;
  overflow: hidden;
  text-align: left;
  padding: 0 12px;
}
.category-btn .btn-text {
  display: inline-block;
  max-width: calc(100% - 24px); /* 给图标留空间 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.clear-btn {
  padding: 0 8px;
  margin-left: 4px;
}

/* 联合过滤条件专属盒子 */
.combined-condition-box {
  display: flex;
  align-items: center;
  background: #fafafa;
  padding: 8px 16px;
  border-radius: 6px;
  border: 1px solid #f0f0f0;
  flex-wrap: wrap; /* 小屏幕自动折行 */
  gap: 8px;
}

.condition-label {
  font-size: 14px;
  color: #5c5c5c;
  font-weight: 500;
  margin-right: 8px;
}

.compact-group {
  display: flex;
  width: max-content;
}
.stock-input {
  width: 90px;
  text-align: center;
}
.stock-separator {
  width: 30px;
  border-left: 0;
  pointer-events: none;
  background-color: #fff;
}
.border-left-0 {
  border-left: 0 !important;
}

.match-any-checkbox {
  margin-left: 12px;
  padding-left: 12px;
  border-left: 1px solid #d9d9d9;
}

.highlight-text {
  color: #fa8c16;
  font-weight: 500;
  user-select: none;
}

/* 按钮操作区固定靠右 */
.search-actions {
  text-align: right;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

/* 保证第二行垂直居中 */
.second-row {
  margin-top: 8px;
}
</style>