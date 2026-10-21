<template>
  <div class="form-list-page">
    <div class="page-layout">
      <!-- 左侧目录侧边栏 (1:1 像素级对齐报表资产) -->
      <div class="folder-sidebar-card">
        <!-- 顶部轻量级标题区 (支持新建目录) -->
        <div class="folder-header-clean">
          <span class="folder-header-title">模板目录</span>
          <el-button v-if="isAdmin" type="primary" link icon="Plus" class="add-folder-btn" @click="createFolder()">新建</el-button>
        </div>

        <div class="folder-tree-panel" v-loading="folderLoading">
          <!-- 全部模板 根节点 (纯净轻蓝胶囊，无臃肿数字气泡) -->
          <div
            class="folder-all-item"
            :class="{ active: !selectedFolderId }"
            @click="selectAllFolders"
          >
            <el-icon class="folder-root-icon"><Folder /></el-icon>
            <span class="folder-root-name">全部模板</span>
          </div>

          <!-- 优雅目录树 (纯净无数字气泡，悬停显操作按钮) -->
          <el-tree
            v-if="folderTree.length > 0"
            ref="folderTreeRef"
            :data="folderTree"
            node-key="id"
            highlight-current
            :draggable="isAdmin"
            :expand-on-click-node="false"
            :props="{ label: 'name', children: 'children' }"
            :current-node-key="selectedFolderId || undefined"
            :allow-drop="allowFolderDrop"
            class="custom-report-tree"
            @node-click="handleFolderSelect"
            @node-contextmenu="handleFolderContextMenu"
            @node-drop="handleFolderDrop"
          >
            <template #default="{ node, data }">
              <div class="tree-node-row">
                <div class="tree-node-left">
                  <el-icon class="node-folder-icon">
                    <component :is="node.expanded ? 'FolderOpened' : 'Folder'" />
                  </el-icon>
                  <el-tooltip :content="data.name" placement="top" :show-after="300" :disabled="!data.name">
                    <span class="node-title-text">{{ data.name }}</span>
                  </el-tooltip>
                </div>
                <div v-if="isAdmin && !data.systemNode" class="folder-node-actions" @click.stop>
                  <el-dropdown trigger="click" @command="(cmd) => handleFolderCommand(cmd, data)">
                    <el-button link class="folder-more-btn" title="更多操作">
                      <el-icon><MoreFilled /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="create" :icon="Plus">新增子目录</el-dropdown-item>
                        <el-dropdown-item command="rename" :icon="Edit">重命名</el-dropdown-item>
                        <el-dropdown-item command="delete" :icon="Delete" divided style="color: #ef4444;">删除目录</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
            </template>
          </el-tree>

          <el-empty v-else-if="!folderLoading" description="暂无目录，模板将归入默认" :image-size="72" />
        </div>

        <div
          v-if="contextMenu.visible"
          class="folder-context-menu"
          :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        >
          <button class="context-menu-item" @click="handleContextMenuAction('create')">新建子目录</button>
          <button class="context-menu-item" @click="handleContextMenuAction('rename')">重命名目录</button>
          <button class="context-menu-item danger" @click="handleContextMenuAction('delete')">删除目录</button>
        </div>

        <!-- 宽度拖拽调节手柄 -->
        <div class="sidebar-resizer" @mousedown="startSidebarResize"></div>
      </div>

      <!-- 右侧主内容卡片面板 (1:1 像素级对齐报表资产) -->
      <div class="content-panel">
        <div class="main-table-card">
          <!-- 顶部搜索与多维筛选栏 (两端排版：搜索条件靠左，按钮操作靠右) -->
          <div class="table-search-bar">
            <!-- 左侧：当前目录名称 + 模板数量徽标 -->
            <div class="view-title-area">
              <span class="view-title">{{ selectedFolderLabel || '全部模板' }}</span>
              <el-tag type="primary" effect="plain" round size="small" class="count-tag">
                共 {{ filteredForms.length }} 个模板
              </el-tag>
            </div>

            <!-- 右侧：紧凑完整的搜索与批量操作栏 -->
            <div class="search-bar-right">
              <el-input
                v-model="searchQuery"
                placeholder="搜索模板名称..."
                prefix-icon="Search"
                clearable
                class="search-input-box"
                @keyup.enter="handleFilter"
                @clear="handleFilter"
              />
              <el-select v-model="statusFilter" placeholder="状态" clearable class="filter-status-box">
                <el-option label="所有状态" value="" />
                <el-option label="运行中" value="ACTIVE" />
                <el-option label="已过期" value="EXPIRED" />
                <el-option label="待发布" value="DISABLED" />
              </el-select>
              <el-button type="primary" class="action-btn search-submit-btn" @click="handleFilter">查询</el-button>
              <el-button class="action-btn" @click="resetFilter">重置</el-button>
              <div v-if="isAdmin" class="divider"></div>
              <el-button
                v-if="isAdmin"
                type="warning"
                plain
                class="action-btn batch-move-btn"
                @click="openBatchMoveDialog"
              >
                <el-icon class="el-icon--left"><Rank /></el-icon> 批量移动<template v-if="selectedFormIds.length > 0"> ({{ selectedFormIds.length }})</template>
              </el-button>
            </div>
          </div>

          <!-- 数据表格 -->
          <el-table
            :data="paginatedForms"
            style="width: 100%"
            v-loading="loading"
            @selection-change="handleSelectionChange"
            row-class-name="modern-table-row"
            header-cell-class-name="modern-table-header"
          >
            <el-table-column v-if="isAdmin" type="selection" width="48" align="center" />
            <el-table-column prop="name" label="模板名称" min-width="280" show-overflow-tooltip>
              <template #default="scope">
                <div class="form-name-cell">
                  <div class="icon-avatar">
                    <el-icon><Document /></el-icon>
                  </div>
                  <span class="name-text">{{ scope.row.name }}</span>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column prop="folderId" label="所属目录" min-width="200" show-overflow-tooltip>
              <template #default="scope">
                <div class="folder-path-cell">
                  <el-icon size="12"><Folder /></el-icon>
                  <span class="folder-path-text">{{ resolveFolderPath(scope.row.folderId) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="tableName" label="数据库物理表名" min-width="220" show-overflow-tooltip>
              <template #default="scope">
                <span
                  class="table-code"
                  :title="'点击复制: ' + formatFullTableName(scope.row)"
                  style="cursor: pointer;"
                  @click="copyTableName(formatFullTableName(scope.row))"
                >
                  {{ formatFullTableName(scope.row) }}
                </span>
              </template>
            </el-table-column>
  
            <el-table-column prop="status" label="状态" width="110" align="center">
              <template #default="scope">
                <el-tag
                  :type="scope.row.status === 'ACTIVE' ? 'success' : (scope.row.status === 'EXPIRED' ? 'danger' : 'info')"
                  effect="light"
                  round
                  size="small"
                  class="status-tag"
                >
                  {{ scope.row.status === 'ACTIVE' ? '运行中' : (scope.row.status === 'EXPIRED' ? '已过期' : '待发布') }}
                </el-tag>
              </template>
            </el-table-column>
  
            <el-table-column prop="deadline" label="截止时间" min-width="170">
              <template #default="scope">
                <div class="time-cell">
                  <el-icon v-if="scope.row.deadline" size="14"><Timer /></el-icon>
                  <span>{{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleDateString() : '长期有效' }}</span>
                </div>
              </template>
            </el-table-column>
  
            <el-table-column prop="creator" label="创建人" min-width="130" show-overflow-tooltip>
              <template #default="scope">
                <span class="creator-text">{{ scope.row.creator || 'admin' }}</span>
              </template>
            </el-table-column>
  
            <el-table-column label="操作" width="160" align="right" fixed="right">
              <template #default="scope">
                <div class="action-cell">
                  <el-tooltip content="查看/填写数据" placement="top">
                    <el-button circle size="small" type="primary" plain icon="List" @click="$router.push({ path: `/fill/${scope.row.id}`, query: { admin: 'true', ...$route.query } })" />
                  </el-tooltip>
                  <el-tooltip v-if="isAdmin" content="设计模板" placement="top">
                    <el-button circle size="small" type="success" plain icon="Edit" @click="$router.push({ path: `/designer/${scope.row.id}`, query: $route.query })" />
                  </el-tooltip>
                  
                  <el-dropdown v-if="isAdmin" trigger="click">
                    <el-button circle size="small" type="info" plain icon="MoreFilled" />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item icon="Rank" @click="openMoveDialog(scope.row)">移动目录</el-dropdown-item>
                        <el-dropdown-item divided icon="Delete" @click="handleDeleteForm(scope.row)" style="color: #f56c6c">删除模板</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next, jumper"
              :total="filteredForms.length"
              @size-change="handlePaginationChange"
              @current-change="handlePaginationChange"
            />
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="moveDialogVisible" title="移动模板目录" width="460px">
      <div class="move-dialog-body">
        <div class="move-form-name">{{ movingForm?.name }}</div>
        <el-select
          v-model="moveTargetFolderId"
          clearable
          filterable
          placeholder="选择目标目录，不选则归入默认"
          style="width: 100%"
        >
          <el-option label="默认" value="" />
          <el-option
            v-for="item in folderOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="moveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMoveForm">确认移动</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchMoveDialogVisible" title="批量移动模板目录" width="460px">
      <div class="move-dialog-body">
        <div class="move-form-name">已选择 {{ selectedFormIds.length }} 个模板</div>
        <el-select
          v-model="batchMoveTargetFolderId"
          clearable
          filterable
          placeholder="选择目标目录，不选则归入默认"
          style="width: 100%"
        >
          <el-option label="默认" value="" />
          <el-option
            v-for="item in folderOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="batchMoveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmBatchMove">确认移动</el-button>
      </template>
    </el-dialog>
 
    <!-- 彻底销毁确认弹窗 -->
    <el-dialog v-model="deleteDialogVisible" title="确认删除模板" width="420px" class="delete-dialog">
      <div class="delete-dialog-body">
        <div class="delete-warning">
          <el-icon class="warning-icon"><WarningFilled /></el-icon>
          <div class="warning-text">确定要删除模板“{{ deletingForm?.name }}”吗？</div>
        </div>
        <div class="delete-hint">删除后，该模板将从列表中移除，用户无法再填报。</div>
        
        <div v-if="!deletingForm?.isExternal" class="delete-option-area">
          <el-checkbox v-model="shouldDropTable">
            <span class="option-label">同时彻底销毁物理表及所有数据</span>
          </el-checkbox>
          <div class="option-desc">勾选后执行 DROP TABLE，数据将不可恢复。不勾选则仅执行重命名备份。</div>
        </div>
        <div v-else class="delete-info-area">
          <el-icon><InfoFilled /></el-icon>
          <span>当前为外部绑定表，删除模板不会影响原始表。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleteLoading" @click="confirmDeleteForm">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, inject, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document, Folder, FolderOpened, Timer, User, WarningFilled,
  InfoFilled, Menu, Box, Plus, Edit, Delete, Rank, List, MoreFilled, Search
} from '@element-plus/icons-vue'
import axios from 'axios'
const route = useRoute()
const router = useRouter()
const currentUser = inject('currentUser', ref(''))
const isAdmin = inject('isAdmin', ref(false))

const forms = ref([])
const loading = ref(false)
const folderTreeRef = ref(null)
const folderTree = ref([])
const folderLoading = ref(false)
const selectedFolderId = ref(route.query.folderId || '')
const searchQuery = ref('')
const statusFilter = ref('')
const appliedSearchQuery = ref('')
const appliedStatusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const moveDialogVisible = ref(false)
const batchMoveDialogVisible = ref(false)
const movingForm = ref(null)
const moveTargetFolderId = ref('')
const batchMoveTargetFolderId = ref('')
const selectedFormIds = ref([])
const deleteDialogVisible = ref(false)
const deleteLoading = ref(false)
const deletingForm = ref(null)
const shouldDropTable = ref(false)
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  folder: null
})

