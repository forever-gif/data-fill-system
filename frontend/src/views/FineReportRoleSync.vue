<template>
  <div class="fr-role-sync-page">
    <!-- 顶部标题与快速操作 -->
    <div class="page-top-header">
      <div class="header-left">
        <h1 class="page-main-title">帆软用户角色批量同步</h1>
        <p class="page-sub-title">通过飞书电子表格与帆软 11.5.6 API 自动化批量比对与更新员工角色权限</p>
      </div>
      <div class="header-right">
        <el-button
          class="top-config-btn"
          @click="openHistoryDrawer"
        >
          <el-icon style="margin-right: 5px;"><Clock /></el-icon>
          历史快照与回滚
        </el-button>
        <el-button
          class="top-config-btn"
          @click="openConfigDialog"
        >
          <el-icon style="margin-right: 5px;"><Setting /></el-icon>
          帆软连接配置
        </el-button>
        <el-tag :type="frConnected ? 'success' : 'info'" effect="light" round class="status-indicator">
          {{ frConnected ? '帆软 11.5.6 已就绪' : '帆软服务未连接' }}
        </el-tag>
      </div>
    </div>

    <!-- 第一步：配置与读取卡片 -->
    <div class="main-card config-card">
      <div class="card-section-title">
        <span class="step-badge">1</span>
        <span>数据源与同步设置</span>
      </div>

      <div class="config-grid">
        <!-- 飞书表格链接 -->
        <div class="grid-item url-item">
          <label class="item-label">飞书表格链接 (Sheet URL)</label>
          <el-input
            v-model="feishuUrl"
            placeholder="粘贴包含角色权限的飞书电子表格链接，如 https://xxx.feishu.cn/sheets/..."
            clearable
            @change="handleUrlChange"
          >
            <template #prefix>
              <el-icon><Link /></el-icon>
            </template>
          </el-input>
        </div>

        <!-- 工作表选择 -->
        <div class="grid-item tab-item">
          <label class="item-label">选择工作表 (Sheet Tab)</label>
          <el-select
            v-model="selectedSheetId"
            placeholder="正在拉取工作表..."
            :loading="loadingTabs"
            style="width: 100%"
          >
            <el-option
              v-for="tab in sheetTabs"
              :key="tab.sheet_id"
              :label="tab.title"
              :value="tab.sheet_id"
            />
          </el-select>
        </div>

        <!-- 同步策略 -->
        <div class="grid-item mode-item">
          <label class="item-label">同步策略</label>
          <el-radio-group v-model="syncMode" class="sync-mode-group">
            <el-radio-button label="incremental">
              <el-tooltip content="保留员工当前已有的其他角色，只新增表格中的新角色（安全推荐）" placement="top">
                <span>增量追加</span>
              </el-tooltip>
            </el-radio-button>
            <el-radio-button label="overwrite">
              <el-tooltip content="以表格中配置的角色为唯一准绳，不在表格里的旧角色将被移除" placement="top">
                <span>完全覆盖</span>
              </el-tooltip>
            </el-radio-button>
          </el-radio-group>
        </div>

        <!-- 自动创建缺失角色开关 -->
        <div class="grid-item auto-create-item">
          <label class="item-label">缺失角色自动处理</label>
          <div class="switch-box">
            <el-switch v-model="autoCreateRoles" active-text="自动在帆软中创建新角色" />
          </div>
        </div>

        <!-- 操作按钮 -->
        <div class="grid-item action-item">
          <label class="item-label">&nbsp;</label>
          <el-button
            type="primary"
            class="fetch-preview-btn"
            :loading="previewLoading"
            @click="fetchAndPreview"
          >
            <el-icon style="margin-right: 5px;"><Search /></el-icon>
            读取并比对权限差异
          </el-button>
        </div>
      </div>
    </div>

    <!-- 第二步：差异 Diff 结果与确认表格 -->
    <div v-if="previewResult" class="main-card diff-card">
      <div class="table-header-toolbar">
        <div class="toolbar-left">
          <div class="card-section-title">
            <span class="step-badge">2</span>
            <span>权限变动明细预览 (工作表: {{ previewResult.sheetTitle || '默认' }})</span>
          </div>

          <!-- 统计徽标 -->
          <div class="stat-badges">
            <span class="badge-pill info">总计 {{ previewResult.totalRows }} 人</span>
            <span class="badge-pill primary">待更新 {{ previewResult.needUpdateCount }} 人</span>
            <span v-if="previewResult.missingRoleCount > 0" class="badge-pill warning">
              {{ previewResult.missingRoleCount }} 个角色未创建
            </span>
            <span v-if="previewResult.departmentChangedCount > 0" class="badge-pill dept-changed">
              <el-icon style="margin-right: 3px; vertical-align: -1px;"><OfficeBuilding /></el-icon>
              {{ previewResult.departmentChangedCount }} 人部门有变动
            </span>
            <span v-if="previewResult.detectedColumns" class="badge-pill detect-tip">
              已定位：角色在第 {{ (previewResult.detectedColumns.rolesCol || 0) + 1 }} 列 ({{ String.fromCharCode(65 + (previewResult.detectedColumns.rolesCol || 0)) }}列)
            </span>
          </div>
        </div>

        <div class="toolbar-right">
          <!-- 筛选单选 -->
          <el-radio-group v-model="statusFilter" size="small" style="margin-right: 12px;">
            <el-radio-button label="ALL">全部 ({{ previewResult.items.length }})</el-radio-button>
            <el-radio-button label="NEED_UPDATE">仅变动 ({{ needUpdateTotalCount }})</el-radio-button>
            <el-radio-button label="WARN">仅异常</el-radio-button>
          </el-radio-group>

          <!-- 关键字过滤 -->
          <el-input
            v-model="searchKeyword"
            placeholder="搜索姓名/工号/部门..."
            prefix-icon="Search"
            clearable
            size="small"
            style="width: 180px; margin-right: 12px;"
          />

          <!-- 批量执行按钮 -->
          <el-button
            type="success"
            size="default"
            class="batch-apply-btn"
            :disabled="selectedRows.length === 0"
            @click="confirmAndApply"
          >
            <el-icon style="margin-right: 4px;"><Check /></el-icon>
            一键同步到帆软 (已选 {{ selectedRows.length }} 人)
          </el-button>
        </div>
      </div>

      <!-- 差异对比表格 -->
      <el-table
        ref="diffTableRef"
        :data="filteredItems"
        row-key="username"
        style="width: 100%"
        border
        header-cell-class-name="modern-table-header"
        row-class-name="diff-table-row"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" align="center" :selectable="canSelectRow" />

        <el-table-column prop="username" label="工号 / 用户名" min-width="140" sortable>
          <template #default="{ row }">
            <span class="username-text">{{ row.username }}</span>
            <el-tag
              v-if="row.mergedRowCount > 1"
              size="small"
              type="warning"
              effect="plain"
              style="margin-left: 6px; font-size: 10.5px; height: 20px; padding: 0 4px;"
            >
              合并{{ row.mergedRowCount }}行
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="realName" label="姓名" width="100" />

        <!-- 所属部门 (包含变动原部门提示) -->
        <el-table-column label="所属部门" min-width="160">
          <template #default="{ row }">
            <div class="dept-cell-box">
              <span class="sheet-dept-text">{{ row.department || '-' }}</span>
              <el-tooltip
                v-if="row.departmentChanged"
                :content="row.currentFrDepartment ? `帆软当前所属部门为【${row.currentFrDepartment}】，与表格配置不同` : '帆软系统中该用户当前未设置部门'"
                placement="top"
              >
                <el-tag
                  type="warning"
                  effect="plain"
                  size="small"
                  class="dept-diff-badge"
                >
                  原: {{ row.currentFrDepartment || '无' }}
                </el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <!-- 帆软当前已有角色 -->
        <el-table-column label="帆软当前已有角色" min-width="200">
          <template #default="{ row }">
            <div v-if="row.currentFrRoles && row.currentFrRoles.length > 0" class="tag-pill-wrap">
              <el-tag
                v-for="r in row.currentFrRoles"
                :key="r"
                type="info"
                effect="plain"
                size="small"
                class="fr-role-tag"
              >
                {{ r }}
              </el-tag>
            </div>
            <span v-else class="empty-tip">暂无角色</span>
          </template>
        </el-table-column>

        <!-- 本次调整明细 (高亮区分新增、移除与部门变动) -->
        <el-table-column label="本次调整明细 (Diff)" min-width="340">
          <template #default="{ row }">
            <div class="diff-tag-container">
              <!-- 部门调整提示徽章 -->
              <el-tooltip
                v-if="row.departmentChanged"
                :content="`所属部门变动：由【${row.currentFrDepartment || '无'}】变更为【${row.department || '未配置'}】`"
                placement="top"
              >
                <el-tag
                  type="warning"
                  effect="light"
                  size="small"
                  class="diff-role-pill dept-change-pill"
                >
                  <el-icon style="margin-right: 2px; vertical-align: -1px;"><OfficeBuilding /></el-icon>
                  部门: {{ row.currentFrDepartment || '无' }} ➔ {{ row.department || '-' }}
                </el-tag>
              </el-tooltip>

              <!-- 新增角色 (绿) -->
              <el-tag
                v-for="r in row.addedRoles"
                :key="'add_' + r"
                type="success"
                effect="light"
                size="small"
                class="diff-role-pill add-pill"
              >
                + {{ r }}
              </el-tag>

              <!-- 保持不变 (灰) -->
              <el-tag
                v-for="r in row.keptRoles"
                :key="'keep_' + r"
                type="info"
                effect="plain"
                size="small"
                class="diff-role-pill keep-pill"
              >
                {{ r }}
              </el-tag>

              <!-- 移除角色 (红，仅覆盖模式) -->
              <el-tag
                v-for="r in row.removedRoles"
                :key="'rem_' + r"
                type="danger"
                effect="light"
                size="small"
                class="diff-role-pill remove-pill"
              >
                - {{ r }}
              </el-tag>

              <!-- 帆软缺失角色 (橙) -->
              <el-tag
                v-for="r in row.missingRoles"
                :key="'miss_' + r"
                type="warning"
                effect="dark"
                size="small"
                class="diff-role-pill missing-pill"
              >
                未建: {{ r }}
              </el-tag>

              <span v-if="row.addedRoles.length === 0 && row.removedRoles.length === 0 && row.keptRoles.length === 0 && !row.departmentChanged" class="empty-tip">
                无变动
              </span>
            </div>
          </template>
        </el-table-column>

        <!-- 预检与状态 -->
        <el-table-column label="预检状态" width="130" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.executed && row.success"
              type="success"
              effect="light"
              round
              size="small"
            >
              <el-icon style="margin-right: 2px; vertical-align: -1px;"><Check /></el-icon>
              已同步
            </el-tag>
            <el-tag
              v-else
              :type="getStatusTagType(row.status)"
              effect="light"
              round
              size="small"
            >
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 同步执行结果 -->
        <el-table-column label="执行状态" width="120" align="center">
          <template #default="{ row }">
            <template v-if="row.executed">
              <el-tag :type="row.success ? 'success' : 'danger'" effect="dark" size="small" round>
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
            <span v-else class="text-muted">待同步</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 帆软连接配置对话框 -->
    <el-dialog
      v-model="configDialogVisible"
      title="帆软决策平台 11.5.6 连接配置"
      width="520px"
      append-to-body
    >
      <el-form label-position="top" class="config-form">
        <el-form-item label="帆软决策系统访问地址 (Base URL)">
          <el-input
            v-model="frConfig.baseUrl"
            placeholder="例如 http://8.136.2.153:8081/webroot/decision"
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="管理员账号">
              <el-input v-model="frConfig.adminUsername" placeholder="如 admin" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="管理员密码">
              <el-input
                v-model="frConfig.adminPassword"
                type="password"
                show-password
                placeholder="请输入密码"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button
            type="info"
            link
            :loading="loadingDebug"
            style="margin-right: auto;"
            @click="openDebugDialog"
          >
            查看接口诊断
          </el-button>
          <el-button :loading="testingConnection" @click="testFrConnection">
            <el-icon style="margin-right: 4px;"><Connection /></el-icon>
            测试连通性
          </el-button>
          <el-button type="primary" :loading="savingConfig" @click="saveFrConfig">
            保存配置
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 接口诊断探测弹窗 -->
    <el-dialog
      v-model="debugDialogVisible"
      title="帆软 API 连通性深度诊断"
      width="780px"
      append-to-body
    >
      <div v-loading="loadingDebug" style="min-height: 200px;">
        <p style="font-size: 13px; color: #475569; margin-top: 0;">
          自动对帆软 11.5.6 决策平台的各个角色端点和测试用户进行多维度接口探测：
        </p>
        <el-input
          type="textarea"
          :rows="18"
          :model-value="debugOutput"
          readonly
          style="font-family: Consolas, monospace; font-size: 12px;"
        />
      </div>
    </el-dialog>

    <!-- 批量同步进度与结果弹窗 -->
    <el-dialog
      v-model="applyModalVisible"
      title="用户角色批量同步至帆软"
      width="580px"
      :close-on-click-modal="false"
      :show-close="!applying"
      append-to-body
    >
      <div class="apply-modal-content">
        <div class="progress-box">
          <el-progress
            :percentage="applyProgress"
            :status="applyProgress === 100 ? (applyResult.failCount > 0 ? 'warning' : 'success') : ''"
            :stroke-width="12"
          />
          <p class="progress-text">{{ applyStatusText }}</p>
        </div>

        <div v-if="!applying && applyResult" class="result-summary-box">
          <el-alert
            :title="`同步完成！成功更新 ${applyResult.successCount} 人，失败 ${applyResult.failCount} 人`"
            :type="applyResult.failCount === 0 ? 'success' : 'warning'"
            show-icon
            :closable="false"
          />
        </div>
      </div>

      <template #footer>
        <el-button :disabled="applying" @click="applyModalVisible = false">关 闭</el-button>
        <el-button v-if="!applying" type="primary" @click="applyModalVisible = false">
          完成并查看列表
        </el-button>
      </template>
    </el-dialog>

    <!-- 🌟 优化点 5：历史快照与安全回滚底册抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      title="帆软角色同步历史快照与安全回滚底册"
      size="720px"
      append-to-body
    >
      <div class="history-drawer-content">
        <div class="drawer-header-tip">
          <el-alert
            title="每次执行同步前，系统会自动对所有变动员工的原始角色进行不可篡改快照备份。如遇误操作，可随时点击一键回滚恢复！"
            type="info"
            :closable="false"
            show-icon
          />
        </div>

        <el-table
          v-loading="loadingHistory"
          :data="historyList"
          border
          style="width: 100%; margin-top: 16px;"
          header-cell-class-name="modern-table-header"
        >
          <el-table-column prop="createTime" label="同步时间" width="160" />
          <el-table-column prop="sourceTitle" label="来源工作表" min-width="120" show-overflow-tooltip />
          <el-table-column prop="syncMode" label="模式" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.syncMode === 'overwrite' ? 'warning' : 'success'">
                {{ row.syncMode === 'overwrite' ? '完全覆盖' : '增量追加' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="影响人数" width="115" align="center">
            <template #default="{ row }">
              <span style="color: #059669; font-weight: 600;">{{ row.successCount }} 成功</span>
              <span v-if="row.failCount > 0" style="color: #dc2626; margin-left: 4px;">({{ row.failCount }} 败)</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="95" align="center">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 'ROLLED_BACK' ? 'danger' : 'success'"
                effect="light"
                size="small"
                round
              >
                {{ row.status === 'ROLLED_BACK' ? '已回滚' : '已生效' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status !== 'ROLLED_BACK'"
                type="danger"
                link
                size="small"
                :loading="rollingBackId === row.id"
                @click="handleRollback(row)"
              >
                <el-icon style="margin-right: 2px;"><RefreshLeft /></el-icon>
                一键回滚
              </el-button>
              <span v-else class="text-muted" style="font-size: 11.5px;">已撤销</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import {
  Setting, Link, Search, Check, Connection, Clock, RefreshLeft, CircleCheckFilled, OfficeBuilding
} from '@element-plus/icons-vue'

// 飞书配置与数据源
const feishuUrl = ref('')
const sheetTabs = ref([])
const selectedSheetId = ref('')
const loadingTabs = ref(false)
const syncMode = ref('incremental')
const autoCreateRoles = ref(false)

// 🌟 优化点 5：历史快照与回滚
const historyDrawerVisible = ref(false)
const loadingHistory = ref(false)
const historyList = ref([])
const rollingBackId = ref('')

// 差异比对
const previewLoading = ref(false)
const previewResult = ref(null)
const selectedRows = ref([])
const statusFilter = ref('ALL')
const searchKeyword = ref('')
const diffTableRef = ref(null)

// 变动总数（包含角色变动与部门变动）
const needUpdateTotalCount = computed(() => {
  if (!previewResult.value || !previewResult.value.items) return 0
  return previewResult.value.items.filter(i =>
    i.status === 'NORMAL' || i.status === 'ROLE_NOT_FOUND' || i.departmentChanged
  ).length
})

// 帆软连接配置
const frConnected = ref(false)
const configDialogVisible = ref(false)
const testingConnection = ref(false)
const savingConfig = ref(false)
const frConfig = ref({
  baseUrl: 'http://8.136.2.153:8081/webroot/decision',
  adminUsername: 'admin',
  adminPassword: ''
})

// 执行同步
const applyModalVisible = ref(false)
const applying = ref(false)
const applyProgress = ref(0)
const applyStatusText = ref('')
const applyResult = ref(null)

// 过滤后的明细列表
const filteredItems = computed(() => {
  if (!previewResult.value || !previewResult.value.items) return []
  let list = previewResult.value.items

  if (statusFilter.value === 'NEED_UPDATE') {
    list = list.filter(i => i.status === 'NORMAL' || i.status === 'ROLE_NOT_FOUND' || i.departmentChanged)
  } else if (statusFilter.value === 'WARN') {
    list = list.filter(i => i.status === 'USER_NOT_FOUND' || i.status === 'ROLE_NOT_FOUND')
  }

  if (searchKeyword.value.trim()) {
    const q = searchKeyword.value.trim().toLowerCase()
    list = list.filter(i =>
      (i.username && i.username.toLowerCase().includes(q)) ||
      (i.realName && i.realName.toLowerCase().includes(q)) ||
      (i.department && i.department.toLowerCase().includes(q))
    )
  }

  return list
})

const canSelectRow = (row) => {
  return row.status !== 'USER_NOT_FOUND'
}

const handleSelectionChange = (rows) => {
  selectedRows.value = rows
}

// 监听飞书表格链接输入，自动提取子工作表 Tabs
const handleUrlChange = async () => {
  if (!feishuUrl.value.trim()) {
    sheetTabs.value = []
    selectedSheetId.value = ''
    return
  }
  loadingTabs.value = true
  try {
    const res = await axios.post('/api/finereport/roles/sheets', { feishuUrl: feishuUrl.value })
    if (res.data.success && Array.isArray(res.data.sheets)) {
      sheetTabs.value = res.data.sheets
      if (sheetTabs.value.length > 0) {
        selectedSheetId.value = sheetTabs.value[0].sheet_id
      }
    } else {
      ElMessage.warning(res.data.message || '无法获取工作表列表，将默认读取第一张工作表')
    }
  } catch (e) {
    ElMessage.warning('未能读取工作表，将直接尝试解析')
  } finally {
    loadingTabs.value = false
  }
}

// 读取并比对差异
const fetchAndPreview = async () => {
  if (!feishuUrl.value.trim()) {
    ElMessage.warning('请先输入飞书电子表格链接')
    return
  }
  previewLoading.value = true
  try {
    const res = await axios.post('/api/finereport/roles/preview', {
      feishuUrl: feishuUrl.value,
      sheetId: selectedSheetId.value,
      syncMode: syncMode.value
    })
    if (res.data.success) {
      previewResult.value = res.data.data
      if (res.data.data.sheetTabs && res.data.data.sheetTabs.length > 0) {
        sheetTabs.value = res.data.data.sheetTabs
        selectedSheetId.value = res.data.data.sheetId
      }
      // 默认全选所有待变动用户
      setTimeout(() => {
        if (diffTableRef.value) {
          diffTableRef.value.clearSelection()
          filteredItems.value.forEach(row => {
            if (row.status === 'NORMAL' || row.status === 'ROLE_NOT_FOUND' || row.departmentChanged) {
              diffTableRef.value.toggleRowSelection(row, true)
            }
          })
        }
      }, 100)
      const deptMsg = previewResult.value.departmentChangedCount > 0 ? `（含 ${previewResult.value.departmentChangedCount} 人部门变动）` : ''
      ElMessage.success(`读取完成！共解析 ${previewResult.value.totalRows} 位员工，待变动 ${needUpdateTotalCount.value} 人${deptMsg}`)
    } else {
      ElMessage.error(res.data.message || '比对失败')
    }
  } catch (e) {
    ElMessage.error('比对请求失败: ' + (e.response?.data?.message || e.message))
  } finally {
    previewLoading.value = false
  }
}

// 确认并批量同步到帆软
const confirmAndApply = () => {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请勾选需要同步的员工')
    return
  }

  const modeText = syncMode.value === 'overwrite' ? '【全量覆盖（将移除未配置旧角色）】' : '【增量追加（安全保留旧角色）】'
  ElMessageBox.confirm(
    `即将批量更新选中的 ${selectedRows.value.length} 位员工角色权限，同步模式为：${modeText}。确定继续吗？`,
    '执行同步确认',
    {
      confirmButtonText: '确定同步',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(() => {
    executeApply()
  }).catch(() => {})
}

// 执行接口同步
const executeApply = async () => {
  applyModalVisible.value = true
  applying.value = true
  applyProgress.value = 20
  applyStatusText.value = `正在向帆软 11.5.6 批量写入 ${selectedRows.value.length} 位员工的角色...`

  try {
    const payload = {
      syncMode: syncMode.value,
      autoCreateMissingRoles: autoCreateRoles.value,
      sourceTitle: (previewResult.value && previewResult.value.sheetTitle) ? previewResult.value.sheetTitle : '飞书电子表格',
      items: selectedRows.value
    }
    applyProgress.value = 50

    const res = await axios.post('/api/finereport/roles/apply', payload)
    applyProgress.value = 100

    if (res.data.success) {
      applyResult.value = res.data.data
      applyStatusText.value = '同步完成！'
      ElMessage.success(res.data.message || '批量更新完成')

      // 回填执行结果至页面表格
      const detailMap = new Map()
      ;(res.data.data.details || []).forEach(d => {
        detailMap.set(d.username, d)
      })

      previewResult.value.items.forEach(item => {
        if (detailMap.has(item.username)) {
          const updated = detailMap.get(item.username)
          item.executed = true
          item.success = updated.success
          item.executeMsg = updated.executeMsg
        }
      })
    } else {
      applyStatusText.value = '同步失败: ' + (res.data.message || '未知异常')
      ElMessage.error(res.data.message || '执行失败')
    }
  } catch (e) {
    applyStatusText.value = '请求异常: ' + (e.response?.data?.message || e.message)
    ElMessage.error('同步异常: ' + e.message)
  } finally {
    applying.value = false
  }
}

// 帆软配置与连通性
const loadFrConfig = async () => {
  try {
    const res = await axios.get('/api/finereport/roles/config')
    if (res.data) {
      frConfig.value.baseUrl = res.data.baseUrl || frConfig.value.baseUrl
      frConfig.value.adminUsername = res.data.adminUsername || 'admin'
    }
  } catch (e) {}
}

const checkFrConnectionQuietly = async () => {
  try {
    const res = await axios.post('/api/finereport/roles/test-connect')
    frConnected.value = res.data.success === true
  } catch (e) {
    frConnected.value = false
  }
}

const openConfigDialog = () => {
  configDialogVisible.value = true
}

const testFrConnection = async () => {
  testingConnection.value = true
  try {
    // 先暂存到后台再测试
    await axios.post('/api/finereport/roles/config', frConfig.value)
    const res = await axios.post('/api/finereport/roles/test-connect')
    if (res.data.success) {
      frConnected.value = true
      ElMessage.success(res.data.message || '连通成功！')
    } else {
      frConnected.value = false
      ElMessage.error(res.data.message || '连通失败，请核对地址与账密')
    }
  } catch (e) {
    ElMessage.error('连接请求异常: ' + e.message)
  } finally {
    testingConnection.value = false
  }
}

const debugDialogVisible = ref(false)
const loadingDebug = ref(false)
const debugOutput = ref('')

const openDebugDialog = async () => {
  debugDialogVisible.value = true
  loadingDebug.value = true
  try {
    const res = await axios.get('/api/finereport/roles/debug?username=donghao-he')
    debugOutput.value = JSON.stringify(res.data, null, 2)
  } catch (e) {
    debugOutput.value = '诊断接口调用失败: ' + (e.response?.data?.message || e.message)
  } finally {
    loadingDebug.value = false
  }
}

const saveFrConfig = async () => {
  savingConfig.value = true
  try {
    await axios.post('/api/finereport/roles/config', frConfig.value)
    ElMessage.success('配置已保存')
    configDialogVisible.value = false
    checkFrConnectionQuietly()
  } catch (e) {
    ElMessage.error('保存失败: ' + e.message)
  } finally {
    savingConfig.value = false
  }
}

// 标签样式辅助方法
const getStatusTagType = (st) => {
  if (st === 'NORMAL') return 'success'
  if (st === 'NO_CHANGE') return 'info'
  if (st === 'ROLE_NOT_FOUND') return 'warning'
  if (st === 'USER_NOT_FOUND') return 'danger'
  return 'info'
}

const getStatusLabel = (st) => {
  if (st === 'NORMAL') return '待更新'
  if (st === 'NO_CHANGE') return '无需更新'
  if (st === 'ROLE_NOT_FOUND') return '存在缺失角色'
  if (st === 'USER_NOT_FOUND') return '用户未创建'
  return st
}

// 🌟 优化点 5：历史快照与安全回滚方法
const openHistoryDrawer = () => {
  historyDrawerVisible.value = true
  loadHistory()
}

const loadHistory = async () => {
  loadingHistory.value = true
  try {
    const res = await axios.get('/api/finereport/roles/history?limit=30')
    if (res.data && res.data.success) {
      historyList.value = res.data.data || []
    }
  } catch (e) {
    ElMessage.error('获取历史快照失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loadingHistory.value = false
  }
}

const handleRollback = (row) => {
  ElMessageBox.confirm(
    `确定要将批次【${row.batchNo}】(${row.sourceTitle}) 同步的 ${row.successCount} 位员工角色一键回滚还原到同步前的状态吗？此操作不可逆！`,
    '高风险操作：安全回滚确认',
    {
      confirmButtonText: '立即回滚',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    rollingBackId.value = row.id
    try {
      const res = await axios.post(`/api/finereport/roles/rollback/${row.id}`)
      if (res.data && res.data.success) {
        ElMessage.success(res.data.message || '回滚完成！')
        loadHistory()
        if (previewResult.value) {
          fetchAndPreview()
        }
      } else {
        ElMessage.error(res.data?.message || '回滚失败')
      }
    } catch (e) {
      ElMessage.error('回滚请求失败: ' + (e.response?.data?.message || e.message))
    } finally {
      rollingBackId.value = ''
    }
  }).catch(() => {})
}

onMounted(() => {
  loadFrConfig()
  checkFrConnectionQuietly()
})
</script>

<style scoped>
.fr-role-sync-page {
  width: 100%;
  padding: 0 4px;
  box-sizing: border-box;
}

.page-top-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
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

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.main-card {
  background: white;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
  padding: 20px;
  box-sizing: border-box;
  margin-bottom: 18px;
}

.card-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 16px;
}

.step-badge {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #2563eb;
  color: white;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}

.config-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
}

.item-label {
  display: block;
  font-size: 12.5px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 6px;
}

.url-item {
  flex: 3;
  min-width: 300px;
}

.tab-item {
  flex: 1.5;
  min-width: 180px;
}

.mode-item {
  flex-shrink: 0;
}

.auto-create-item {
  flex-shrink: 0;
}

.switch-box {
  height: 32px;
  display: flex;
  align-items: center;
}

.action-item {
  flex-shrink: 0;
}

.fetch-preview-btn {
  background-color: #2563eb !important;
  border-color: #2563eb !important;
  padding: 8px 18px;
  font-weight: 600;
}

/* 差异对比卡片与工具栏 */
.table-header-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.toolbar-left .card-section-title {
  margin-bottom: 0;
}

.stat-badges {
  display: flex;
  align-items: center;
  gap: 8px;
}

.badge-pill {
  font-size: 12px;
  padding: 3px 8px;
  border-radius: 6px;
  font-weight: 600;
}

.badge-pill.info {
  background: #f1f5f9;
  color: #475569;
}

.badge-pill.primary {
  background: #eff6ff;
  color: #2563eb;
}

.badge-pill.warning {
  background: #fffbeb;
  color: #d97706;
}

.badge-pill.detect-tip {
  background: #f0fdf4;
  color: #15803d;
  border: 1px solid #bbf7d0;
}

.toolbar-right {
  display: flex;
  align-items: center;
}

.batch-apply-btn {
  font-weight: 600;
}

/* 表格定制 */
:deep(.modern-table-header th) {
  background-color: #f8fafc !important;
  color: #0f172a !important;
  font-weight: 700 !important;
  font-size: 13px !important;
  height: 44px;
}

.username-text {
  font-weight: 600;
  color: #1e293b;
}

.tag-pill-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.fr-role-tag {
  border-radius: 4px;
  font-size: 11.5px;
}

.diff-tag-container {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
}

.diff-role-pill {
  border-radius: 4px;
  font-size: 11.5px;
  font-weight: 500;
}

.add-pill {
  background-color: #ecfdf5 !important;
  border-color: #a7f3d0 !important;
  color: #059669 !important;
}

.remove-pill {
  background-color: #fef2f2 !important;
  border-color: #fecaca !important;
  color: #dc2626 !important;
  text-decoration: line-through;
}

.missing-pill {
  border-radius: 4px;
}

.empty-tip {
  color: #94a3b8;
  font-size: 12px;
}

.text-muted {
  color: #94a3b8;
  font-size: 12px;
}

.apply-modal-content {
  padding: 10px 0;
}

.progress-box {
  margin-bottom: 20px;
}

.progress-text {
  margin-top: 12px;
  font-size: 13px;
  color: #475569;
  text-align: center;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.history-drawer-content {
  padding: 4px 0;
}

.drawer-header-tip {
  margin-bottom: 12px;
}

/* 部门变动明细定制样式 */
.dept-cell-box {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.sheet-dept-text {
  font-weight: 600;
  color: #1e293b;
  font-size: 13px;
}

.dept-diff-badge {
  font-size: 10.5px;
  height: 19px;
  line-height: 17px;
  padding: 0 5px;
  border-radius: 4px;
  background-color: #fffbeb;
  border-color: #fde68a;
  color: #b45309;
}

.dept-change-pill {
  background-color: #fffbeb !important;
  border-color: #fde68a !important;
  color: #b45309 !important;
  font-weight: 600;
}

.badge-pill.dept-changed {
  background: #fefce8;
  color: #b45309;
  border: 1px solid #fde047;
}
</style>
