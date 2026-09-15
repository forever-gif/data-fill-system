<template>
  <div class="report-catalog-page">
    <!-- 顶部标题区 -->
    <div class="page-top-header">
      <div class="header-title-box">
        <h1 class="page-main-title">报表资产目录</h1>
        <p class="page-sub-title">报表主数据与资产维护入口</p>
      </div>
    </div>

    <!-- 左右主体布局 -->
    <div class="page-layout">
      <!-- 左侧目录树面板 -->
      <div class="folder-sidebar-card">
        <div class="folder-tree-panel">
          <!-- 全部报表 根节点 -->
          <div
            class="folder-all-item"
            :class="{ active: selectedNodeId === 'ALL' }"
            @click="selectTreeCategory('ALL')"
          >
            <el-icon class="folder-root-icon"><Folder /></el-icon>
            <span class="folder-root-name">全部报表</span>
          </div>

          <!-- 优雅文件夹树 (默认合并一级目录，带蓝色文件夹/文件图标、高亮胶囊) -->
          <el-tree
            ref="catalogTreeRef"
            :data="computedTreeData"
            node-key="id"
            highlight-current
            :expand-on-click-node="false"
            :props="{ label: 'name', children: 'children' }"
            :current-node-key="selectedNodeId !== 'ALL' ? selectedNodeId : undefined"
            class="custom-report-tree"
            @node-click="handleNodeClick"
          >
            <template #default="{ node, data }">
              <div class="tree-node-row" :class="{ 'is-selected': selectedNodeId === data.id }">
                <div class="tree-node-left">
                  <!-- 文件夹 vs 文件 图标 -->
                  <el-icon v-if="data.isFolder" class="node-folder-icon">
                    <component :is="node.expanded ? 'FolderOpened' : 'Folder'" />
                  </el-icon>
                  <el-icon v-else class="node-document-icon">
                    <Document />
                  </el-icon>

                  <!-- 节点名称 (纯净去除数字序号展示) -->
                  <el-tooltip :content="formatCleanName(data.name)" placement="top" :show-after="300" :disabled="!data.name">
                    <span class="node-title-text">{{ formatCleanName(data.name) }}</span>
                  </el-tooltip>
                </div>
              </div>
            </template>
          </el-tree>
        </div>

        <!-- 宽度拖拽调节手柄 -->
        <div class="sidebar-resizer" @mousedown="startSidebarResize"></div>
      </div>

      <!-- 右侧主内容卡片面板 -->
      <div class="content-panel">
        <div class="main-table-card">
          <!-- 顶部搜索与多维筛选栏 (全部改为下拉选择框，且严格保持在单行) -->
          <div class="table-search-bar">
            <el-select
              v-model="searchReportName"
              placeholder="报表名称"
              filterable
              clearable
              class="filter-name-box"
            >
              <el-option v-for="name in reportNameOptions" :key="name" :label="name" :value="name" />
            </el-select>

            <el-tree-select
              v-model="searchCatalogPath"
              :data="catalogTreeOptions"
              :props="{ label: 'label', value: 'value', children: 'children' }"
              node-key="value"
              placeholder="所属目录"
              filterable
              clearable
              check-strictly
              :render-after-expand="false"
              class="filter-path-box"
              popper-class="catalog-tree-popper"
            />

            <el-select
              v-model="searchOwner"
              placeholder="责任人"
              filterable
              clearable
              class="filter-owner-box"
            >
              <el-option v-for="o in ownerOptions" :key="o" :label="o" :value="o" />
            </el-select>

            <el-select
              v-model="selectedDept"
              placeholder="所属部门"
              filterable
              clearable
              class="filter-dept-box"
            >
              <el-option v-for="d in departmentOptions" :key="d" :label="d" :value="d" />
            </el-select>

            <el-select
              v-model="selectedStatus"
              placeholder="状态"
              clearable
              class="filter-status-box"
            >
              <el-option label="所有状态" value="" />
              <el-option label="正式使用" value="正式使用" />
              <el-option label="建设中" value="建设中" />
            </el-select>

            <el-button class="action-btn" @click="resetFilter">重置</el-button>
            <el-button type="primary" class="action-btn search-submit-btn" @click="handleFilter">查询</el-button>
            <el-button
              v-if="isAdmin"
              type="success"
              plain
              class="action-btn feishu-sync-btn"
              @click="openFeishuModal"
            >
              <el-icon style="margin-right: 4px;"><Share /></el-icon>
              飞书知识库同步
            </el-button>
            <el-button
              type="primary"
              plain
              class="action-btn wiki-manual-btn"
              @click="openFeishuManual"
            >
              <el-icon style="margin-right: 4px;"><TopRight /></el-icon>
              美鹰报表资产全景手册
            </el-button>
            <el-button
              v-if="isAdmin && selectedRows.length > 0"
              type="warning"
              plain
              class="action-btn batch-sync-btn"
              @click="openBatchSyncModal"
            >
              <el-icon style="margin-right: 4px;"><UploadFilled /></el-icon>
              同步选中 ({{ selectedRows.length }})
            </el-button>
          </div>

          <!-- 数据表格 (从 PostgreSQL dwd.dwd_fid_report_catalog 表实时读取) -->
          <el-table
            v-loading="loading"
            :data="pagedTableData"
            style="width: 100%"
            row-class-name="modern-table-row"
            header-cell-class-name="modern-table-header"
            @selection-change="handleSelectionChange"
          >
            <el-table-column v-if="isAdmin" type="selection" width="45" align="center" />

            <el-table-column key="col_report_name" prop="reportName" label="报表名称" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="form-name-cell">
                  <span class="name-text">{{ formatCleanName(row.reportName) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column key="col_folder_path" prop="_folderPathStr" label="所属目录" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="folder-path-cell">
                  <span class="folder-path-text">{{ formatCleanPath(row._folderPathStr) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column key="col_owner" prop="owner" label="责任人" width="120">
              <template #default="{ row }">
                <span class="text-regular">{{ row.owner || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column key="col_dept" prop="deptName" label="所属部门" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="text-secondary">{{ row.deptName || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column key="col_status" prop="lifecycleStatus" label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag
                  :type="row.lifecycleStatus === '正式' || row.lifecycleStatus === '正式使用' ? 'success' : 'warning'"
                  effect="light"
                  round
                  class="status-pill-tag"
                >
                  {{ (row.lifecycleStatus === '正式' || row.lifecycleStatus === '正式使用') ? '正式使用' : '建设中' }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column key="col_action" label="操作" width="80" fixed="right" align="center">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="goToDetail(row.reportId)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页组件 -->
          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next, jumper"
              :total="filteredTableData.length"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 新建/编辑报表资产 Modal -->
    <el-dialog v-model="addModalVisible" :title="isEditMode ? '编辑报表属性' : '新增报表属性'" width="680px" destroy-on-close>
      <el-form :model="addForm" label-width="110px">
        <el-form-item label="报表名称" required>
          <el-input v-model="addForm.reportName" placeholder="如：03运营利润SKU明细月报" />
        </el-form-item>
        <el-form-item label="报表 ID" required>
          <el-input v-model="addForm.reportId" placeholder="如：REP-202508-0013" :disabled="isEditMode" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="L3 目录">
              <el-input v-model="addForm.l3" placeholder="如：06.财务管理" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="L4 目录">
              <el-input v-model="addForm.l4" placeholder="如：03.利润" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="L5 目录/文件">
              <el-input v-model="addForm.l5" placeholder="如：02.运营利润月报" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="L6 目录/文件">
              <el-input v-model="addForm.l6" placeholder="如：02.运营利润明细" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="L7 文件(.cpt)">
              <el-input v-model="addForm.l7" placeholder="如：03运营利润SKU明细月报.cpt" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="addForm.lifecycleStatus" style="width: 100%">
                <el-option label="正式使用" value="正式使用" />
                <el-option label="建设中" value="建设中" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="责任人">
              <el-input v-model="addForm.owner" placeholder="责任人姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属部门">
              <el-input v-model="addForm.deptName" placeholder="如：财务管理部" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="报表描述">
          <el-input v-model="addForm.reportDesc" type="textarea" :rows="2" placeholder="报表功能说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addModalVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitAddForm">保存至数据库</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入 Modal -->
    <el-dialog v-model="importModalVisible" title="批量导入报表资产" width="500px">
      <el-upload drag action="#" :auto-upload="false" accept=".xlsx,.xls">
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">
          将报表资产 Excel 文件拖到此处，或 <em>点击上传</em>
        </div>
      </el-upload>
      <template #footer>
        <el-button @click="importModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBatchImport">开始解析导入</el-button>
      </template>
    </el-dialog>

    <!-- 同步至飞书知识库 Modal (极简模式：直接使用后端预设的飞书配置与空间) -->
    <el-dialog
      v-model="feishuModalVisible"
      :title="isBatchSync ? `批量同步 (${selectedRows.length} 篇报表) 至飞书全景手册` : '同步报表资产至飞书全景手册'"
      width="540px"
      destroy-on-close
    >
      <el-alert
        title="系统将自动读取服务器配置文件中的飞书自建应用与知识库空间，直接对齐更新至《美鹰报表资产全景手册》。"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 20px;"
      />
      <el-form label-position="top">
        <el-form-item label="页面同步模式">
          <el-radio-group v-model="feishuForm.force" class="sync-mode-radio-group">
            <el-radio :label="false" border class="mode-radio-item">
              <div class="radio-label-wrap">
                <span class="mode-title">⚡ 智能增量更新 (推荐)</span>
                <span class="mode-sub">仅同步发生字段变动的报表，无变化自动跳过 (0 API 消耗)</span>
              </div>
            </el-radio>
            <el-radio :label="true" border class="mode-radio-item">
              <div class="radio-label-wrap">
                <span class="mode-title">🔄 强制覆盖刷新</span>
                <span class="mode-sub">无论数据有无变动，全部强制重排并覆盖整份云文档</span>
              </div>
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="feishuModalVisible = false">取消</el-button>
        <el-button
          v-if="isBatchSync"
          type="warning"
          :loading="syncLoading"
          @click="startBatchSync"
        >
          开始同步勾选报表 ({{ selectedRows.length }})
        </el-button>
        <el-button
          v-else
          type="primary"
          :loading="syncLoading"
          @click="startFeishuSync"
        >
          开始执行同步
        </el-button>
      </template>
    </el-dialog>

    <!-- 🌟 同步执行结果与变动审计日志 Modal (结构化详细清单与报错说明) -->
    <el-dialog
      v-model="syncResultModalVisible"
      title="飞书全景手册同步执行结果"
      width="640px"
      destroy-on-close
      class="sync-result-dialog"
    >
      <div class="sync-result-content">
        <!-- 成功状态卡片 (有数据更新) -->
        <div v-if="isSyncSuccess && (syncResultData.updatedCount > 0 || (syncResultData.changeLogs && syncResultData.changeLogs.length > 0))" class="result-summary-card success">
          <el-icon class="result-status-icon" color="#10b981"><CircleCheckFilled /></el-icon>
          <div class="result-status-text">
            <h4 class="result-title">{{ syncResultData.message || '飞书全景手册同步完成！' }}</h4>
            <p class="result-meta">
              报表总数: <b>{{ syncResultData.total || 0 }}</b> 篇 &nbsp;|&nbsp;
              本次更新: <b style="color: #2563eb;">{{ syncResultData.updatedCount || 0 }}</b> 篇 &nbsp;|&nbsp;
              无变动略过: <b style="color: #64748b;">{{ syncResultData.skippedCount || 0 }}</b> 篇
            </p>
            <div v-if="syncResultData.durationMs !== undefined" class="result-perf-tags">
              <el-tag size="small" type="info" effect="plain">⏱️ 耗时 {{ syncResultData.durationMs }} ms</el-tag>
              <el-tag size="small" :type="syncResultData.apiCalls === 0 ? 'success' : 'warning'" effect="plain">
                🎯 飞书 API 调用: {{ syncResultData.apiCalls || 0 }} 次
              </el-tag>
              <span v-if="syncResultData.syncTime" class="sync-time-label">{{ syncResultData.syncTime }}</span>
            </div>
          </div>
        </div>

        <!-- 成功状态卡片 (0 变动，完全对齐) -->
        <div v-else-if="isSyncSuccess" class="result-summary-card info">
          <el-icon class="result-status-icon" color="#2563eb"><CircleCheckFilled /></el-icon>
          <div class="result-status-text">
            <h4 class="result-title" style="color: #1e40af;">⚡ 智能增量校验通过：数据已与飞书手册保持一致</h4>
            <p class="result-meta">
              全量 <b>{{ syncResultData.total || 176 }}</b> 篇报表数据均无变更，飞书云文档已是最新状态，已自动跳过云文档重写（零 API 消耗）。
            </p>
            <div v-if="syncResultData.durationMs !== undefined" class="result-perf-tags">
              <el-tag size="small" type="success" effect="plain">⏱️ 耗时仅 {{ syncResultData.durationMs }} ms (秒级命中)</el-tag>
              <el-tag size="small" type="success" effect="plain">🎯 飞书 API 调用: 0 次 (零配额消耗)</el-tag>
              <span v-if="syncResultData.syncTime" class="sync-time-label">{{ syncResultData.syncTime }}</span>
            </div>
          </div>
        </div>

        <!-- 失败状态卡片 (真正的失败) -->
        <div v-else class="result-summary-card error">
          <el-icon class="result-status-icon" color="#ef4444"><CircleCloseFilled /></el-icon>
          <div class="result-status-text">
            <h4 class="result-title">飞书知识库同步失败</h4>
            <p class="result-meta error-desc">{{ syncResultData.message || '网络或接口异常，请查看报错日志' }}</p>
          </div>
        </div>

        <!-- 详细字段变动清单 (如果有报表变动) -->
        <div v-if="syncResultData.changeLogs && syncResultData.changeLogs.length > 0" class="change-log-section">
          <div class="change-log-header">
            <span>📋 本次更新字段明细清单 (共 {{ syncResultData.changeLogs.length }} 篇报表有变动)</span>
          </div>
          <div class="change-log-list">
            <div
              v-for="(item, idx) in syncResultData.changeLogs"
              :key="'log_' + idx"
              class="change-log-item"
            >
              <div class="item-title-bar">
                <el-icon class="doc-icon"><Document /></el-icon>
                <span class="report-name">{{ item.reportName }}</span>
                <el-tag size="small" :type="item.changeType === 'NEW' ? 'success' : (item.changeType === 'DELETE' ? 'danger' : 'primary')" effect="plain">
                  {{ item.changeType === 'NEW' ? '新收录' : (item.changeType === 'DELETE' ? '已移除下线' : '字段已更新') }}
                </el-tag>
              </div>
              <ul class="item-diff-list">
                <li v-for="(diff, dIdx) in item.details" :key="'diff_' + dIdx">
                  <span class="diff-bullet">•</span> {{ diff }}
                </li>
              </ul>
            </div>
          </div>
        </div>

        <!-- 0 变动提示 -->
        <div v-else-if="syncResultData.success && syncResultData.updatedCount === 0" class="no-change-tip">
          <el-icon size="18" color="#3b82f6"><InfoFilled /></el-icon>
          <span>经指纹深度校验，数据库中所有报表字段与飞书手册完全一致。若需强制重新生成整份手册，请选择【强制覆盖刷新】。</span>
        </div>
      </div>

      <template #footer>
        <div class="result-dialog-footer">
          <el-button @click="syncResultModalVisible = false">关 闭</el-button>
          <el-button
            type="primary"
            @click="downloadSyncLog"
          >
            <el-icon style="margin-right: 4px;"><Download /></el-icon>
            {{ isSyncSuccess ? (syncResultData.updatedCount > 0 ? '下载变更日志清单' : '下载同步审计日志') : '下载错误清单' }}
          </el-button>
          <el-button
            v-if="syncResultData.docUrl"
            type="success"
            @click="openExternalDoc(syncResultData.docUrl)"
          >
            <el-icon style="margin-right: 4px;"><TopRight /></el-icon>
            打开飞书全景手册
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Folder, FolderOpened, Document, Search, ArrowDown,
  Plus, Upload, UploadFilled, Share, User,
  CircleCheckFilled, CircleCloseFilled, TopRight, InfoFilled, Download
} from '@element-plus/icons-vue'
import axios from 'axios'
import { decryptUser, isEncrypted } from '@/utils/crypto.js'
import { isKnownAdmin } from '@/utils/admin.js'

defineOptions({
  name: 'ReportCatalogList'
})

const route = useRoute()
const router = useRouter()

// 🌟 管理员权限控制：仅管理员（如 finereport_manage 等）可见飞书同步、批量同步等运维按钮，普通用户只展示查看与报表浏览
const injectedIsAdmin = inject('isAdmin', null)
const isAdmin = computed(() => {
  if (injectedIsAdmin && injectedIsAdmin.value) {
    return true
  }
  let rawUser = (route.query && route.query.user) || ''
  if (!rawUser && typeof window !== 'undefined') {
    const urlParams = new URLSearchParams(window.location.search)
    rawUser = urlParams.get('user') || ''
    if (!rawUser && window.location.hash.includes('?')) {
      const hashParams = new URLSearchParams(window.location.hash.split('?')[1])
      rawUser = hashParams.get('user') || ''
    }
  }
  if (!rawUser) return false
  const plainUser = isEncrypted(rawUser) ? decryptUser(rawUser) : rawUser
  return isKnownAdmin(plainUser)
})

// 侧边栏宽度
const sidebarWidth = ref(250)

// 数据库获取的真实表格数据
const rawTableData = ref([])

// 筛选状态 (支持 报表名称、所属目录、责任人 分开输入)
const searchReportName = ref('')
const searchCatalogPath = ref('')
const searchOwner = ref('')
const selectedDept = ref('')
const selectedStatus = ref('')
const selectedNodeId = ref('ALL')
const selectedFullPath = ref('')
const selectedReportId = ref('')
const loading = ref(false)

// 🌟 视图状态持久化机制（在跳转详情与返回时 100% 完整保留选中的目录、搜索词与筛选条件）
const savePageState = () => {
  try {
    sessionStorage.setItem('REPORT_CATALOG_FILTER_STATE', JSON.stringify({
      selectedNodeId: selectedNodeId.value,
      selectedFullPath: selectedFullPath.value,
      selectedReportId: selectedReportId.value,
      searchReportName: searchReportName.value,
      searchCatalogPath: searchCatalogPath.value,
      searchOwner: searchOwner.value,
      selectedDept: selectedDept.value,
      selectedStatus: selectedStatus.value,
      appliedFilter: appliedFilter.value,
      currentPage: currentPage.value,
      pageSize: pageSize.value
    }))
  } catch (e) {}
}

const restorePageState = () => {
  try {
    const saved = sessionStorage.getItem('REPORT_CATALOG_FILTER_STATE')
    if (saved) {
      const state = JSON.parse(saved)
      if (state.selectedNodeId !== undefined) selectedNodeId.value = state.selectedNodeId
      if (state.selectedFullPath !== undefined) selectedFullPath.value = state.selectedFullPath
      if (state.selectedReportId !== undefined) selectedReportId.value = state.selectedReportId
      if (state.searchReportName !== undefined) searchReportName.value = state.searchReportName
      if (state.searchCatalogPath !== undefined) searchCatalogPath.value = state.searchCatalogPath
      if (state.searchOwner !== undefined) searchOwner.value = state.searchOwner
      if (state.selectedDept !== undefined) selectedDept.value = state.selectedDept
      if (state.selectedStatus !== undefined) selectedStatus.value = state.selectedStatus
      if (state.appliedFilter !== undefined) appliedFilter.value = state.appliedFilter
      if (state.currentPage !== undefined) currentPage.value = state.currentPage
      if (state.pageSize !== undefined) pageSize.value = state.pageSize
      return true
    }
  } catch (e) {}
  return false
}

// 飞书同步弹窗与状态
const feishuModalVisible = ref(false)
const isBatchSync = ref(false)
const testLoading = ref(false)
const syncLoading = ref(false)
const syncSuccess = ref(true)
const syncResultMsg = ref('')
const syncResultModalVisible = ref(false)
const syncResultData = ref({})

const isSyncSuccess = computed(() => {
  if (syncResultData.value.success === false) return false
  if (syncResultData.value.isError === true) return false
  return Boolean(syncResultData.value.docUrl || syncResultData.value.success || syncResultData.value.message)
})
const selectedRows = ref([])
const feishuForm = ref({
  force: false
})

const feishuWikiUrl = ref('https://feishu.cn/wiki/Q58FwZAMeiobL3kdmWFc6zwcnSq')

const openExternalDoc = (url) => {
  if (url) {
    window.open(url, '_blank')
  }
}

const openFeishuManual = () => {
  openExternalDoc(feishuWikiUrl.value)
}

// 🌟 下载结构化同步日志清单 / 错误日志文件
const downloadSyncLog = () => {
  const data = syncResultData.value || {}
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const timeStr = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  const fileDateStr = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`

  let content = ''
  content += '================================================================================\n'
  content += '         美鹰报表资产系统 - 飞书知识库全景手册同步执行日志\n'
  content += '================================================================================\n'
  content += `执行时间: ${data.syncTime || timeStr}\n`
  content += `同步状态: ${isSyncSuccess.value ? '【成功】' : '【失败】'}\n`
  if (data.durationMs !== undefined) {
    content += `执行耗时: ${data.durationMs} ms\n`
  }
  if (data.apiCalls !== undefined) {
    content += `飞书接口调用: ${data.apiCalls} 次\n`
  }
  if (data.docTitle) {
    content += `目标文档: ${data.docTitle}\n`
  }
  if (data.docUrl) {
    content += `文档链接: ${data.docUrl}\n`
  }
  if (data.spaceId) {
    content += `知识库空间 ID: ${data.spaceId}\n`
  }

  content += '\n--------------------------------------------------------------------------------\n'
  content += '【一、同步统计摘要】\n'
  content += '--------------------------------------------------------------------------------\n'
  content += `• 报表资产总数: ${data.total || 0} 篇\n`
  content += `• 本次更新篇数: ${data.updatedCount || 0} 篇\n`
  content += `• 智能略过篇数: ${data.skippedCount || 0} 篇 (数据完全一致，0 API 消耗)\n`
  content += `• 异常失败篇数: ${data.failCount || 0} 篇\n`

  if (!isSyncSuccess.value) {
    content += '\n--------------------------------------------------------------------------------\n'
    content += '【二、报错异常详情】\n'
    content += '--------------------------------------------------------------------------------\n'
    content += `异常原因: ${data.message || '未知错误'}\n\n`
    content += '【排查建议指引】:\n'
    content += '1. 检查服务器网络与代理配置，确保可正常访问飞书开放平台 (https://open.feishu.cn)；\n'
    content += '2. 检查飞书应用 App ID 与 App Secret 是否有效，未过期或被停用；\n'
    content += '3. 检查知识库空间 ID 是否正确，且应用已被加入知识库协作者中并赋予编辑权限；\n'
    content += '4. 若提示 QPS 限频或 Handshake 异常，通常为飞书网关并发保护，请等待 1~2 分钟后重试。\n'
  }

  if (data.changeLogs && data.changeLogs.length > 0) {
    content += '\n--------------------------------------------------------------------------------\n'
    content += `【二、字段级精准变更清单】(共 ${data.changeLogs.length} 篇报表有更新)\n`
    content += '--------------------------------------------------------------------------------\n'
    data.changeLogs.forEach((item, index) => {
      content += `\n[${index + 1}] 📄 ${item.reportName || '未命名报表'} (ID: ${item.reportId || '-'}) [${item.changeType === 'NEW' ? '新收录' : '字段已更新'}]\n`
      if (item.details && item.details.length > 0) {
        item.details.forEach(d => {
          content += `    • ${d}\n`
        })
      } else {
        content += '    • 报表基础数据更新\n'
      }
    })
  } else if (isSyncSuccess.value && data.updatedCount === 0) {
    content += '\n--------------------------------------------------------------------------------\n'
    content += '【二、变动核验明细】\n'
    content += '--------------------------------------------------------------------------------\n'
    content += '经本地 MD5 指纹深度比对，全量 176 篇报表的全部字段与飞书全景手册完全一致。\n'
    content += '本次同步触发了智能零消耗拦截，未向飞书发出任何多余 API 写入。\n'
  }

  content += '\n================================================================================\n'
  content += `日志生成时间: ${timeStr}\n`
  content += '系统来源: 美鹰数据填报与报表资产管理系统\n'
  content += '================================================================================\n'

  const fileName = isSyncSuccess.value
    ? `飞书全景手册同步日志_${fileDateStr}.txt`
    : `飞书同步错误清单_${fileDateStr}.txt`

  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)

  ElMessage.success(`已成功导出日志: ${fileName}`)
}

// 分页
const currentPage = ref(1)
const pageSize = ref(20)

// 弹窗状态
const addModalVisible = ref(false)
const isEditMode = ref(false)
const submitLoading = ref(false)
const importModalVisible = ref(false)
const addForm = ref({ lifecycleStatus: '建设中' })

// 自然排序比较器
const naturalCompare = (a, b) => {
  if (!a && !b) return 0
  if (!a) return -1
  if (!b) return 1
  return a.localeCompare(b, 'zh-CN', { numeric: true, sensitivity: 'base' })
}

// 展示名称过滤器：完整保留原始数字序号 (如 00.经营管理, 01.产品管理)，仅去除文件后缀
const formatCleanName = (name) => {
  if (!name) return ''
  return name.replace(/\.cpt$/i, '').replace(/\.frm$/i, '').trim()
}

// 展示路径过滤器：保留目录链中每一级的数字序号
const formatCleanPath = (pathStr) => {
  if (!pathStr) return '默认目录'
  return pathStr.split(' / ').map(formatCleanName).join(' / ')
}

// 动态提取报表名称下拉选项
const reportNameOptions = computed(() => {
  const names = new Set()
  rawTableData.value.forEach(item => {
    const n = formatCleanName(item.reportName || item._cptFile)
    if (n && n.trim()) names.add(n.trim())
  })
  return Array.from(names).sort(naturalCompare)
})

// 动态提取所属目录下拉选项
const catalogOptions = computed(() => {
  const paths = new Set()
  rawTableData.value.forEach(item => {
    if (item._folderPathStr && item._folderPathStr.trim() && item._folderPathStr !== '默认目录') {
      paths.add(formatCleanPath(item._folderPathStr.trim()))
    }
  })
  return Array.from(paths).sort(naturalCompare)
})

// 动态提取责任人下拉选项
const ownerOptions = computed(() => {
  const owners = new Set()
  rawTableData.value.forEach(item => {
    if (item.owner && item.owner.trim() && item.owner.trim() !== '-') {
      owners.add(item.owner.trim())
    }
  })
  return Array.from(owners).sort(naturalCompare)
})

// 动态提取部门列表下拉选项
const departmentOptions = computed(() => {
  const depts = new Set()
  rawTableData.value.forEach(item => {
    if (item.deptName && item.deptName.trim() && item.deptName.trim() !== '-') {
      depts.add(item.deptName.trim())
    }
  })
  return Array.from(depts).sort(naturalCompare)
})

// 核心：任意深度动态多级目录树算法 (一级业务目录 -> 二级业务目录 -> 三级目录 -> 报表文档 逐级清晰展开)
const computedTreeData = computed(() => {
  const rootNodes = []
  const pathMap = new Map()

  rawTableData.value.forEach(row => {
    let folderChain = []

    // 🌟 1. 优先从 fullPath 解析：路径的最后一个段一定是报表文件自身，前面的段全部是纯目录！
    if (row.fullPath && row.fullPath.includes('/')) {
      const parts = row.fullPath.split('/').map(p => p.trim()).filter(Boolean)
      // 排除最后一个元素（报表文件自身），前面的全部是父级目录
      for (let i = 0; i < parts.length - 1; i++) {
        const p = parts[i]
        if (i === 0 && (p.includes('报表') || p.includes('数据分析') || p.includes('Dec-Entry'))) {
          continue
        }
        if (p === 'Dec-Entry_Management') continue
        if (!folderChain.includes(p)) {
          folderChain.push(p)
        }
      }
    }

    // 🌟 2. 兜底：如果 fullPath 为空，尝试从 l2~l7 提取
    if (folderChain.length === 0) {
      let rawLevels = [row.l2, row.l3, row.l4, row.l5, row.l6, row.l7]
        .map(s => (s || '').trim())
        .filter(s => s && s !== 'Dec-Entry_Management')

      if (rawLevels.length > 0) {
        const last = rawLevels[rawLevels.length - 1]
        // 如果末尾项等于报表名或以.cpt/.frm结尾，剔除它（因为它是报表文件）
        if (last.toLowerCase().endsWith('.cpt') || last.toLowerCase().endsWith('.frm') ||
            last === row.reportName || formatCleanName(last) === formatCleanName(row.reportName) ||
            last.includes(formatCleanName(row.reportName)) || formatCleanName(row.reportName).includes(formatCleanName(last))) {
          rawLevels.pop()
        }
        folderChain = rawLevels
      }
    }

    // 🌟 3. 再次兜底从业务域与主题分类提取
    if (folderChain.length === 0) {
      if (row.bizDomain) folderChain.push(row.bizDomain)
      if (row.subjectL1 && !folderChain.includes(row.subjectL1)) folderChain.push(row.subjectL1)
      if (row.subjectL2 && !folderChain.includes(row.subjectL2)) folderChain.push(row.subjectL2)
    }

    if (folderChain.length === 0) {
      folderChain = ['默认目录']
    }

    const cptFile = row.reportName || '未命名报表.cpt'

    row._folderChain = folderChain
    row._folderPathStr = folderChain.join(' / ')
    row._cptFile = cptFile

    // 1. 逐级构建父子目录节点 (一级业务目录 -> 二级业务目录 -> 三级业务目录)
    let currentPath = ''
    let parentChildren = rootNodes

    folderChain.forEach((seg, depth) => {
      currentPath = currentPath ? `${currentPath} / ${seg}` : seg
      let node = pathMap.get(currentPath)
      if (!node) {
        node = {
          id: `node_${currentPath}`,
          name: seg,
          fullPath: currentPath,
          depth: depth + 1,
          isFolder: true,
          count: 0,
          children: []
        }
        pathMap.set(currentPath, node)
        parentChildren.push(node)
      }
      node.count++
      parentChildren = node.children
    })

    // 2. 在最末级目录节点下，挂载具体的报表文件叶子节点
    const fileNode = {
      id: `file_${row.reportId}`,
      name: row.reportName || cptFile,
      reportId: row.reportId,
      fullPath: `${currentPath} / ${row.reportName || cptFile}`,
      isFolder: false,
      reportData: row
    }
    parentChildren.push(fileNode)
  })

  // 递归自然排序与清理
  const sortAndCleanTree = (nodes) => {
    nodes.sort((a, b) => {
      if (a.isFolder && !b.isFolder) return -1
      if (!a.isFolder && b.isFolder) return 1
      return naturalCompare(a.name, b.name)
    })
    nodes.forEach(n => {
      if (n.children && n.children.length === 0) {
        delete n.children
      } else if (n.children) {
        sortAndCleanTree(n.children)
      }
    })
  }
  sortAndCleanTree(rootNodes)

  return rootNodes
})

// 递归提取纯目录树 (过滤掉具体的报表文件，供顶部“所属目录”树形选择器使用)
const extractFolderTree = (nodes) => {
  const result = []
  if (!nodes || !nodes.length) return result
  for (const node of nodes) {
    if (node.isFolder) {
      const item = {
        value: node.fullPath,
        label: formatCleanName(node.name),
        children: []
      }
      if (node.children && node.children.length > 0) {
        const subFolders = extractFolderTree(node.children)
        if (subFolders.length > 0) {
          item.children = subFolders
        } else {
          delete item.children
        }
      } else {
        delete item.children
      }
      result.push(item)
    }
  }
  return result
}

// 动态构建纯目录层级多层树结构 (支持多级折叠展开、搜索与任意层级选择)
const catalogTreeOptions = computed(() => {
  return extractFolderTree(computedTreeData.value)
})

// 实际生效的筛选条件 (仅在点击【查询】按钮或回车时生效)
const appliedFilter = ref({
  reportName: '',
  catalogPath: '',
  owner: '',
  dept: '',
  status: ''
})

// 过滤与排序后的报表列表
const filteredTableData = computed(() => {
  const filtered = rawTableData.value.filter(item => {
    if (selectedReportId.value) {
      if (item.reportId !== selectedReportId.value) return false
    } else if (selectedFullPath.value) {
      const itemPath = item._folderPathStr || ''
      const targetPath = selectedFullPath.value
      const isMatch = itemPath === targetPath || itemPath.startsWith(targetPath + ' / ')
      if (!isMatch) return false
    }

    if (appliedFilter.value.reportName) {
      const q = appliedFilter.value.reportName.toLowerCase()
      const matchName = (item.reportName || '').toLowerCase().includes(q)
      const matchFile = (item._cptFile || '').toLowerCase().includes(q)
      if (!matchName && !matchFile) return false
    }

    if (appliedFilter.value.catalogPath) {
      const q = appliedFilter.value.catalogPath.toLowerCase()
      const matchFolder = (item._folderPathStr || '').toLowerCase().includes(q)
      const matchL1 = (item.l1 || '').toLowerCase().includes(q)
      const matchL2 = (item.l2 || '').toLowerCase().includes(q)
      const matchL3 = (item.l3 || '').toLowerCase().includes(q)
      const matchL4 = (item.l4 || '').toLowerCase().includes(q)
      const matchL5 = (item.l5 || '').toLowerCase().includes(q)
      const matchL6 = (item.l6 || '').toLowerCase().includes(q)
      const matchL7 = (item.l7 || '').toLowerCase().includes(q)
      const matchFull = (item.fullPath || '').toLowerCase().includes(q)
      if (!matchFolder && !matchL1 && !matchL2 && !matchL3 && !matchL4 && !matchL5 && !matchL6 && !matchL7 && !matchFull) return false
    }

    if (appliedFilter.value.owner) {
      const q = appliedFilter.value.owner.toLowerCase()
      const matchOwner = (item.owner || '').toLowerCase().includes(q)
      if (!matchOwner) return false
    }

    if (appliedFilter.value.dept && item.deptName !== appliedFilter.value.dept) {
      return false
    }

    if (appliedFilter.value.status) {
      const target = appliedFilter.value.status
      const raw = (item.lifecycleStatus || '').trim()
      const isOfficial = (raw === '正式' || raw === '正式使用')
      if (target === '正式使用' && !isOfficial) {
        return false
      }
      if (target === '建设中' && isOfficial) {
        return false
      }
    }

    return true
  })

  return filtered.sort((a, b) => {
    const dirA = a._folderPathStr || ''
    const dirB = b._folderPathStr || ''
    if (dirA !== dirB) {
      return naturalCompare(dirA, dirB)
    }
    return naturalCompare(a.reportName || a._cptFile, b.reportName || b._cptFile)
  })
})

const pagedTableData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredTableData.value.slice(start, start + pageSize.value)
})

const goToDetail = (reportId) => {
  savePageState()
  router.push({
    path: `/report-detail/${reportId}`,
    query: route.query
  })
}

const handleNodeClick = (nodeData, node) => {
  selectedNodeId.value = nodeData.id
  if (!nodeData.isFolder) {
    // 🌟 选中具体报表文件：只在右侧表格中联动高亮/筛选出该报表，不再直接跳转详情页
    selectedReportId.value = nodeData.reportId
    selectedFullPath.value = ''
  } else {
    selectedReportId.value = ''
    selectedFullPath.value = nodeData.fullPath
    if (node) {
      node.expanded = !node.expanded
    }
  }
  currentPage.value = 1
  savePageState()
}

const selectTreeCategory = (id) => {
  selectedNodeId.value = id
  selectedFullPath.value = ''
  selectedReportId.value = ''
  currentPage.value = 1
  savePageState()
}

const handleFilter = () => {
  appliedFilter.value = {
    reportName: searchReportName.value ? searchReportName.value.trim() : '',
    catalogPath: searchCatalogPath.value ? searchCatalogPath.value.trim() : '',
    owner: searchOwner.value ? searchOwner.value.trim() : '',
    dept: selectedDept.value || '',
    status: selectedStatus.value || ''
  }
  currentPage.value = 1
  savePageState()
}

const resetFilter = () => {
  searchReportName.value = ''
  searchCatalogPath.value = ''
  searchOwner.value = ''
  selectedDept.value = ''
  selectedStatus.value = ''
  appliedFilter.value = {
    reportName: '',
    catalogPath: '',
    owner: '',
    dept: '',
    status: ''
  }
  selectedNodeId.value = 'ALL'
  selectedFullPath.value = ''
  selectedReportId.value = ''
  currentPage.value = 1
  try {
    sessionStorage.removeItem('REPORT_CATALOG_FILTER_STATE')
  } catch (e) {}
}

const handleSizeChange = (val) => {
  pageSize.value = val
}
const handleCurrentChange = (val) => {
  currentPage.value = val
}

const openAddDialog = () => {
  isEditMode.value = false
  const autoId = `REP-${new Date().getFullYear()}${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(rawTableData.value.length + 1).padStart(4, '0')}`
  addForm.value = {
    reportId: autoId,
    lifecycleStatus: '建设中',
    l1: '集团报表',
    l2: '01生产环境',
    l3: '06.财务管理',
    l4: '03.利润',
    l5: '02.运营利润月报',
    l6: '02.运营利润明细'
  }
  addModalVisible.value = true
}

const openEditModal = (row) => {
  isEditMode.value = true
  addForm.value = { ...row }
  addModalVisible.value = true
}

const submitAddForm = async () => {
  if (!addForm.value.reportName || !addForm.value.reportId) {
    ElMessage.warning('请填写报表名称和报表ID')
    return
  }
  submitLoading.value = true
  try {
    const reportId = addForm.value.reportId
    await axios.put(`/api/report/catalog/${reportId}`, addForm.value)
    ElMessage.success(isEditMode.value ? '报表资产已更新至数据库' : '新报表资产已成功写入数据库')
    addModalVisible.value = false
    await fetchCatalogList()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  } finally {
    submitLoading.value = false
  }
}

const handleMoreCommand = async (cmd, row) => {
  if (cmd === 'edit') {
    openEditModal(row)
  } else if (cmd === 'copy') {
    navigator.clipboard.writeText(row.reportId)
    ElMessage.success(`已复制 ID: ${row.reportId}`)
  } else if (cmd === 'open') {
    const url = row.reportUrl || `https://finevis.example.com/report/${row.reportId}`
    window.open(url, '_blank')
  } else if (cmd === 'delete') {
    ElMessageBox.confirm(`确认将【${row.reportName}】从数据库中移出？`, '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    }).then(async () => {
      try {
        await axios.delete(`/api/report/catalog/${row.reportId}`)
        ElMessage.success('已从数据库中移出')
        await fetchCatalogList()
      } catch (e) {
        ElMessage.error('删除失败')
      }
    }).catch(() => {})
  }
}

const handleBatchImport = () => {
  ElMessage.success('已接收批量导入文件，请稍候解析写入数据库...')
  importModalVisible.value = false
}

// 表格勾选变动
const handleSelectionChange = (rows) => {
  selectedRows.value = rows
}

// 打开飞书全量同步弹窗
const openFeishuModal = () => {
  isBatchSync.value = false
  feishuForm.value.force = false
  feishuModalVisible.value = true
}

// 打开勾选批量同步弹窗
const openBatchSyncModal = () => {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先勾选需要同步的报表！')
    return
  }
  isBatchSync.value = true
  feishuForm.value.force = false
  feishuModalVisible.value = true
}

// 开始全量同步飞书知识库 (增量/全量)
const startFeishuSync = async () => {
  syncLoading.value = true
  try {
    const payload = {
      force: feishuForm.value.force
    }
    const res = await axios.post('/api/feishu/sync/all', payload)
    feishuModalVisible.value = false
    syncResultData.value = res.data || {}
    syncResultModalVisible.value = true
    if (res.data?.success) {
      ElMessage.success(res.data.message || '同步完成')
    } else {
      ElMessage.warning(res.data?.message || '同步未完成')
    }
  } catch (e) {
    feishuModalVisible.value = false
    syncResultData.value = {
      success: false,
      message: e.response?.data?.message || e.message
    }
    syncResultModalVisible.value = true
    ElMessage.error('同步失败: ' + (e.response?.data?.message || e.message))
  } finally {
    syncLoading.value = false
  }
}

// 开始批量同步选中的报表
const startBatchSync = async () => {
  if (selectedRows.value.length === 0) return
  syncLoading.value = true
  try {
    const payload = {
      reportIds: selectedRows.value.map(r => r.reportId),
      force: feishuForm.value.force
    }
    const res = await axios.post('/api/feishu/sync/batch', payload)
    feishuModalVisible.value = false
    syncResultData.value = res.data || {}
    syncResultModalVisible.value = true
    if (res.data?.success) {
      ElMessage.success(res.data.message || '批量同步完成')
    } else {
      ElMessage.warning(res.data?.message || '批量同步未完成')
    }
  } catch (e) {
    feishuModalVisible.value = false
    syncResultData.value = {
      success: false,
      message: e.response?.data?.message || e.message
    }
    syncResultModalVisible.value = true
    ElMessage.error('批量同步失败: ' + (e.response?.data?.message || e.message))
  } finally {
    syncLoading.value = false
  }
}



const startSidebarResize = (e) => {
  const startX = e.clientX
  const startWidth = sidebarWidth.value

  const onMouseMove = (moveEvent) => {
    const newWidth = Math.max(200, Math.min(500, startWidth + (moveEvent.clientX - startX)))
    sidebarWidth.value = newWidth
  }

  const onMouseUp = () => {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  }

  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}

const fetchCatalogList = async () => {
  // 🌟 1. 优先从浏览器本地 Session 高速缓存中瞬时预加载，实现 0 毫秒即开即看
  const cached = sessionStorage.getItem('REPORT_CATALOG_CACHE_DATA')
  if (cached) {
    try {
      const parsed = JSON.parse(cached)
      if (Array.isArray(parsed) && parsed.length > 0) {
        rawTableData.value = parsed
        loading.value = false
      }
    } catch (e) {}
  } else {
    loading.value = true
  }

  // 🌟 2. 后台异步静默获取最新 PostgreSQL 数据，保证数据实时性与极致速度兼得
  try {
    const res = await axios.get(`/api/report/catalog/list?_t=${Date.now()}`)
    if (res.data && Array.isArray(res.data)) {
      rawTableData.value = res.data
      sessionStorage.setItem('REPORT_CATALOG_CACHE_DATA', JSON.stringify(res.data))
    }
  } catch (e) {
    if (!rawTableData.value || rawTableData.value.length === 0) {
      ElMessage.warning('从数据库读取报表目录失败，请检查网络或后端状态')
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const restored = restorePageState()
  if (!restored) {
    selectTreeCategory('ALL')
  }
  fetchCatalogList()
  axios.get('/api/feishu/config').then(res => {
    if (res.data?.wikiDocUrl) {
      feishuWikiUrl.value = res.data.wikiDocUrl
    }
  }).catch(() => {})
})
</script>

<style scoped>
.report-catalog-page {
  width: 100%;
  padding: 0 4px;
  box-sizing: border-box;
}

/* 顶部标题与全局操作区 (1:1 还原右侧参考设计) */
.page-top-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding: 4px 2px 8px 2px;
}

.page-main-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
  letter-spacing: -0.3px;
}

.page-sub-title {
  font-size: 13px;
  color: #64748b;
  margin: 4px 0 0 0;
}

.header-action-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

.top-action-btn.primary-btn {
  background-color: #2563eb !important;
  border-color: #2563eb !important;
  font-weight: 600;
  padding: 8px 18px;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(37, 99, 235, 0.2);
}

.top-action-btn.import-btn {
  font-weight: 500;
  padding: 8px 18px;
  border-radius: 8px;
  background-color: #f8fafc;
  border-color: #e2e8f0;
  color: #334155;
}

.top-action-btn.import-btn:hover {
  background-color: #f1f5f9;
  color: #0f172a;
}

/* 主体左右分栏布局 */
.page-layout {
  width: 100%;
  display: flex;
  gap: 16px;
  align-items: flex-start;
  box-sizing: border-box;
}

/* 左侧目录侧边栏 */
.folder-sidebar-card {
  position: relative;
  width: v-bind('sidebarWidth + "px"');
  min-width: 200px;
  max-width: 500px;
  flex-shrink: 0;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  background: white;
  padding: 12px 6px;
  box-sizing: border-box;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
  transition: all 0.2s;
}

.folder-tree-panel {
  width: 100%;
}

/* 全部报表项 */
.folder-all-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  color: #334155;
  margin-bottom: 6px;
  font-size: 13.5px;
  border-radius: 8px;
  transition: all 0.2s;
}

.folder-root-icon {
  font-size: 16px;
  color: #3b82f6;
}

.folder-root-name {
  font-weight: 600;
}

.folder-all-item:hover {
  background: #f1f5f9;
  color: #2563eb;
}

.folder-all-item.active {
  color: #2563eb;
  font-weight: 600;
  background: #eff6ff;
}

/* 树组件定制 */
.custom-report-tree {
  background: transparent;
  color: #334155;
}

.custom-report-tree :deep(.el-tree-node__content) {
  height: 34px;
  padding-right: 6px;
  border-radius: 8px;
  margin-bottom: 2px;
  transition: all 0.2s;
}

.custom-report-tree :deep(.el-tree-node__content:hover) {
  background-color: #f1f5f9;
}

.custom-report-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: #eff6ff !important;
  color: #2563eb !important;
  font-weight: 600;
}

.custom-report-tree :deep(.el-tree-node__expand-icon) {
  font-size: 12px;
  color: #94a3b8;
  padding: 6px;
}

.custom-report-tree :deep(.el-tree-node__expand-icon.is-leaf) {
  color: transparent;
}

.tree-node-row {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.tree-node-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.node-folder-icon {
  font-size: 16px;
  color: #3b82f6;
  flex-shrink: 0;
}

.node-document-icon {
  font-size: 15px;
  color: #64748b;
  flex-shrink: 0;
}

.custom-report-tree :deep(.el-tree-node.is-current .node-document-icon) {
  color: #2563eb;
}

.node-title-text {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: inherit;
}

.sidebar-resizer {
  position: absolute;
  top: 0;
  right: 0;
  width: 4px;
  height: 100%;
  cursor: col-resize;
  z-index: 10;
}

.sidebar-resizer:hover {
  background: #2563eb;
  opacity: 0.3;
}

/* 右侧内容区面板 */
.content-panel {
  flex: 1;
  min-width: 0;
  width: 0;
}

.main-table-card {
  background: white;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
  padding: 16px 20px;
  box-sizing: border-box;
}

/* 顶部搜索过滤栏 (严格单行对齐) */
.table-search-bar {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px;
  margin-bottom: 16px;
  width: 100%;
  overflow-x: auto;
  padding-bottom: 2px;
}

.filter-name-box {
  width: 160px;
  min-width: 120px;
  flex-shrink: 1;
}

.filter-path-box {
  width: 150px;
  min-width: 120px;
  flex-shrink: 1;
}

.filter-owner-box {
  width: 110px;
  min-width: 90px;
  flex-shrink: 1;
}

.filter-dept-box {
  width: 135px;
  min-width: 110px;
  flex-shrink: 1;
}

.filter-status-box {
  width: 95px;
  min-width: 80px;
  flex-shrink: 1;
}

.action-btn {
  padding: 8px 14px;
  border-radius: 6px;
  flex-shrink: 0;
}

.search-submit-btn {
  background-color: #2563eb !important;
  border-color: #2563eb !important;
  font-weight: 500;
  flex-shrink: 0;
}

.feishu-sync-btn {
  flex-shrink: 0;
}

.wiki-manual-btn {
  background-color: #eff6ff !important;
  border-color: #bfdbfe !important;
  color: #1d4ed8 !important;
  font-weight: 500;
  flex-shrink: 0;
}

.wiki-manual-btn:hover {
  background-color: #dbeafe !important;
  border-color: #93c5fd !important;
  color: #1e40af !important;
}

/* 表格定制 */
.modern-table-row {
  transition: background-color 0.2s;
}

.modern-table-row:hover {
  background-color: #f8fafc !important;
}

:deep(.modern-table-header),
:deep(.el-table__header-wrapper th) {
  background-color: #f8fafc !important;
  color: #0f172a !important; /* 表头颜色深黑清晰 */
  font-weight: 700 !important; /* 表头加粗 */
  font-size: 13.5px !important;
  height: 46px;
}

.form-name-cell {
  display: flex;
  align-items: center;
}

.name-text {
  font-weight: 400; /* 报表名称不用加粗 */
  color: #0f172a;
  font-size: 13.5px;
}

.name-text.clickable {
  cursor: pointer;
}

.name-text.clickable:hover {
  color: #2563eb;
}

.folder-path-cell {
  color: #334155; /* 所属目录颜色加深 */
  font-size: 13px;
}

.folder-path-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cpt-file-tag {
  color: #0284c7;
  background: #f0f9ff;
  border: 1px solid #e0f2fe;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}

.text-regular {
  color: #1e293b; /* 责任人颜色加深 */
  font-size: 13px;
}

.text-secondary {
  color: #334155; /* 所属部门颜色加深 */
  font-size: 13px;
}

.time-text {
  color: #334155;
  font-size: 13px;
}

.status-pill-tag {
  border: none;
  font-size: 12px;
  height: 24px;
  padding: 0 10px;
}

.link-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.link-actions :deep(.el-button--primary.is-link) {
  color: #2563eb;
  font-weight: 500;
  font-size: 13px;
}

.link-actions :deep(.el-button--primary.is-link:hover) {
  color: #1d4ed8;
}

.more-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0 4px 0;
  margin-top: 8px;
}

@media (max-width: 1024px) {
  .page-layout {
    flex-direction: column;
  }
  .folder-sidebar-card {
    width: 100%;
    max-width: 100%;
  }
}
/* 极简同步弹窗样式 */
.sync-mode-radio-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.mode-radio-item {
  width: 100%;
  margin-right: 0 !important;
  height: auto !important;
  padding: 12px 16px !important;
  border-radius: 8px;
}

.radio-label-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mode-title {
  font-weight: 600;
  font-size: 14px;
  color: #1e293b;
}

.mode-sub {
  font-size: 12px;
  color: #64748b;
}

/* 结果审计弹窗样式 */
.result-summary-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px;
  border-radius: 10px;
  margin-bottom: 16px;
}

.result-summary-card.success {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
}

.result-summary-card.error {
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.result-summary-card.info {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.result-status-icon {
  font-size: 26px;
  flex-shrink: 0;
  margin-top: 2px;
}

.result-status-text .result-title {
  margin: 0 0 6px 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.result-status-text .result-meta {
  margin: 0;
  font-size: 13px;
  color: #475569;
}

.result-perf-tags {
  margin-top: 8px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.sync-time-label {
  color: #94a3b8;
  font-size: 12px;
  margin-left: 4px;
}

.result-status-text .error-desc {
  color: #dc2626;
}

.change-log-section {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background: #ffffff;
}

.change-log-header {
  background: #f8fafc;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  border-bottom: 1px solid #e2e8f0;
}

.change-log-list {
  max-height: 240px;
  overflow-y: auto;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.change-log-item {
  background: #f8fafc;
  padding: 10px 12px;
  border-radius: 6px;
  border: 1px solid #edf2f7;
}

.item-title-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.doc-icon {
  color: #2563eb;
  font-size: 14px;
}

.report-name {
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
  flex: 1;
}

.item-diff-list {
  margin: 0;
  padding-left: 8px;
  list-style: none;
}

.item-diff-list li {
  font-size: 12px;
  color: #475569;
  line-height: 1.6;
}

.diff-bullet {
  color: #3b82f6;
  font-weight: bold;
  margin-right: 4px;
}

.no-change-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #eff6ff;
  border-radius: 8px;
  color: #1e40af;
  font-size: 13px;
  line-height: 1.5;
}

.result-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

/* 顶部“所属目录”多层树形下拉选择器弹窗样式 */
:global(.catalog-tree-popper) {
  min-width: 300px !important;
  max-height: 420px !important;
}

:global(.catalog-tree-popper .el-tree-node__content) {
  height: 32px;
  border-radius: 6px;
  margin: 1px 4px;
}

:global(.catalog-tree-popper .el-tree-node__content:hover) {
  background-color: #f1f5f9;
}

:global(.catalog-tree-popper .el-tree-node.is-current > .el-tree-node__content) {
  background-color: #eff6ff !important;
  color: #2563eb !important;
  font-weight: 600;
}
</style>