// 侧边栏宽度动态调节逻辑
const sidebarWidth = ref(150) // 极致紧凑宽度 (从 200px 进一步收窄至 150px，彻底消除空白)
const isResizing = ref(false) // 是否正在调整大小

// 鼠标按下缩放条时触发
const startSidebarResize = (e) => {
  isResizing.value = true
  // 监听全局鼠标移动和松开事件
  document.addEventListener('mousemove', handleSidebarResize)
  document.addEventListener('mouseup', stopSidebarResize)
  // 锁定光标样式并禁用文字选择
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

// 鼠标移动时计算新宽度
const handleSidebarResize = (e) => {
  if (!isResizing.value) return
  // 计算鼠标当前位置相对于页面的宽度（减去可能的左侧偏移量）
  const newWidth = e.clientX - 24 
  // 限制宽度范围：最小 120px，最大 350px
  if (newWidth >= 120 && newWidth <= 350) {
    sidebarWidth.value = newWidth
  }
}

// 鼠标松开时移除监听，恢复页面状态
const stopSidebarResize = () => {
  isResizing.value = false
  document.removeEventListener('mousemove', handleSidebarResize)
  document.removeEventListener('mouseup', stopSidebarResize)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

const filteredForms = computed(() => {
  const keyword = appliedSearchQuery.value.trim().toLowerCase()
  return forms.value.filter(form => {
    const fullTable = formatFullTableName(form).toLowerCase()
    const matchesSearch = !keyword ||
      (form.name || '').toLowerCase().includes(keyword) ||
      (form.tableName || '').toLowerCase().includes(keyword) ||
      fullTable.includes(keyword)
    const matchesStatus = !appliedStatusFilter.value || form.status === appliedStatusFilter.value
    return matchesSearch && matchesStatus
  })
})

const paginatedForms = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredForms.value.slice(start, end)
})

