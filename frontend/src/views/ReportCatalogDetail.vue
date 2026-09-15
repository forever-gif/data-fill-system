<template>
  <div class="report-detail-container">
    <!-- 顶部统一的标准返回页头 -->
    <div class="header-nav flat-header">
      <el-page-header @back="goBack">
        <template #content>
          <span class="nav-page-title">报表资产详情</span>
        </template>
      </el-page-header>
    </div>

    <!-- 详情主内容体 -->
    <div class="detail-body">
      <!-- 报表资产 Banner 主卡片 -->
      <div class="report-hero-card">
        <div class="hero-chart-illustration">
          <svg width="84" height="60" viewBox="0 0 84 60" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="84" height="60" rx="14" fill="#F0F6FF"/>
            <circle cx="68" cy="22" r="10" fill="#DBEAFE"/>
            <circle cx="72" cy="38" r="6" fill="#FFEDD5"/>
            <!-- 5 根现代圆角柱状图 -->
            <rect x="14" y="24" width="7" height="24" rx="3" fill="#2563EB"/>
            <rect x="25" y="17" width="7" height="31" rx="3" fill="#3B82F6"/>
            <rect x="36" y="26" width="7" height="22" rx="3" fill="#60A5FA"/>
            <rect x="47" y="20" width="7" height="28" rx="3" fill="#3B82F6"/>
            <rect x="58" y="30" width="7" height="18" rx="3" fill="#93C5FD"/>
            <!-- 橙色折线趋势与数据拐点 -->
            <polyline points="17.5,25 28.5,18 39.5,27 50.5,21 61.5,31" fill="none" stroke="#F97316" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="17.5" cy="25" r="2.8" fill="#F97316"/>
            <circle cx="28.5" cy="18" r="3.2" fill="#F97316"/>
            <circle cx="39.5" cy="27" r="2.8" fill="#F97316"/>
            <circle cx="50.5" cy="21" r="3.2" fill="#F97316"/>
            <circle cx="61.5" cy="31" r="2.8" fill="#F97316"/>
          </svg>
        </div>
        <div class="hero-content">
          <div class="hero-title-row">
            <h2 class="hero-report-name">{{ cleanReportName }}</h2>
            <el-tag
              :type="getStatusTagType(currentStatus)"
              effect="light"
              round
              class="hero-status-tag"
            >
              {{ currentStatus }}
            </el-tag>
          </div>
          <p v-if="reportData.reportDesc || reportData.report_desc" class="hero-report-desc">{{ reportData.reportDesc || reportData.report_desc }}</p>
        </div>
      </div>

      <!-- 核心信息左右分栏 (上排两张平衡卡片) -->
      <el-row :gutter="20" class="info-row">
        <!-- 左上：基本信息与目录归属 -->
        <el-col :xs="24" :lg="12">
          <el-card class="info-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div class="card-header-title text-primary-deep">
                  <el-icon class="title-icon icon-blue"><Document /></el-icon>
                  <span>基本信息与目录归属</span>
                </div>
              </div>
            </template>

            <el-descriptions :column="1" border class="custom-descriptions">
              <el-descriptions-item label="报表名称">
                <span class="font-bold">{{ cleanReportName }}</span>
              </el-descriptions-item>

              <el-descriptions-item label="业务分类">
                <div class="domain-tags">
                  <el-tag v-if="reportData.bizDomain || reportData.domainL1 || reportData.l3" type="warning" effect="plain">业务域: {{ formatCleanName(reportData.bizDomain || reportData.domainL1 || reportData.l3) }}</el-tag>
                  <el-tag v-if="reportData.subjectL1" type="success" effect="plain">一级主题: {{ formatCleanName(reportData.subjectL1) }}</el-tag>
                  <el-tag v-if="reportData.subjectL2 || reportData.domainL2" type="info" effect="light">二级主题: {{ formatCleanName(reportData.subjectL2 || reportData.domainL2) }}</el-tag>
                  <span v-if="!reportData.bizDomain && !reportData.domainL1 && !reportData.l3 && !reportData.subjectL1 && !reportData.subjectL2" class="text-secondary">-</span>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="所属层级">
                <el-breadcrumb separator=">" class="nested-breadcrumb">
                  <el-breadcrumb-item v-if="reportData.l1">{{ formatCleanName(reportData.l1) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l2">{{ formatCleanName(reportData.l2) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l3">{{ formatCleanName(reportData.l3) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l4">{{ formatCleanName(reportData.l4) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l5">{{ formatCleanName(reportData.l5) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l6">{{ formatCleanName(reportData.l6) }}</el-breadcrumb-item>
                  <el-breadcrumb-item v-if="reportData.l7">{{ formatCleanName(reportData.l7) }}</el-breadcrumb-item>
                </el-breadcrumb>
              </el-descriptions-item>

              <el-descriptions-item label="报表全路径">
                <span class="text-secondary">{{ formatCleanPath(reportData.fullPath || reportData.full_path) }}</span>
              </el-descriptions-item>

              <el-descriptions-item label="报表链接 (URL)">
                <a :href="reportData.reportUrl || reportData.report_url || '#'" target="_blank" class="report-link">
                  {{ reportData.reportUrl || reportData.report_url || 'https://finevis.example.com/report/' + reportId }}
                  <el-icon><TopRight /></el-icon>
                </a>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>

        <!-- 右上：维护责任与组织 -->
        <el-col :xs="24" :lg="12">
          <el-card class="info-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div class="card-header-title text-primary-deep">
                  <el-icon class="title-icon icon-amber"><UserFilled /></el-icon>
                  <span>维护责任与组织</span>
                </div>
              </div>
            </template>

            <el-descriptions :column="1" border class="custom-descriptions">
              <el-descriptions-item label="责任人 (Owner)">
                <div class="avatar-info">
                  <el-avatar :size="26" class="owner-avatar">{{ reportData.owner ? reportData.owner[0] : '-' }}</el-avatar>
                  <span class="font-bold">{{ reportData.owner || '-' }}</span>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="开发人员">
                <div class="avatar-info">
                  <el-avatar :size="24" class="user-avatar">{{ (reportData.developer || reportData.owner) ? (reportData.developer || reportData.owner)[0] : '-' }}</el-avatar>
                  <span>{{ reportData.developer || reportData.owner || '-' }}</span>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="所属部门">
                <span>{{ reportData.deptName || reportData.dept_name || '-' }}</span>
              </el-descriptions-item>

              <el-descriptions-item label="更新频率 / 时间">
                <div class="freq-box">
                  <el-tag size="small" type="success">{{ reportData.updateFrequency || reportData.update_frequency || '每日' }}</el-tag>
                  <span class="freq-time">{{ reportData.dataUpdateTime || reportData.data_update_time || '-' }}</span>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="备注说明">
                <span class="text-secondary">{{ reportData.remark || '-' }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>

      <!-- 核心信息左右分栏 (下排两张平衡卡片) -->
      <el-row :gutter="20" class="info-row mt-20">
        <!-- 左下：关联指标与维度分析 -->
        <el-col :xs="24" :lg="12">
          <el-card class="info-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div class="card-header-title text-primary-deep">
                  <el-icon class="title-icon icon-green"><CollectionTag /></el-icon>
                  <span>关联指标与维度分析</span>
                </div>
              </div>
            </template>

            <el-descriptions :column="1" border class="custom-descriptions">
              <el-descriptions-item label="分析周期">
                <el-tag v-if="reportData.analysisCycle || reportData.analysis_cycle" size="small" type="info">
                  {{ reportData.analysisCycle || reportData.analysis_cycle }}
                </el-tag>
                <span v-else class="text-secondary">-</span>
              </el-descriptions-item>

              <el-descriptions-item label="关联维度">
                <div class="tag-group">
                  <el-tag
                    v-for="(dim, idx) in dimensionList"
                    :key="'dim_'+idx"
                    class="metric-chip"
                    type="info"
                    effect="light"
                  >
                    {{ dim }}
                  </el-tag>
                  <span v-if="dimensionList.length === 0" class="empty-tip">未关联维度</span>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="核心指标">
                <div class="tag-group">
                  <el-tag
                    v-for="(met, idx) in metricList"
                    :key="'met_'+idx"
                    class="metric-chip primary-chip"
                    effect="light"
                  >
                    {{ met }}
                  </el-tag>
                  <span v-if="metricList.length === 0" class="empty-tip">未关联指标</span>
                </div>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>

        <!-- 右下：安全管控与生命周期 -->
        <el-col :xs="24" :lg="12">
          <el-card class="info-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div class="card-header-title text-primary-deep">
                  <el-icon class="title-icon icon-purple"><Lock /></el-icon>
                  <span>安全管控与生命周期</span>
                </div>
              </div>
            </template>

            <el-descriptions :column="1" border class="custom-descriptions">
              <el-descriptions-item label="是否机密">
                <div class="security-box">
                  <el-tag
                    :type="(reportData.isConfidential === '是' || reportData.is_confidential === '是') ? 'danger' : 'info'"
                    size="small"
                  >
                    {{ (reportData.isConfidential === '是' || reportData.is_confidential === '是') ? '是 (机密)' : '否' }}
                  </el-tag>
                </div>
              </el-descriptions-item>

              <el-descriptions-item label="适用范围">
                <span class="text-regular font-medium">{{ reportData.useScope || reportData.use_scope || '-' }}</span>
              </el-descriptions-item>

              <el-descriptions-item label="生命周期">
                <el-tag
                  :type="getStatusTagType(currentStatus)"
                  effect="light"
                  round
                  size="small"
                >
                  {{ currentStatus }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  TrendCharts, User, OfficeBuilding, DataLine, Clock,
  Document, TopRight, CollectionTag,
  UserFilled, Lock, InfoFilled
} from '@element-plus/icons-vue'
import axios from 'axios'

const route = useRoute()
const router = useRouter()
const reportId = ref(route.params.id || 'REP-202508-0001')

// 返回上一页（保留原先的筛选条件与选中的目录/报表）
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push({ path: '/report-catalog', query: route.query })
  }
}

const reportData = ref({})
const loading = ref(false)

// 展示名称过滤器：完整保留原始数字序号 (如 00.经营管理, 01.产品管理)，仅去除文件后缀
const formatCleanName = (name) => {
  if (!name) return ''
  return name.replace(/\.cpt$/i, '').replace(/\.frm$/i, '').trim()
}

// 展示路径过滤器：保留目录链中每一级的数字序号
const formatCleanPath = (pathStr) => {
  if (!pathStr) return '—'
  return pathStr.split(' / ').map(formatCleanName).join(' / ')
}

const currentReportName = computed(() => {
  return reportData.value.reportName || reportData.value.report_name || '报表详情'
})

const cleanReportName = computed(() => {
  return formatCleanName(currentReportName.value)
})

const currentStatus = computed(() => {
  const raw = (reportData.value.lifecycleStatus || reportData.value.lifecycle_status || '').trim()
  return (raw === '正式' || raw === '正式使用') ? '正式使用' : '建设中'
})

const kpiData = computed(() => {
  return {
    authUserCnt: reportData.value.authUserCnt || 126,
    authDeptCnt: reportData.value.authDeptCnt || 8,
    authUsageRate: reportData.value.authUsageRate || 74.2,
    lastAccessTime: reportData.value.lastAccessTime || '2026-08-24 13:51:12'
  }
})

const dimensionList = computed(() => {
  const str = reportData.value.dimensions || ''
  if (!str) return []
  return str.split(/[,，、]/).map(s => s.trim()).filter(Boolean)
})

const metricList = computed(() => {
  const str = reportData.value.metrics || ''
  if (!str) return []
  return str.split(/[,，、]/).map(s => s.trim()).filter(Boolean)
})

const useScopeList = computed(() => {
  const str = reportData.value.useScope || reportData.value.use_scope || ''
  if (!str || str.trim() === '-') return []
  return str.split(/[,，、/]/).map(s => s.trim()).filter(Boolean)
})

const getStatusTagType = (status) => {
  return (status === '正式' || status === '正式使用') ? 'success' : 'warning'
}

const getSensitiveTagType = (level) => {
  if (level === '极密' || level === '机密') return 'danger'
  if (level === '秘密') return 'warning'
  return 'info'
}

// 核心：优先从高速缓存立即呈现，再静默刷新最新数据，实现 0 毫秒秒开
const fetchReportDetail = async () => {
  const cachedList = sessionStorage.getItem('REPORT_CATALOG_CACHE_DATA')
  if (cachedList) {
    try {
      const parsed = JSON.parse(cachedList)
      if (Array.isArray(parsed)) {
        const found = parsed.find(item => item.reportId === reportId.value)
        if (found) {
          reportData.value = found
          loading.value = false
        }
      }
    } catch (e) {}
  }

  if (!reportData.value || !reportData.value.reportName) {
    loading.value = true
  }

  try {
    const res = await axios.get(`/api/report/catalog/${reportId.value}?_t=${Date.now()}`)
    if (res.data) {
      reportData.value = res.data
      try {
        const cachedList = sessionStorage.getItem('REPORT_CATALOG_CACHE_DATA')
        if (cachedList) {
          const parsed = JSON.parse(cachedList)
          const idx = parsed.findIndex(item => item.reportId === reportId.value)
          if (idx !== -1) {
            parsed[idx] = res.data
            sessionStorage.setItem('REPORT_CATALOG_CACHE_DATA', JSON.stringify(parsed))
          }
        }
      } catch (e) {}
    }
  } catch (e) {
    if (!reportData.value || !reportData.value.reportName) {
      ElMessage.warning('未能从数据库查询到该报表资产数据')
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchReportDetail()
})
</script>

<style scoped>
.report-detail-container {
  width: 100%;
  padding: 0 4px;
  box-sizing: border-box;
}

/* 顶部页头 (1:1 还原参考设计) */
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
  gap: 10px;
}

.edit-action-btn {
  font-weight: 600;
  padding: 7px 16px;
  border-radius: 6px;
}

.more-action-btn {
  padding: 7px 14px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.header-nav {
  padding: 8px 0 16px 0;
}

.flat-header :deep(.el-page-header__left) {
  margin-right: 16px;
}

.flat-header :deep(.el-page-header__back) {
  font-weight: 500;
  color: #334155;
  font-size: 14px;
}

.flat-header :deep(.el-page-header__back:hover) {
  color: #2563eb;
}

.nav-page-title {
  font-size: 15px;
  font-weight: 600;
  color: #475569;
}

.nav-owner-text {
  color: #64748b;
}

.info-icon {
  font-size: 16px;
  cursor: help;
  color: #94a3b8;
}

.detail-body {
  width: 100%;
  box-sizing: border-box;
}

/* 报表主卡片 Banner (绿底图标 + 报表名 + 状态 + 描述) */
.report-hero-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: white;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  padding: 18px 20px;
  margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.hero-icon-avatar {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  background: #10b981;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
  box-shadow: 0 4px 10px rgba(16, 185, 129, 0.2);
}

.hero-content {
  flex: 1;
  min-width: 0;
}

.hero-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hero-report-name {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.hero-status-tag {
  border: none;
  font-size: 12px;
  padding: 0 10px;
  height: 22px;
}

.hero-report-desc {
  font-size: 13px;
  color: #64748b;
  margin: 6px 0 0 0;
  line-height: 1.5;
}

/* 权限概览外层卡片 (1:1 还原参考设计) */
.permission-overview-card {
  background: white;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  padding: 16px 20px;
  margin-bottom: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.section-header {
  margin-bottom: 12px;
}

.section-title {
  font-size: 14.5px;
  font-weight: 700;
  color: #0f172a; /* 加深大标题 */
  letter-spacing: -0.2px;
}

/* KPI 卡片网格 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi-card-item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f8fafc;
  border-radius: 10px;
  padding: 12px 14px;
  border: 1px solid #f1f5f9;
  transition: all 0.2s;
}

.kpi-card-item:hover {
  background: #f1f5f9;
}

.kpi-icon-box {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.kpi-icon-box.blue { background: #eff6ff; color: #3b82f6; }
.kpi-icon-box.purple { background: #faf5ff; color: #8b5cf6; }
.kpi-icon-box.green { background: #ecfdf5; color: #10b981; }
.kpi-icon-box.orange { background: #fff7ed; color: #f97316; }

.kpi-content {
  flex: 1;
  min-width: 0;
}

.kpi-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 3px;
}

.kpi-value {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.kpi-value .unit {
  font-size: 12px;
  font-weight: normal;
  color: #94a3b8;
  margin-left: 2px;
}

.kpi-subvalue {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

.kpi-value-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.kpi-progress {
  flex: 1;
}

.h-full {
  height: 100%;
}

.info-card {
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  background: white;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.mt-20 {
  margin-top: 20px;
}

.card-header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14.5px;
  font-weight: 700;
  color: #0f172a;
}

.title-icon {
  font-size: 17px;
}

.icon-blue { color: #2563eb; }
.icon-green { color: #10b981; }
.icon-amber { color: #f59e0b; }
.icon-purple { color: #8b5cf6; }

.custom-descriptions :deep(.el-descriptions__cell) {
  padding: 10px 14px;
  font-size: 13px;
}

.custom-descriptions :deep(.el-descriptions__label) {
  width: 130px;
  background: #f8fafc;
  color: #1e293b;
  font-weight: 600;
}

.custom-descriptions :deep(.el-descriptions__content) {
  color: #0f172a;
}

.font-bold {
  font-weight: 600;
  color: #0f172a;
}

.nested-breadcrumb {
  font-size: 13px;
}

.report-link {
  color: #2563eb;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.report-link:hover {
  text-decoration: underline;
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.metric-chip {
  border-radius: 6px;
}

.primary-chip {
  background: #eff6ff;
  color: #2563eb;
  border-color: #dbeafe;
}

.empty-tip {
  font-size: 13px;
  color: #94a3b8;
}

.avatar-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.owner-avatar {
  background: #10b981;
  color: white;
  font-weight: 600;
  font-size: 12px;
}

.user-avatar {
  background: #3b82f6;
  color: white;
  font-weight: 600;
  font-size: 12px;
}

.domain-tags {
  display: flex;
  gap: 6px;
}

.freq-box {
  display: flex;
  align-items: center;
  gap: 8px;
}

.freq-time {
  color: #64748b;
  font-size: 12.5px;
}

.text-secondary {
  color: #64748b;
}

@media (max-width: 1024px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