const folderPathMap = computed(() => {
  const map = {}
  const walk = (nodes, parents = []) => {
    ;(nodes || []).forEach(node => {
      const nextParents = [...parents, node.name]
      map[node.id] = nextParents
      walk(node.children, nextParents)
    })
  }
  walk(folderTree.value)
  return map
})

const folderOptions = computed(() => {
  const options = []
  Object.entries(folderPathMap.value).forEach(([id, segments]) => {
    if (id === '__uncategorized__') return
    options.push({ value: id, label: segments.join(' / ') })
  })
  return options
})

const selectedFolderCrumbs = computed(() => {
  if (!selectedFolderId.value) return []
  const segments = folderPathMap.value[selectedFolderId.value] || ['默认']
  return segments.map((name, index) => ({
    id: index === segments.length - 1 ? selectedFolderId.value : findFolderIdByPath(segments.slice(0, index + 1)),
    name
  })).filter(item => item.id)
})

const selectedFolderLabel = computed(() => {
  if (!selectedFolderId.value) return ''
  if (selectedFolderId.value === '__uncategorized__') return '默认'
  return (folderPathMap.value[selectedFolderId.value] || []).join(' / ')
})

const allTemplateCount = computed(() => {
  return folderTree.value.reduce((sum, node) => sum + (node.templateCount || 0), 0)
})

const updateQueryParams = async (overrideFolderId) => {
  const query = { ...route.query }
  const folderIdToUse = overrideFolderId !== undefined ? overrideFolderId : selectedFolderId.value
  if (folderIdToUse) {
    query.folderId = folderIdToUse
  } else {
    delete query.folderId
  }
  if (appliedSearchQuery.value) {
    query.search = appliedSearchQuery.value
  } else {
    delete query.search
  }
  if (appliedStatusFilter.value) {
    query.status = appliedStatusFilter.value
  } else {
    delete query.status
  }
  await router.replace({ query })
}

const updateRouteFolder = async (folderId) => {
  await updateQueryParams(folderId)
}

const findFolderIdByPath = (segments) => {
  const pathText = segments.join(' / ')
  const target = folderOptions.value.find(item => item.label === pathText)
  return target?.value || ''
}

const resolveFolderPath = (folderId) => {
  if (!folderId) return '默认'
  const segments = folderPathMap.value[folderId]
  return segments && segments.length > 0 ? segments.join(' / ') : '默认'
}

const formatFullTableName = (row) => {
  if (!row || !row.tableName) return '-'
  if (row.tableName.includes('.')) return row.tableName
  return row.schemaName ? `${row.schemaName}.${row.tableName}` : row.tableName
}

const copyTableName = async (text) => {
  if (!text || text === '-') return
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const input = document.createElement('textarea')
      input.value = text
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success({ message: `已复制表名: ${text}`, duration: 1500 })
  } catch (e) {
    ElMessage.info(`表名: ${text}`)
  }
}

const handlePaginationChange = () => {}

const handleSelectionChange = (rows) => {
  selectedFormIds.value = (rows || []).map(row => row.id)
}

const hideContextMenu = () => {
  contextMenu.value = {
    visible: false,
    x: 0,
    y: 0,
    folder: null
  }
}

const resetFilter = async () => {
  searchQuery.value = ''
  statusFilter.value = ''
  appliedSearchQuery.value = ''
  appliedStatusFilter.value = ''
  currentPage.value = 1
  selectedFolderId.value = ''
  await updateQueryParams('')
  await loadForms()
}

const handleFilter = async () => {
  appliedSearchQuery.value = searchQuery.value
  appliedStatusFilter.value = statusFilter.value
  currentPage.value = 1
  await updateQueryParams()
  await loadForms()
}

const loadFolders = async () => {
  folderLoading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    if (route.query.groupTag) params.groupTag = route.query.groupTag
    const res = await axios.get('/api/fill/folders/tree', { params })
    folderTree.value = res.data || []
  } catch (e) {
    folderTree.value = []
    ElMessage.error(e.response?.data?.message || '获取目录失败')
  } finally {
    folderLoading.value = false
  }
}

const loadForms = async () => {
  loading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    if (selectedFolderId.value) params.folderId = selectedFolderId.value
    if (route.query.groupTag) params.groupTag = route.query.groupTag
    const res = await axios.get('/api/fill/forms', { params })
    forms.value = res.data || []
    currentPage.value = 1
    selectedFormIds.value = []
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '获取表单模板失败')
  } finally {
    loading.value = false
  }
}

const selectAllFolders = async () => {
  selectedFolderId.value = ''
  await updateRouteFolder('')
  await loadForms()
}

const selectFolderById = async (folderId) => {
  selectedFolderId.value = folderId
  await updateRouteFolder(folderId)
  await loadForms()
}

const handleFolderSelect = async (data) => {
  hideContextMenu()
  await selectFolderById(data.id)
}

const handleFolderContextMenu = (event, data) => {
  if (!isAdmin.value || !data || data.systemNode) {
    hideContextMenu()
    return
  }
  event.preventDefault()
  contextMenu.value = {
    visible: true,
    x: Math.min(event.clientX, window.innerWidth - 180),
    y: Math.min(event.clientY, window.innerHeight - 140),
    folder: data
  }
}

const handleContextMenuAction = async (action) => {
  const folder = contextMenu.value.folder
  hideContextMenu()
  if (!folder) return
  if (action === 'create') {
    await createFolder(folder)
  } else if (action === 'rename') {
    await renameFolder(folder)
  } else if (action === 'delete') {
    await deleteFolder(folder)
  }
}

const handleFolderCommand = async (command, folder) => {
  if (!folder) return
  if (command === 'create') {
    await createFolder(folder)
  } else if (command === 'rename') {
    await renameFolder(folder)
  } else if (command === 'delete') {
    await deleteFolder(folder)
  }
}

const createFolder = async (parentNode = null) => {
  try {
    const result = await ElMessageBox.prompt('请输入目录名称', parentNode ? `在“${parentNode.name}”下新建目录` : '新建根目录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空'
    })
    await axios.post('/api/fill/folders', {
      name: result.value,
      parentId: parentNode?.systemNode ? null : (parentNode?.id || null)
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('目录创建成功')
    await loadFolders()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '目录创建失败')
    }
  }
}

const renameFolder = async (folder) => {
  try {
    const result = await ElMessageBox.prompt('修改目录名称', '重命名目录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: folder.name,
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空'
    })
    await axios.put(`/api/fill/folders/${folder.id}`, {
      name: result.value,
      parentId: folder.parentId || null,
      sortOrder: folder.sortOrder || 0
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('目录已重命名')
    await loadFolders()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '目录修改失败')
    }
  }
}

const deleteFolder = async (folder) => {
  try {
    await ElMessageBox.confirm(`确定删除目录“${folder.name}”吗？`, '删除目录', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await axios.delete(`/api/fill/folders/${folder.id}`, {
      params: { userEmail: currentUser.value }
    })
    if (selectedFolderId.value === folder.id) {
      selectedFolderId.value = ''
      await updateRouteFolder('')
    }
    ElMessage.success('目录已删除')
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '删除目录失败')
    }
  }
}

const openMoveDialog = (form) => {
  movingForm.value = form
  moveTargetFolderId.value = form.folderId || ''
  moveDialogVisible.value = true
}

const openBatchMoveDialog = () => {
  if (selectedFormIds.value.length === 0) {
    ElMessage.warning('请先勾选需要移动的模板！')
    return
  }
  batchMoveTargetFolderId.value = ''
  batchMoveDialogVisible.value = true
}

const confirmMoveForm = async () => {
  if (!movingForm.value) return
  try {
    await axios.put(`/api/fill/forms/${movingForm.value.id}/folder`, {
      folderId: moveTargetFolderId.value || null
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('模板目录已更新')
    moveDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '移动模板失败')
  }
}

const confirmBatchMove = async () => {
  if (selectedFormIds.value.length === 0) {
    ElMessage.warning('请先选择要移动的模板')
    return
  }
  try {
    await axios.put('/api/fill/forms/folder/batch', {
      formIds: selectedFormIds.value,
      folderId: batchMoveTargetFolderId.value || null
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('模板已批量移动')
    batchMoveDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '批量移动失败')
  }
}

const allowFolderDrop = (draggingNode, dropNode, type) => {
  const draggingData = draggingNode?.data
  const dropData = dropNode?.data
  if (!draggingData || !dropData) return false
  if (draggingData.systemNode || dropData.systemNode) return false
  if (type === 'inner' && draggingData.id === dropData.id) return false
  return true
}

const buildFolderReorderPayload = (nodes, parentId = null, result = []) => {
  ;(nodes || []).forEach((node, index) => {
    if (!node.systemNode) {
      result.push({
        id: node.id,
        parentId,
        sortOrder: (index + 1) * 10
      })
      buildFolderReorderPayload(node.children || [], node.id, result)
    }
  })
  return result
}

const handleFolderDrop = async (draggingNode, dropNode, dropType) => {
  const draggingFolder = draggingNode?.data
  const dropFolder = dropNode?.data
  try {
    const payload = buildFolderReorderPayload(folderTree.value.filter(node => !node.systemNode))
    await axios.put('/api/fill/folders/reorder', payload, {
      params: { userEmail: currentUser.value }
    })
    let successText = '目录顺序已更新'
    if (draggingFolder?.name && dropFolder?.name) {
      if (dropType === 'inner') {
        successText = `已将“${draggingFolder.name}”移动到“${dropFolder.name}”下`
      } else if (dropType === 'before') {
        successText = `已将“${draggingFolder.name}”调整到“${dropFolder.name}”前面`
      } else if (dropType === 'after') {
        successText = `已将“${draggingFolder.name}”调整到“${dropFolder.name}”后面`
      }
    }
    ElMessage.success(successText)
    await loadFolders()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '目录拖拽保存失败')
    await loadFolders()
  }
}

const handleDeleteForm = (form) => {
  deletingForm.value = form
  shouldDropTable.value = false
  deleteDialogVisible.value = true
}

const confirmDeleteForm = async () => {
  if (!deletingForm.value) return
  deleteLoading.value = true
  try {
    await axios.delete(`/api/fill/forms/${deletingForm.value.id}`, { 
      params: { 
        userEmail: currentUser.value,
        dropTable: shouldDropTable.value
      } 
    })
    ElMessage.success('模板已成功删除')
    deleteDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除操作失败')
  } finally {
    deleteLoading.value = false
  }
}

onMounted(async () => {
  document.addEventListener('click', hideContextMenu)
  selectedFolderId.value = route.query.folderId || ''
  if (route.query.search) {
    searchQuery.value = route.query.search
    appliedSearchQuery.value = route.query.search
  }
  if (route.query.status) {
    statusFilter.value = route.query.status
    appliedStatusFilter.value = route.query.status
  }
  await Promise.all([loadFolders(), loadForms()])
})

onBeforeUnmount(() => {
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.form-list-page {
  animation: fadeIn 0.4s ease-out;
}

.form-list-page {
  width: 100%;
  padding: 0 4px;
  box-sizing: border-box;
}

.page-layout {
  width: 100%;
  display: flex;
  gap: 16px;
  align-items: flex-start;
  box-sizing: border-box;
}

/* 左侧目录侧边栏 (与报表资产完全一致) */
.folder-sidebar-card {
  position: relative;
  width: v-bind('sidebarWidth + "px"');
  min-width: 120px;
  max-width: 350px;
  flex-shrink: 0;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  background: white;
  padding: 12px 6px;
  box-sizing: border-box;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
  transition: all 0.2s;
}

.folder-header-clean {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 8px 8px;
  border-bottom: 1px solid #f1f5f9;
  margin-bottom: 8px;
}

.folder-header-title {
  font-size: 13px;
  font-weight: 700;
  color: #475569;
  letter-spacing: 0.3px;
}

.add-folder-btn {
  font-size: 12.5px;
  font-weight: 600;
  padding: 0;
}

.folder-tree-panel {
  width: 100%;
}

/* 全部模板 根项 */
.folder-all-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  cursor: pointer;
  color: #334155;
  margin-bottom: 4px;
  font-size: 13px;
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

/* 树组件定制 (与报表资产完全一致) */
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
  justify-content: space-between;
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

.custom-report-tree :deep(.el-tree-node.is-current .node-folder-icon) {
  color: #2563eb;
}

.node-title-text {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: inherit;
}

.folder-node-actions {
  display: none;
  align-items: center;
  flex-shrink: 0;
  padding-left: 2px;
}

.folder-more-btn {
  padding: 2px;
  height: 20px;
  width: 20px;
  border-radius: 4px;
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.folder-more-btn:hover {
  background-color: #e2e8f0;
  color: #1e293b;
}

.tree-node-row:hover .folder-node-actions {
  display: flex;
}

/* 侧边栏缩放手柄样式 */
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

.folder-context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 160px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.16);
  padding: 6px;
}

.context-menu-item {
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  padding: 9px 12px;
  border-radius: 8px;
  color: #334155;
  cursor: pointer;
  font-size: 13px;
}

.context-menu-item:hover {
  background: #f8fafc;
}

.context-menu-item.danger {
  color: #dc2626;
}

/* 右侧内容区面板 (与报表资产完全一致) */
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

/* 顶部搜索过滤栏 (两端排版：左侧目录标题徽标，右侧整套紧凑搜索区) */
.table-search-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: nowrap;
  gap: 16px;
  margin-bottom: 16px;
  width: 100%;
  overflow-x: auto;
  padding-bottom: 2px;
}

.view-title-area {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.view-title {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
  letter-spacing: -0.01em;
}

.count-tag {
  background-color: #eff6ff !important;
  border-color: #dbeafe !important;
  color: #2563eb !important;
  font-weight: 500;
  padding: 0 10px;
  height: 24px;
  line-height: 22px;
}

.search-bar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.search-input-box {
  width: 250px;
  min-width: 180px;
  flex-shrink: 1;
}

.filter-status-box {
  width: 115px;
  min-width: 95px;
  flex-shrink: 1;
}

.action-btn {
  padding: 8px 16px;
  border-radius: 6px;
  flex-shrink: 0;
}

.search-submit-btn {
  background-color: #2563eb !important;
  border-color: #2563eb !important;
  font-weight: 500;
  flex-shrink: 0;
}

.divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  margin: 0 4px;
  flex-shrink: 0;
}

.batch-move-btn {
  background-color: #fffaf0 !important;
  border-color: #fed7aa !important;
  color: #ea580c !important;
  font-weight: 500;
  flex-shrink: 0;
}

.batch-move-btn:hover {
  background-color: #ffedd5 !important;
  border-color: #fdba74 !important;
  color: #c2410c !important;
}

/* 表格定制 */
.modern-table-row {
  transition: background-color 0.2s;
}

.modern-table-row:hover {
  background-color: #f8fafc !important;
}

.icon-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #eff6ff;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #2563eb;
  flex-shrink: 0;
  transition: all 0.2s;
}

.modern-table-row:hover .icon-avatar {
  background: #dbeafe;
  color: #1d4ed8;
}

.form-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name-text {
  font-weight: 400; /* 模板名称不用加粗 */
  color: #0f172a;
  font-size: 13.5px;
}

.folder-path-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #334155; /* 所属目录颜色加深 */
  font-size: 13px;
}

.table-code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: #f8fafc;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: #334155;
  border: 1px solid #e2e8f0;
  transition: all 0.2s;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.table-code:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1d4ed8;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  height: 24px;
  border: none;
}

.time-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #334155; /* 截止时间颜色加深 */
  font-size: 13px;
}

.creator-text {
  color: #1e293b; /* 创建人颜色加深 */
  font-size: 13px;
}

.action-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

:deep(.el-table__header-wrapper th),
:deep(.modern-table-header) {
  background-color: #f8fafc !important;
  color: #0f172a !important; /* 表头颜色深黑清晰 */
  font-weight: 700 !important; /* 表头加粗 */
  font-size: 13.5px !important;
  height: 46px;
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 12px;
}

@media (max-width: 768px) {
  .filter-inputs {
    flex-direction: column;
    align-items: stretch;
  }
  .search-input, .status-select {
    width: 100%;
  }
  .view-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
/* 彻底销毁确认弹窗样式 */
.delete-dialog-body {
  padding: 10px 4px;
}

.delete-warning {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.warning-icon {
  font-size: 24px;
  color: #f56c6c;
}

.warning-text {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
}

.delete-hint {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 20px;
  padding-left: 36px;
}

.delete-option-area {
  margin-left: 36px;
  padding: 12px 16px;
  background: #fff1f2;
  border: 1px solid #fecdd3;
  border-radius: 8px;
}

.delete-info-area {
  margin-left: 36px;
  padding: 12px 16px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #0369a1;
}

.option-label {
  font-weight: 600;
  color: #991b1b;
}

.option-desc {
  font-size: 12px;
  color: #b91c1c;
  margin-top: 4px;
  line-height: 1.4;
}

:deep(.delete-dialog .el-dialog__header) {
  margin-right: 0;
  border-bottom: 1px solid #f1f5f9;
  padding: 16px 20px;
}

:deep(.delete-dialog .el-dialog__title) {
  font-size: 16px;
  font-weight: 700;
}

:deep(.delete-dialog .el-dialog__footer) {
  border-top: 1px solid #f1f5f9;
  padding: 12px 20px;
}
</style>
