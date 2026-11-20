<template>
  <div class="cpt-workbench-container">
    <div class="page-header">
      <div class="header-left">
        <h2>🛠️ 帆软全新报表纯规则装配向导</h2>
        <el-tag size="small" type="success" effect="plain">纯规则驱动 • 字段一键转筛选器 • 隐藏列 • 父子格 • 多级小计 • 团队公用规则库</el-tag>
      </div>
      <div class="header-right">
        <el-button type="warning" plain size="small" @click="openSharedHub('formula')">
          🌟 团队公用规则资产库 ({{ sharedFormulas.length + sharedRules.length + sharedParameters.length }} 项)
        </el-button>
      </div>
    </div>

    <!-- 步骤条 -->
    <el-steps :active="activeStep" finish-status="success" align-center class="step-bar">
      <el-step title="步骤 1" description="选基础样式母版" />
      <el-step title="步骤 2" description="贴入新SQL与筛选器" />
      <el-step title="步骤 3" description="隐藏列与父子格排版" />
      <el-step title="步骤 4" description="一键合成全新报表" />
    </el-steps>

    <!-- 步骤 1：选母版 -->
    <el-card v-if="activeStep === 0" class="step-card-wide" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>步骤 1：选择你要装配的【复杂高阶业务报表场景】（系统自动代劳 80% 复杂底层工作）</span>
          <el-tag type="success">告别帆软设计器手工苦活 • 场景化向导 • 工业级标准母版</el-tag>
        </div>
      </template>

      <!-- 场景化高难度报表选型卡片 -->
      <div class="archetype-grid">
        <div
          v-for="arc in archetypePresets"
          :key="arc.key"
          class="archetype-card"
          :class="{ active: selectedArchetypeKey === arc.key }"
          @click="selectArchetype(arc)"
        >
          <div class="arc-header">
            <span class="arc-icon">{{ arc.icon }}</span>
            <span class="arc-title">{{ arc.name }}</span>
            <el-tag size="small" :type="arc.tagType" effect="plain">{{ getArchetypeCountBadge(arc.key) || '分析中...' }}</el-tag>
          </div>
          
          <div class="arc-pain">
            <span class="pain-label">⚠️ 难点痛点:</span> {{ arc.painPoint }}
          </div>
          <div class="arc-sol">
            <span class="sol-label">⚡ 系统代劳:</span> {{ arc.systemSol }}
          </div>

          <div class="arc-features">
            <span v-for="ft in arc.features" :key="ft" class="arc-feat-tag">{{ ft }}</span>
          </div>
          <div class="arc-sample">
            <span style="color: #6b7280;">标杆模板:</span>
            <span style="font-weight: 600; color: #1e40af; margin-left: 4px;">{{ arc.sampleName }}</span>
          </div>
        </div>
      </div>

      <!-- 选中母版配置面板 -->
      <el-form label-width="130px" style="margin-top: 24px; padding: 18px 20px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;">
        <div style="font-weight: 600; font-size: 14px; color: #0f172a; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between;">
          <div style="display: flex; align-items: center; gap: 8px;">
            <span>🎯 选中场景与精选模板池：</span>
            <el-tag size="small" type="primary" effect="light">
              当前场景：{{ currentArchetype ? currentArchetype.name : '未选' }} ({{ filteredTemplates.length }} 套候选)
            </el-tag>
          </div>
          <el-checkbox v-model="showAllCategories" size="small">
            显示全部 210 套报表 (取消场景过滤)
          </el-checkbox>
        </div>

        <el-form-item :label="showAllCategories ? '全量模板选择' : '当前场景下模板'">
          <el-select
            v-model="form.templatePath"
            :placeholder="showAllCategories ? '从 210 个公司报表中选择模板' : ('仅限【' + (currentArchetype?.name || '') + '】下的 ' + filteredTemplates.length + ' 套候选模板')"
            style="width: 100%"
            filterable
            @change="handleTemplateChange"
          >
            <el-option
              v-for="item in (showAllCategories ? presetTemplates : filteredTemplates)"
              :key="item.path"
              :label="item.name"
              :value="item.path"
            >
              <span style="float: left; font-weight: 500;">{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 12px">{{ item.rel_path }}</span>
            </el-option>
          </el-select>
          <div style="font-size: 12px; color: #64748b; margin-top: 4px;">
            💡 已自动联动过滤：下拉框已锁定属于<b>【{{ currentArchetype?.name }}】</b>的精选模板，自动继承对应的复杂计算模型与样式骨架。
          </div>
        </el-form-item>

        <el-form-item label="主替换数据集">
          <el-select v-model="form.datasetName" style="width: 100%" placeholder="选择或填写被替换的主数据集">
            <el-option
              v-for="ds in templateDatasets"
              :key="ds.name"
              :label="ds.name"
              :value="ds.name"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="新报表名称">
          <el-input v-model="form.reportTitle" placeholder="如：美线销量明细分析表" />
        </el-form-item>
      </el-form>

      <div class="step-actions">
        <el-button type="primary" :disabled="!form.templatePath" @click="goToStep2">
          下一步：贴入数仓 SQL ➔
        </el-button>
      </div>
    </el-card>

    <!-- 步骤 2：贴入 SQL 并智能嗅探与字段一键转筛选器 -->
    <el-card v-if="activeStep === 1" class="step-card-wide" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>步骤 2：贴入数仓新 SQL 并配置顶部【筛选器 / 参数面板】</span>
          <div style="display: flex; gap: 8px;">
            <el-button type="success" size="small" @click="handleAnalyzeSql" :loading="analyzingSql">
              重新解析 SQL
            </el-button>
          </div>
        </div>
      </template>

      <!-- 场景化向导提示横幅 -->
      <div
        class="scenario-banner"
        :style="{ background: currentArchetype.bannerColor, borderColor: currentArchetype.bannerBorder, color: currentArchetype.bannerText }"
      >
        <div style="display: flex; align-items: center; gap: 8px;">
          <span style="font-size: 18px;">{{ currentArchetype.icon }}</span>
          <span style="font-weight: 600; font-size: 13px;">正在装配模式：{{ currentArchetype.name }}</span>
        </div>
        <div style="font-size: 12px; margin-top: 4px; opacity: 0.9;">
          {{ currentArchetype.guidanceTips }}
        </div>
      </div>

      <el-input
        v-model="form.sql"
        type="textarea"
        :rows="8"
        placeholder="贴入完整 SQL，支持 CTE (WITH子句)、JOIN、以及 ${fine_username}、${v_report_date}、${v_show_type} 等动态参数..."
      />

      <!-- 快捷操作区：SQL 字段一键转顶部筛选器 -->
      <div class="fields-quick-bar" style="margin-top: 14px; padding: 12px 16px; background: #f0fdf4; border: 1px solid #86efac; border-radius: 8px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <div style="font-weight: 600; font-size: 13px; color: #166534; display: flex; align-items: center; gap: 6px;">
            <span>⚡ 快捷生成：点击下方 SQL 字段，自动设为顶部筛选控件（并自动在上方 SQL 注入动态过滤语句）：</span>
          </div>
          <el-tag size="small" type="success" effect="light">实时嗅探出 {{ availableFields.length }} 个字段</el-tag>
        </div>
        <div style="display: flex; flex-wrap: wrap; gap: 8px;">
          <el-button
            v-for="f in availableFields"
            :key="f"
            size="small"
            :type="isFieldFiltered(f) ? 'success' : 'default'"
            :plain="!isFieldFiltered(f)"
            @click="toggleFilterForField(f)"
          >
            <span v-if="isFieldFiltered(f)">✓ 已设为筛选: {{ getFieldLabel(f) }} ({{ f }})</span>
            <span v-else>+ 设为筛选: {{ getFieldLabel(f) }} ({{ f }})</span>
          </el-button>
        </div>
      </div>

      <!-- 企业级公共参数模板一键套用栏 -->
      <div style="margin-top: 12px; padding: 10px 16px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px;">
        <div style="font-weight: 600; font-size: 13px; color: #1e40af; display: flex; align-items: center; gap: 6px;">
          <span>🛡️ 公共参数模板一键注入：</span>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap;">
          <el-button type="primary" size="small" plain @click="applySecurityParamsPreset">
            + 注入权限三件套 (fine_username, role, s_read_permission)
          </el-button>
          <el-button type="primary" size="small" plain @click="applyDateParamsPreset">
            + 注入报表基准日期 (v_report_date / v_month)
          </el-button>
          <el-button type="primary" size="small" plain @click="applyDimensionParamsPreset">
            + 注入常用维度过滤 (国家 / 事业部 / 一二级品类)
          </el-button>
          <el-button type="warning" size="small" plain @click="openSharedHub('parameter')">
            🌟 团队公用参数库 (管理/新增)
          </el-button>
        </div>
      </div>

      <!-- 筛选器配置区：支持自动提取 + 手动任意新增与删除 + 一键注入SQL -->
      <div style="margin-top: 18px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <div style="font-weight: 600; font-size: 13px; color: #1f2937;">
            🎯 顶部筛选器列表（生成 CPT 时自动在顶部参数面板布设对应控件）：
          </div>
          <div style="display: flex; gap: 8px;">
            <el-button type="warning" plain size="small" @click="addShowTypeRadioFilter">
              + 添加【展示方式】单选按钮组 (BU/运营组/专员)
            </el-button>
            <el-button type="primary" plain size="small" @click="addNewFilterRow">
              + 手动添加筛选参数
            </el-button>
          </div>
        </div>

        <el-table :data="filterConfigs" border stripe size="small">
          <el-table-column label="关联过滤字段" width="160">
            <template #default="scope">
              <el-select
                v-model="scope.row.bindField"
                size="small"
                style="width: 100%;"
                placeholder="选择关联字段"
                clearable
                filterable
                allow-create
                default-first-option
                @change="handleBindFieldChange(scope.row)"
              >
                <el-option
                  v-for="f in availableFields"
                  :key="f"
                  :label="getFieldLabel(f) + ' (' + f + ')'"
                  :value="f"
                />
              </el-select>
            </template>
          </el-table-column>

          <el-table-column label="SQL参数标识" width="160">
            <template #default="scope">
              <el-input v-model="scope.row.paramName" size="small" placeholder="如 v_bu_name">
                <template #prepend>$</template>
              </el-input>
            </template>
          </el-table-column>

          <el-table-column label="控件中文标签" width="130">
            <template #default="scope">
              <el-input v-model="scope.row.label" size="small" placeholder="显示标签名" />
            </template>
          </el-table-column>

          <el-table-column label="呈现控件类型" width="170">
            <template #default="scope">
              <el-select v-model="scope.row.widgetType" size="small" style="width: 100%">
                <el-option label="☑️ 下拉复选框 (ComboCheckBox)" value="ComboCheckBox" />
                <el-option label="📅 日期选择器 (DateEditor)" value="DateEditor" />
                <el-option label="🔘 单选按钮组 (RadioGroup)" value="RadioGroup" />
                <el-option label="🔤 文本输入框 (TextEditor)" value="TextEditor" />
                <el-option label="🔒 隐藏系统鉴权参数 (无控件)" value="Hidden" />
              </el-select>
            </template>
          </el-table-column>

          <el-table-column label="默认值 (可选)" width="120">
            <template #default="scope">
              <el-input v-model="scope.row.defaultValue" size="small" placeholder="如 1 或 全部" />
            </template>
          </el-table-column>

          <!-- 动态 SQL 注入状态与快捷按钮 -->
          <el-table-column label="SQL 过滤状态" min-width="190">
            <template #default="scope">
              <div style="display: flex; gap: 8px; align-items: center;">
                <template v-if="isParamInSql(scope.row.paramName)">
                  <el-tag type="success" size="small" effect="plain">✓ 已注入到 SQL</el-tag>
                  <el-button
                    type="danger"
                    link
                    size="small"
                    @click="removeParamFromSql(scope.row.paramName)"
                  >
                    从 SQL 撤销
                  </el-button>
                </template>
                <template v-else>
                  <el-tag type="info" size="small" effect="plain">未在 SQL 过滤</el-tag>
                  <el-button
                    v-if="scope.row.bindField"
                    type="success"
                    size="small"
                    @click="injectFilterIntoSql(scope.row)"
                  >
                    注入到 SQL ➔
                  </el-button>
                  <span v-else style="font-size: 11px; color: #9ca3af;">(纯显示控件)</span>
                </template>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="65" align="center">
            <template #default="scope">
              <el-button type="danger" link size="small" @click="removeFilterRow(scope.$index)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="step-actions">
        <el-button @click="activeStep = 0">上一步</el-button>
        <el-button type="primary" @click="goToStep3">
          下一步：设置字段排版、隐藏列与条件属性 ➔
        </el-button>
      </div>
    </el-card>

    <!-- 步骤 3：隐藏列、父子格显式配置与条件属性规则抽屉 -->
    <el-card v-if="activeStep === 2" class="step-card-wide" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>步骤 3：隐藏列、多级小计、父子格显式控制与条件属性可视化配置</span>
          <el-tag type="success">支持静态隐藏(辅助列) • 动态按参数隐藏 • 父子格改绑</el-tag>
        </div>
      </template>

      <!-- 场景化向导提示横幅 -->
      <div
        class="scenario-banner"
        :style="{ background: currentArchetype.bannerColor, borderColor: currentArchetype.bannerBorder, color: currentArchetype.bannerText }"
      >
        <div style="display: flex; align-items: center; gap: 8px;">
          <span style="font-size: 18px;">{{ currentArchetype.icon }}</span>
          <span style="font-weight: 600; font-size: 13px;">正在装配模式：{{ currentArchetype.name }}</span>
        </div>
        <div style="font-size: 12px; margin-top: 4px; opacity: 0.9;">
          {{ currentArchetype.guidanceTips }}
        </div>
      </div>

      <!-- 公共规则一键套用中心 -->
      <div style="margin-bottom: 14px; padding: 12px 16px; background: #faf5ff; border: 1px solid #d8b4fe; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px;">
        <div style="font-weight: 600; font-size: 13px; color: #6b21a8; display: flex; align-items: center; gap: 6px;">
          <span>⚡ 企业级公共规则套用中心：</span>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap;">
          <el-button type="primary" size="small" @click="applyAutoLayoutRules">
            ⚡ 智能匹配排版规则 (自动推导维度/指标/隐藏列)
          </el-button>
          <el-button type="danger" size="small" plain @click="applyBatchNegativeRedRules">
            🔴 一键所有指标负数标红 (&lt; 0)
          </el-button>
          <el-button type="info" size="small" plain @click="applyBatchHideHelperCols">
            🚫 一键辅助排序列隐藏 (列宽置0)
          </el-button>
          <el-button type="warning" size="small" @click="openSharedHub('formula')">
            🌟 团队公用规则与公式中心 (新增/共享)
          </el-button>
        </div>
      </div>

      <el-table :data="columnConfigs" border stripe size="small">
        <el-table-column prop="field" label="SQL字段名" width="130" />
        
        <el-table-column label="中文表头" width="115">
          <template #default="scope">
            <el-input v-model="scope.row.title" size="small" placeholder="显示的列名" />
          </template>
        </el-table-column>

        <el-table-column label="扩展方向 / 角色" width="150" align="center">
          <template #default="scope">
            <el-select v-model="scope.row.direction" size="small" style="width: 100%">
              <el-option label="纵向维度(向下)" value="1" />
              <el-option label="横向交叉(向右)" value="2" />
              <el-option label="指标(数值)" value="0" />
            </el-select>
          </template>
        </el-table-column>

        <!-- 显式静态隐藏列配置 -->
        <el-table-column label="【隐藏列控制】" width="135" align="center">
          <template #default="scope">
            <el-checkbox v-model="scope.row.isHidden" size="small">
              静态隐藏(列宽0)
            </el-checkbox>
            <div v-if="scope.row.isHidden" style="font-size: 11px; color: #6b7280;">
              [辅助列] 供计算不展现
            </div>
          </template>
        </el-table-column>

        <!-- 父子格显式配置列 -->
        <el-table-column label="【父子格显式控制】" width="175">
          <template #default="scope">
            <el-select v-model="scope.row.leftParentField" size="small" style="width: 100%" placeholder="选择左父格">
              <el-option label="自动推荐 (层级串联)" value="auto" />
              <el-option label="无 / 默认" value="none" />
              <el-option
                v-for="dim in columnConfigs.filter(c => c.direction === '1' && c.field !== scope.row.field)"
                :key="dim.field"
                :label="'挂载到: ' + (dim.title || dim.field)"
                :value="dim.field"
              />
            </el-select>
          </template>
        </el-table-column>

        <!-- 纵向维度的多级小计配置 -->
        <el-table-column label="多级小计配置" width="150">
          <template #default="scope">
            <div v-if="scope.row.direction === '1'" class="subtotal-config">
              <el-checkbox v-model="scope.row.hasSubtotal">追加小计</el-checkbox>
              <el-input
                v-if="scope.row.hasSubtotal"
                v-model="scope.row.subtotalLabel"
                size="small"
                style="width: 60px; margin-left: 6px;"
              />
            </div>
            <span v-else style="color: #9ca3af; font-size: 11px;">- (非维度列)</span>
          </template>
        </el-table-column>

        <!-- 指标计算规则 (SUM / 自定义公式) -->
        <el-table-column label="指标汇总计算 / 公式" width="180">
          <template #default="scope">
            <div v-if="scope.row.direction === '0'">
              <el-checkbox v-model="scope.row.autoSum" size="small">小计/总计SUM</el-checkbox>
              <div style="margin-top: 4px; display: flex; gap: 4px; align-items: center;">
                <el-input
                  v-model="scope.row.customFormula"
                  size="small"
                  placeholder="公式 如 =A3/B3"
                  style="flex: 1;"
                />
                <el-button size="small" type="primary" link @click="openFormulaSelector(scope.row)">
                  选择
                </el-button>
              </div>
            </div>
            <span v-else style="color: #9ca3af; font-size: 11px;">- (非指标列)</span>
          </template>
        </el-table-column>

        <!-- 条件属性可视化配置与规则抽屉 -->
        <el-table-column label="【条件属性规则】(标红/背景/隐藏整行/动态隐藏列)" min-width="280">
          <template #default="scope">
            <div class="highlight-rules-box">
              <div v-for="(rule, rIdx) in scope.row.highlightRules" :key="rIdx" class="rule-badge">
                <el-tag
                  size="small"
                  closable
                  :type="getRuleTagType(rule.actionType)"
                  @close="removeRule(scope.row, rIdx)"
                >
                  {{ formatRuleLabel(rule) }}
                </el-tag>
              </div>
              <div style="display: flex; gap: 6px; align-items: center; margin-top: 4px; flex-wrap: wrap;">
                <el-button size="small" type="primary" link @click="openRuleDrawer(scope.row)">
                  + 自定义规则
                </el-button>
                <el-dropdown trigger="click" @command="(cmd) => handleRuleDropdownCommand(scope.row, cmd)">
                  <el-button size="small" type="success" link>
                    ⚡ 套用预设/共享规则 ▾
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-for="preset in sharedRules"
                        :key="preset.id"
                        :command="'apply_' + preset.id"
                      >
                        <span v-if="preset.builtin">{{ preset.name }}</span>
                        <span v-else style="color: #059669; font-weight: 500;">🌟 [共享] {{ preset.name }} (by {{ preset.author }})</span>
                      </el-dropdown-item>
                      <el-dropdown-item divided command="save_as_shared">
                        💾 将此列最后一条规则存为团队共享
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="60" align="center">
          <template #default="scope">
            <el-button type="danger" link size="small" @click="removeColumn(scope.$index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="step-actions">
        <el-button @click="activeStep = 1">上一步</el-button>
        <el-button type="primary" @click="activeStep = 3">
          下一步：预览并下载全新 CPT ➔
        </el-button>
      </div>
    </el-card>

    <!-- 步骤 4：生成与下载 -->
    <el-card v-if="activeStep === 3" class="step-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>步骤 4：一键合成符合帆软标准的 .cpt 报表</span>
          <el-tag type="success">100% 规则保证 • 零 AI 幻觉 • 格式严谨</el-tag>
        </div>
      </template>

      <div class="summary-box">
        <el-descriptions title="新报表生成摘要" :column="2" border>
          <el-descriptions-item label="报表标题">{{ form.reportTitle }}</el-descriptions-item>
          <el-descriptions-item label="参考母版">{{ form.templatePath.split('\\').pop() }}</el-descriptions-item>
          <el-descriptions-item label="主数据集">{{ form.datasetName }}</el-descriptions-item>
          <el-descriptions-item label="配置列总数">{{ columnConfigs.length }} 列</el-descriptions-item>
          <el-descriptions-item label="纵向展开维度">
            {{ columnConfigs.filter(c => c.direction === '1').map(c => c.title || c.field).join(', ') || '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="横向交叉维度">
            {{ columnConfigs.filter(c => c.direction === '2').map(c => c.title || c.field).join(', ') || '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="静态隐藏辅助列">
            {{ columnConfigs.filter(c => c.isHidden).map(c => c.title || c.field).join(', ') || '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="挂载小计列">
            {{ columnConfigs.filter(c => c.hasSubtotal).map(c => c.title || c.field).join(', ') || '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="同步顶部筛选器" :span="2">
            {{ filterConfigs.map(f => f.label + ' ($' + f.paramName + ')').join(' | ') || '无' }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="step-actions">
        <el-button @click="activeStep = 2">上一步</el-button>
        <el-button type="success" size="large" :loading="generating" @click="handleBuildNewReport">
          🚀 立即生成并下载全新 .CPT 文件
        </el-button>
      </div>
    </el-card>

    <!-- 弹窗 1：团队公用规则与资产管理中心 (支持查看、套用、同事自由新增与共享) -->
    <el-dialog
      v-model="sharedHubVisible"
      title="🌟 团队公用规则与公式资产共享中心"
      width="880px"
      destroy-on-close
    >
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <span style="font-size: 13px; color: #4b5563;">
          同事沉淀的公共规则、公式模板与常用参数已统一持久化，全员均可直接套用或贡献新规则。
        </span>
        <el-button type="success" size="small" @click="openAddSharedAssetModal">
          ➕ 贡献新公共规则 / 模板
        </el-button>
      </div>

      <el-tabs v-model="sharedHubTab" type="border-card">
        <!-- Tab 1: 常用公式函数模板 -->
        <el-tab-pane label="📐 常用公式函数模板" name="formula">
          <el-table :data="sharedFormulas" border stripe size="small" max-height="400">
            <el-table-column prop="name" label="模板名称" width="160">
              <template #default="scope">
                <div style="font-weight: 600;">{{ scope.row.name }}</div>
                <el-tag size="small" :type="scope.row.builtin ? 'info' : 'success'" effect="plain">
                  {{ scope.row.builtin ? '系统内置' : ('by ' + scope.row.author) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="templateExpr" label="公式表达式" min-width="260">
              <template #default="scope">
                <code style="color: #2563eb; background: #eff6ff; padding: 2px 6px; border-radius: 4px;">{{ scope.row.templateExpr }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="使用说明" min-width="180" />
            <el-table-column label="操作" width="130" align="center">
              <template #default="scope">
                <el-button
                  type="primary"
                  link
                  size="small"
                  :disabled="!targetColumnForFormula"
                  @click="applyFormulaToTarget(scope.row)"
                >
                  套用到列
                </el-button>
                <el-button
                  v-if="!scope.row.builtin"
                  type="danger"
                  link
                  size="small"
                  @click="deleteSharedAsset('formula', scope.row.id)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab 2: 条件属性与高亮规则 -->
        <el-tab-pane label="🎨 条件属性与高亮规则" name="rule">
          <el-table :data="sharedRules" border stripe size="small" max-height="400">
            <el-table-column prop="name" label="规则名称" width="160">
              <template #default="scope">
                <div style="font-weight: 600;">{{ scope.row.name }}</div>
                <el-tag size="small" :type="scope.row.builtin ? 'info' : 'success'" effect="plain">
                  {{ scope.row.builtin ? '系统内置' : ('by ' + scope.row.author) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="触发条件" min-width="200">
              <template #default="scope">
                <span v-if="scope.row.conditionType === 'formula'">公式: <code>{{ scope.row.formulaExpr }}</code></span>
                <span v-else>值 {{ formatOp(scope.row.compareOp) }} {{ scope.row.compareVal }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="actionType" label="执行动作" width="130">
              <template #default="scope">
                <el-tag size="small" :type="getRuleTagType(scope.row.actionType)">
                  {{ formatAction(scope.row.actionType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="170" />
            <el-table-column label="操作" width="130" align="center">
              <template #default="scope">
                <el-button
                  type="primary"
                  link
                  size="small"
                  :disabled="!currentRow"
                  @click="applyRuleToCurrentRow(scope.row)"
                >
                  套用到列
                </el-button>
                <el-button
                  v-if="!scope.row.builtin"
                  type="danger"
                  link
                  size="small"
                  @click="deleteSharedAsset('rule', scope.row.id)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab 3: 公用参数预设库 -->
        <el-tab-pane label="🛡️ 公用参数预设库" name="parameter">
          <el-table :data="sharedParameters" border stripe size="small" max-height="400">
            <el-table-column prop="paramName" label="参数标识" width="160">
              <template #default="scope">
                <code>${{ scope.row.paramName }}</code>
                <div>
                  <el-tag size="small" :type="scope.row.builtin ? 'info' : 'success'" effect="plain">
                    {{ scope.row.builtin ? '系统内置' : ('by ' + scope.row.author) }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="label" label="控件中文名" width="120" />
            <el-table-column prop="widgetType" label="控件类型" width="140" />
            <el-table-column prop="defaultValue" label="默认值" width="110" />
            <el-table-column prop="description" label="业务说明" min-width="180" />
            <el-table-column label="操作" width="130" align="center">
              <template #default="scope">
                <el-button
                  type="success"
                  link
                  size="small"
                  @click="injectSingleParamPreset(scope.row)"
                >
                  + 注入报表
                </el-button>
                <el-button
                  v-if="!scope.row.builtin"
                  type="danger"
                  link
                  size="small"
                  @click="deleteSharedAsset('parameter', scope.row.key)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button size="small" @click="sharedHubVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 2：新增自定义共享资产 (参数/公式/高亮规则) -->
    <el-dialog
      v-model="addCustomModalVisible"
      :title="'➕ 贡献团队共享资产 - ' + (newAssetType === 'formula' ? '常用公式' : (newAssetType === 'rule' ? '条件属性规则' : '公共参数'))"
      width="540px"
      destroy-on-close
    >
      <el-form label-width="120px" size="small">
        <el-form-item label="贡献者姓名" required>
          <el-input v-model="newAssetForm.author" placeholder="如：张工 / 供应链组" />
        </el-form-item>

        <el-form-item label="资产类别">
          <el-radio-group v-model="newAssetType">
            <el-radio value="formula">公式模板</el-radio>
            <el-radio value="rule">条件属性</el-radio>
            <el-radio value="parameter">公共参数</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 针对公式模板 -->
        <template v-if="newAssetType === 'formula'">
          <el-form-item label="公式名称" required>
            <el-input v-model="newAssetForm.name" placeholder="如：毛利率计算 / 达成率" />
          </el-form-item>
          <el-form-item label="业务分类">
            <el-input v-model="newAssetForm.category" placeholder="如：比率计算 / 文本拼接" />
          </el-form-item>
          <el-form-item label="公式表达式" required>
            <el-input
              v-model="newAssetForm.templateExpr"
              placeholder="如：=IF(ISNULL({sales})||{sales}==0, 0, {profit}/{sales})"
            />
            <div style="font-size: 11px; color: #6b7280; margin-top: 4px;">
              提示：用 {字段名} 作为占位符，套用时会自动替换为对应单元格
            </div>
          </el-form-item>
        </template>

        <!-- 针对条件属性规则 -->
        <template v-if="newAssetType === 'rule'">
          <el-form-item label="规则名称" required>
            <el-input v-model="newAssetForm.name" placeholder="如：利润率低于10%黄色告警" />
          </el-form-item>
          <el-form-item label="触发条件方式">
            <el-radio-group v-model="newAssetForm.conditionType">
              <el-radio value="value">按数值大小</el-radio>
              <el-radio value="formula">按帆软公式</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="newAssetForm.conditionType === 'value'" label="数值比较">
            <div style="display: flex; gap: 8px;">
              <el-select v-model="newAssetForm.compareOp" style="width: 120px;">
                <el-option label="小于 (<)" value="1" />
                <el-option label="小于等于 (<=)" value="2" />
                <el-option label="等于 (==)" value="0" />
                <el-option label="大于等于 (>=)" value="3" />
                <el-option label="大于 (>)" value="4" />
              </el-select>
              <el-input v-model="newAssetForm.compareVal" placeholder="比较值 如 0 或 0.1" />
            </div>
          </el-form-item>
          <el-form-item v-if="newAssetForm.conditionType === 'formula'" label="公式表达式">
            <el-input v-model="newAssetForm.formulaExpr" placeholder="如：$v_show_type != '1'" />
          </el-form-item>
          <el-form-item label="执行动作">
            <el-select v-model="newAssetForm.actionType" style="width: 100%;">
              <el-option label="🔴 字体颜色变红 (Font Red)" value="font_red" />
              <el-option label="🟡 单元格背景变黄高亮 (Background Yellow)" value="bg_yellow" />
              <el-option label="🚫 隐藏此列 (列宽置0)" value="hide_col" />
              <el-option label="👁️ 隐藏整行 (行高置0)" value="hide_row" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 针对公共参数 -->
        <template v-if="newAssetType === 'parameter'">
          <el-form-item label="参数标识" required>
            <el-input v-model="newAssetForm.paramName" placeholder="如 v_channel_id">
              <template #prepend>$</template>
            </el-input>
          </el-form-item>
          <el-form-item label="中文标签" required>
            <el-input v-model="newAssetForm.label" placeholder="如 销售渠道" />
          </el-form-item>
          <el-form-item label="呈现控件">
            <el-select v-model="newAssetForm.widgetType" style="width: 100%;">
              <el-option label="☑️ 下拉复选框 (ComboCheckBox)" value="ComboCheckBox" />
              <el-option label="📅 日期选择器 (DateEditor)" value="DateEditor" />
              <el-option label="🔘 单选按钮组 (RadioGroup)" value="RadioGroup" />
              <el-option label="🔤 文本输入框 (TextEditor)" value="TextEditor" />
              <el-option label="🔒 隐藏权限参数" value="Hidden" />
            </el-select>
          </el-form-item>
          <el-form-item label="默认值">
            <el-input v-model="newAssetForm.defaultValue" placeholder="如 全部 或 1" />
          </el-form-item>
        </template>

        <el-form-item label="使用说明">
          <el-input v-model="newAssetForm.description" type="textarea" :rows="2" placeholder="详细说明使用场景与注意点..." />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button size="small" @click="addCustomModalVisible = false">取消</el-button>
        <el-button type="success" size="small" @click="submitSaveCustomAsset">保存并共享</el-button>
      </template>
    </el-dialog>

    <!-- 条件属性配置抽屉/对话框 -->
    <el-dialog v-model="drawerVisible" title="配置单元格条件属性规则" width="560px">
      <el-form label-width="120px" size="small">
        <el-form-item label="规则名称">
          <el-input v-model="tempRule.name" placeholder="如：负数标红、按参数隐藏列" />
        </el-form-item>

        <el-form-item label="触发条件方式">
          <el-radio-group v-model="tempRule.conditionType">
            <el-radio value="value">按当前单元格数值比对</el-radio>
            <el-radio value="formula">自定义帆软公式 (如参数判断)</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="tempRule.conditionType === 'value'" label="数值比对条件">
          <div style="display: flex; gap: 8px;">
            <el-select v-model="tempRule.compareOp" style="width: 110px;">
              <el-option label="小于 (<)" value="1" />
              <el-option label="小于等于 (<=)" value="2" />
              <el-option label="等于 (==)" value="0" />
              <el-option label="大于等于 (>=)" value="3" />
              <el-option label="大于 (>)" value="4" />
            </el-select>
            <el-input v-model="tempRule.compareVal" placeholder="比较值，如 0" />
          </div>
        </el-form-item>

        <el-form-item v-if="tempRule.conditionType === 'formula'" label="帆软公式表达式">
          <el-input
            v-model="tempRule.formulaExpr"
            placeholder="如：$v_show_type != '3' 或 len($$$)==0"
          />
          <div style="font-size: 11px; color: #6b7280; margin-top: 4px;">
            提示：支持使用顶部单选参数，如 $v_show_type != '3'（非专员模式时隐藏此列）
          </div>
        </el-form-item>

        <el-form-item label="触发动作">
          <el-select v-model="tempRule.actionType" style="width: 100%;">
            <el-option label="🚫 隐藏此列 (列宽设为 0)" value="hide_col" />
            <el-option label="🔴 字体颜色变红 (Font Red)" value="font_red" />
            <el-option label="🎨 单元格背景变黄高亮 (Background Yellow)" value="bg_yellow" />
            <el-option label="👁️ 隐藏整行 (行高设为 0)" value="hide_row" />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button size="small" @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="confirmAddRule">确认添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

const activeStep = ref(0)
const presetTemplates = ref([])
const templateDatasets = ref([])
const analyzingSql = ref(false)
const generating = ref(false)

// 共享公共资产库
const sharedParameters = ref([])
const sharedFormulas = ref([])
const sharedRules = ref([])
const sharedHubVisible = ref(false)
const sharedHubTab = ref('formula')

// 6 大核心共有模板预设规范 (从 210 套报表中逆向归纳)
const selectedArchetypeKey = ref('MULTI_LEVEL_SUBTOTAL')
const showAllCategories = ref(false)

const archetypePresets = [
  {
    key: 'MULTI_GRANULARITY_DYNAMIC',
    icon: '🎛️',
    name: '多粒度动态折叠报表（周转率模式）',
    tagType: 'danger',
    painPoint: '单表多用(品类/SPU/SKU)，手动在设计器做多行隔离与行列互锁极其痛苦',
    systemSol: '自动生成行列互锁矩阵、周转率公式自动重算、多行模板隔离',
    features: ['单选展示方式联动', '多行物理隔离', '列宽行高互锁', '周转率衍生公式'],
    sampleName: '01品类周转率分析.cpt',
    matchKeyword: '01品类周转率分析',
    defaultTitle: '品类周转率多颗粒度动态分析表',
    bannerColor: '#fef2f2',
    bannerBorder: '#fca5a5',
    bannerText: '#991b1b',
    guidanceTips: '⚡ 已启用【多粒度行列互锁引擎】：系统会自动为你处理品类、SPU、SKU等多行物理隔离，并自动绑定单选联动消隐规则。'
  },
  {
    key: 'FINANCIAL_PL',
    icon: '💰',
    name: '财务经营损益表 P&L（59行固定科目树）',
    tagType: 'warning',
    painPoint: '手动画60行会计科目、交错配%费率行、算智能环比与客单价极繁琐',
    systemSol: '内置59行标准损益科目树，自动装配交错费率%与智能环比(基点差额)',
    features: ['59行固定损益科目树', '交错费率%', '智能环比同比(基点差额)'],
    sampleName: '01财务分析会小组利润表.cpt',
    matchKeyword: '01财务分析会小组利润表',
    defaultTitle: '财务经营分析会损益利润表',
    bannerColor: '#fffbeb',
    bannerBorder: '#fde68a',
    bannerText: '#92400e',
    guidanceTips: '⚡ 已启用【财务科目树引擎】：系统已预装59行标准经营损益科目与智能环比公式，自动识别绝对值与百分比基点差额。'
  },
  {
    key: 'MULTI_LEVEL_SUBTOTAL',
    icon: '📑',
    name: '组织多级小计下钻表（树状层级汇总）',
    tagType: 'primary',
    painPoint: '事业部➔部门➔专员➔品类多层级自动汇总与小计行、父子格关联极难调',
    systemSol: '自动串联多级父子格、小计行自动SUM求和、指标自动向上聚合',
    features: ['纵向多级分组', '小计/总计SUM自动求和', '左父格层级串联'],
    sampleName: '09事业部-运营专员-品类分析.cpt',
    matchKeyword: '09事业部-运营专员-品类分析',
    defaultTitle: '事业部运营专员多级小计分析表',
    bannerColor: '#eff6ff',
    bannerBorder: '#bfdbfe',
    bannerText: '#1e40af',
    guidanceTips: '⚡ 已启用【多级小计下钻引擎】：系统会自动按层级挂载小计行并智能串联左父格，指标自动启用求和汇总。'
  },
  {
    key: 'CROSS_TAB_MATRIX',
    icon: '🔀',
    name: '双向动态交叉透视表（矩阵动态透视）',
    tagType: 'success',
    painPoint: '年月/周次向右动态展开，品类纵向展开，交叉点动态汇总指标',
    systemSol: '自动配置横向扩展单元格(dir=1)与多维交叉透视汇总',
    features: ['横向扩展 dir=1', '双向矩阵透视', '年月/周度趋势对比'],
    sampleName: '01供应链下单出货计划.cpt',
    matchKeyword: '供应链下单出货计划',
    defaultTitle: '供应链渠道多维交叉透视表',
    bannerColor: '#f0fdf4',
    bannerBorder: '#86efac',
    bannerText: '#166534',
    guidanceTips: '⚡ 已启用【双向交叉透视引擎】：系统已将年月/周次列配置为横向扩展单元格，交叉点指标自动求和。'
  },
  {
    key: 'WIDE_DETAIL_AUDIT',
    icon: '📊',
    name: '全成本项大宽表明细穿透表（超多列审计）',
    tagType: 'info',
    painPoint: '横向60~95列全成本与费用项核算，辅助排序列隐藏、月结核对',
    systemSol: '超宽明细规范排版、辅助列列宽置0、全成本项公式勾稽',
    features: ['超宽明细(60+列)', '辅助排序列隐藏', '多字段动态过滤'],
    sampleName: '03运营利润SKU明细月报.cpt',
    matchKeyword: '03运营利润SKU明细月报',
    defaultTitle: '运营利润全要素SKU明细月报',
    bannerColor: '#f8fafc',
    bannerBorder: '#cbd5e1',
    bannerText: '#334155',
    guidanceTips: '⚡ 已启用【大宽表穿透引擎】：针对60+超宽列自动优化列宽，排版辅助列自动隐藏。'
  },
  {
    key: 'STANDARD_METRIC_CARD',
    icon: '📈',
    name: '常规业务看板/极速明细表（快速出表）',
    tagType: 'default',
    painPoint: '日常常规报表，无需复杂多级折叠，只需标准规范表头',
    systemSol: '标准双层表头、负数标红、库存告警',
    features: ['双层标准表头', '负数自动标红', '库存告警黄色高亮'],
    sampleName: '01每日快递超区超规告警.cpt',
    matchKeyword: '01每日快递超区超规告警',
    defaultTitle: '业务核心指标监控快报',
    bannerColor: '#f8fafc',
    bannerBorder: '#e2e8f0',
    bannerText: '#475569',
    guidanceTips: '⚡ 已启用【极速标准出表引擎】：标准单向明细展开，负数自动标红。'
  }
]

const currentArchetype = computed(() => {
  return archetypePresets.find(a => a.key === selectedArchetypeKey.value) || archetypePresets[0]
})

// 根据当前选中的母版分类，对模板池进行严格联动过滤！
const filteredTemplates = computed(() => {
  if (!presetTemplates.value || presetTemplates.value.length === 0) return []
  return presetTemplates.value.filter(t => t.archetypeKey === selectedArchetypeKey.value)
})

const getArchetypeCountBadge = (key) => {
  if (!presetTemplates.value || presetTemplates.value.length === 0) return ''
  const c = presetTemplates.value.filter(t => t.archetypeKey === key).length
  const pct = ((c / presetTemplates.value.length) * 100).toFixed(1)
  return `${c} 套 (${pct}%)`
}

const currentTemplateDisplayName = computed(() => {
  if (!form.value.templatePath) return '尚未选择'
  const arcName = currentArchetype.value ? `【${currentArchetype.value.name}】` : ''
  const fileName = form.value.templatePath.split('\\').pop() || form.value.templatePath
  return `${arcName} ${fileName}`
})

const selectArchetype = (arc) => {
  selectedArchetypeKey.value = arc.key
  form.value.reportTitle = arc.defaultTitle

  // 在该分类下的候选模板中找到最佳标杆模板
  const candidates = presetTemplates.value.filter(t => t.archetypeKey === arc.key)
  if (candidates.length > 0) {
    const found = candidates.find(t => t.name.includes(arc.matchKeyword)) || candidates[0]
    form.value.templatePath = found.path
    handleTemplateChange()
  }
  ElMessage.success(`已锁定母版大类：${arc.name}，下方模板池已自动过滤！`)
}

// 新增共享资产弹窗控制
const addCustomModalVisible = ref(false)
const newAssetType = ref('formula')
const newAssetForm = ref({
  author: localStorage.getItem('cpt_user_name') || '业务同事',
  name: '',
  category: '业务自定义',
  templateExpr: '',
  paramName: '',
  label: '',
  widgetType: 'ComboCheckBox',
  defaultValue: '',
  conditionType: 'value',
  compareOp: '1',
  compareVal: '0',
  formulaExpr: '',
  actionType: 'font_red',
  description: ''
})

const drawerVisible = ref(false)
const tempRule = ref({
  name: '规则1',
  conditionType: 'value',
  formulaExpr: "$v_show_type != '3'",
  compareOp: '1',
  compareVal: '0',
  actionType: 'hide_col'
})

const form = ref({
  templatePath: '',
  datasetName: '',
  reportTitle: '事业部运营专员多级分析表',
  sql: `SELECT 
  bu_name,
  dept_name,
  person_name,
  sea_port_name,
  sales_qty,
  sales_amount
FROM ads.ads_scm_pla_purchase_reference_na_wek`
})

const extractedFields = ref([])
const filterConfigs = ref([])
const columnConfigs = ref([])

// 业务常用英文字段到中文标签的智能映射表
const fieldLabelMap = {
  bu_name: '事业部',
  bu_sort: '事业部',
  dept_name: '运营部门',
  person_name: '运营专员',
  user_name: '负责人',
  sea_port_name: '港口',
  port_name: '港口',
  country: '国家',
  month: '月份',
  year: '年份',
  date: '日期',
  report_date: '报告日期',
  report_wek_chr: '报告周',
  sales_qty: '销量',
  sales_amount: '销售额',
  spu: 'SPU',
  sku: 'SKU',
  warehouse_type: '仓库类型',
  warehouse_type_char: '展示方式/状态',
  status: '状态',
  channel: '渠道'
}

const getFieldLabel = (field) => {
  if (!field) return ''
  return fieldLabelMap[field.toLowerCase()] || field
}

// 快速解析 SQL 字段
const parseFieldsFromSql = (sql) => {
  if (!sql) return []
  const m = sql.match(/SELECT\s+([\s\S]*?)\s+FROM\b/i)
  if (!m) return []
  const raw = m[1].split(',')
  const list = []
  raw.forEach(item => {
    item = item.trim()
    if (!item) return
    const asMatch = item.match(/\bAS\s+([a-zA-Z0-9_]+)$/i)
    if (asMatch) {
      list.push(asMatch[1])
    } else {
      const parts = item.split(/\s+/)
      const last = parts[parts.length - 1].replace(/[`"']/g, '')
      const field = last.split('.').pop()
      if (/^[a-zA-Z0-9_]+$/.test(field)) {
        list.push(field)
      }
    }
  })
  return Array.from(new Set(list))
}

const availableFields = computed(() => {
  const fromSql = parseFieldsFromSql(form.value.sql)
  if (fromSql.length > 0) return fromSql
  if (extractedFields.value && extractedFields.value.length > 0) return extractedFields.value
  return []
})

const isFieldFiltered = (field) => {
  return filterConfigs.value.some(f => f.bindField === field)
}

const isParamInSql = (paramName) => {
  if (!paramName) return false
  const p = paramName.startsWith('$') ? paramName.substring(1) : paramName
  return form.value.sql.includes(p)
}

const insertClauseIntoSql = (sql, clause, param) => {
  if (sql.includes(param)) return sql

  const upper = sql.toUpperCase()
  let insertPos = sql.length

  const keywords = ['\nGROUP BY', ' GROUP BY', '\nORDER BY', ' ORDER BY', '\nHAVING', ' HAVING', '\nLIMIT', ' LIMIT']
  let earliestIdx = -1
  for (const kw of keywords) {
    const idx = upper.indexOf(kw)
    if (idx !== -1 && (earliestIdx === -1 || idx < earliestIdx)) {
      earliestIdx = idx
    }
  }

  if (earliestIdx !== -1) {
    insertPos = earliestIdx
  }

  const before = sql.slice(0, insertPos)
  const after = sql.slice(insertPos)

  if (/WHERE\b/i.test(before)) {
    return before.trimEnd() + '\n  ' + clause.trim() + '\n' + after.trimStart()
  } else {
    return before.trimEnd() + '\nWHERE 1=1\n  ' + clause.trim() + '\n' + after.trimStart()
  }
}

const removeParamFromSql = (paramName) => {
  const p = paramName.startsWith('$') ? paramName.substring(1) : paramName
  const lines = form.value.sql.split('\n')
  const filtered = lines.filter(line => !line.includes(p))
  form.value.sql = filtered.join('\n')
  ElMessage.success(`已从 SQL 中撤销参数 [${p}] 的过滤子句`)
}

const toggleFilterForField = (field) => {
  const existingIdx = filterConfigs.value.findIndex(f => f.bindField === field)
  const param = `v_${field}`

  if (existingIdx !== -1) {
    removeFilterRow(existingIdx)
  } else {
    const label = getFieldLabel(field)
    let widget = 'ComboCheckBox'
    const fLower = field.toLowerCase()
    if (fLower.includes('date') || fLower.includes('time') || fLower.includes('month') || fLower.includes('day')) {
      widget = 'DateEditor'
    }

    const newFilter = {
      paramName: param,
      label: label,
      widgetType: widget,
      defaultValue: '',
      bindField: field
    }
    filterConfigs.value.push(newFilter)

    let clause = ''
    if (widget === 'ComboCheckBox') {
      clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${field} IN ('" + replace(${param}, ",", "','") + "') ")})`
    } else {
      clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${field} = '" + ${param} + "' ")})`
    }

    form.value.sql = insertClauseIntoSql(form.value.sql, clause, param)
    ElMessage.success(`已为 [${field}] 生成顶部筛选器并自动注入 SQL 动态过滤条件！`)
  }
}

const handleBindFieldChange = (row) => {
  if (row.bindField) {
    row.paramName = `v_${row.bindField}`
    row.label = getFieldLabel(row.bindField)
  }
}

const injectFilterIntoSql = (filter) => {
  if (!filter.bindField) {
    ElMessage.warning('请先选择或输入要绑定的过滤字段')
    return
  }
  const param = filter.paramName.startsWith('$') ? filter.paramName.substring(1) : filter.paramName
  const field = filter.bindField

  let clause = ''
  if (filter.widgetType === 'ComboCheckBox') {
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${field} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else {
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${field} = '" + ${param} + "' ")})`
  }

  form.value.sql = insertClauseIntoSql(form.value.sql, clause, param)
  ElMessage.success(`成功为字段 [${field}] 注入筛选条件至 SQL！`)
}

const addNewFilterRow = () => {
  const newIdx = filterConfigs.value.length + 1
  filterConfigs.value.push({
    paramName: `v_param_${newIdx}`,
    label: `参数${newIdx}`,
    widgetType: 'ComboCheckBox',
    defaultValue: '',
    bindField: ''
  })
}

const addShowTypeRadioFilter = () => {
  if (filterConfigs.value.some(f => f.paramName === 'v_show_type')) {
    ElMessage.info('已存在展示方式单选控件，无需重复添加')
    return
  }
  filterConfigs.value.push({
    paramName: 'v_show_type',
    label: '展示方式',
    widgetType: 'RadioGroup',
    defaultValue: '1',
    bindField: ''
  })
  ElMessage.success('已添加【展示方式】单选按钮组控件（可在步骤3配置条件属性按此控件动态隐藏列）')
}

const removeFilterRow = (idx) => {
  const target = filterConfigs.value[idx]
  if (target && target.paramName) {
    const p = target.paramName.startsWith('$') ? target.paramName.substring(1) : target.paramName
    if (form.value.sql.includes(p)) {
      const lines = form.value.sql.split('\n')
      form.value.sql = lines.filter(l => !l.includes(p)).join('\n')
    }
  }
  filterConfigs.value.splice(idx, 1)
  ElMessage.info('已移除筛选器')
}

// 加载公共规则资产
const loadCommonRules = async () => {
  try {
    const res = await axios.get('/api/cpt/common_rules')
    sharedParameters.value = res.data.parameters || []
    sharedFormulas.value = res.data.formulas || []
    sharedRules.value = res.data.rules || []
  } catch (err) {
    console.error('加载公共规则资产失败', err)
  }
}

// 打开资产共享中心
const openSharedHub = (tab) => {
  sharedHubTab.value = tab || 'formula'
  sharedHubVisible.value = true
  loadCommonRules()
}

// 智能在当前 SQL 字段列表中匹配最佳关联字段
const findBestMatchField = (keywords, defaultField = '') => {
  const fields = availableFields.value || []
  for (const kw of keywords) {
    const found = fields.find(f => f.toLowerCase().includes(kw.toLowerCase()))
    if (found) return found
  }
  return defaultField
}

// 步骤2快捷注入参数预设，并同时自动生成动态过滤条件注入到上方 SQL 代码中
const injectSingleParamPreset = (p) => {
  if (filterConfigs.value.some(f => f.paramName === p.paramName)) {
    ElMessage.info(`参数 [${p.paramName}] 已存在，无需重复注入`)
    return
  }

  let matchedField = ''
  let clause = ''
  const param = p.paramName

  if (param === 'v_report_date') {
    matchedField = findBestMatchField(['report_date', 'date', 'day', 'time', 'wek'], 'report_date')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} = '" + ${param} + "' ")})`
  } else if (param === 'v_month') {
    matchedField = findBestMatchField(['month', 'mon'], 'report_month_id')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} = '" + ${param} + "' ")})`
  } else if (param === 'v_start_date') {
    matchedField = findBestMatchField(['report_date', 'date', 'day', 'time'], 'report_date')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} >= '" + ${param} + "' ")})`
  } else if (param === 'v_end_date') {
    matchedField = findBestMatchField(['report_date', 'date', 'day', 'time'], 'report_date')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} <= '" + ${param} + "' ")})`
  } else if (param === 'v_country') {
    matchedField = findBestMatchField(['country_name_zh', 'country_name', 'country'], 'country_name_zh')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_bu') {
    matchedField = findBestMatchField(['bu_name', 'bu'], 'bu_name')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_continent') {
    matchedField = findBestMatchField(['continent'], 'continent')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_first_sort_desc') {
    matchedField = findBestMatchField(['first_sort_desc', 'first_sort', 'category'], 'first_sort_desc')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_second_sort_desc') {
    matchedField = findBestMatchField(['second_sort_desc', 'second_sort'], 'second_sort_desc')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_third_sort_desc') {
    matchedField = findBestMatchField(['third_sort_desc', 'third_sort'], 'third_sort_desc')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 'v_sku') {
    matchedField = findBestMatchField(['sku'], 'sku')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (param === 's_read_permission') {
    clause = `AND \${s_read_permission} = 1`
  } else if (p.widgetType === 'ComboCheckBox') {
    matchedField = findBestMatchField([param.replace('v_', '')], param.replace('v_', ''))
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} IN ('" + replace(${param}, ",", "','") + "') ")})`
  } else if (p.widgetType === 'DateEditor') {
    matchedField = findBestMatchField(['date', 'time', 'day'], 'report_date')
    clause = `AND (\${if(len(${param}) == 0, " 1=1 ", " ${matchedField} = '" + ${param} + "' ")})`
  }

  filterConfigs.value.push({
    paramName: p.paramName,
    label: p.label,
    widgetType: p.widgetType,
    defaultValue: p.defaultValue,
    bindField: matchedField
  })

  if (clause) {
    form.value.sql = insertClauseIntoSql(form.value.sql, clause, param)
  }

  ElMessage.success(`成功注入公用参数 [${p.label}]，并已在上方 SQL 追加动态过滤代码！`)
}

const applySecurityParamsPreset = () => {
  const secParams = sharedParameters.value.filter(p => p.isSecurity)
  let count = 0
  secParams.forEach(p => {
    if (!filterConfigs.value.some(f => f.paramName === p.paramName)) {
      filterConfigs.value.push({
        paramName: p.paramName,
        label: p.label,
        widgetType: p.widgetType,
        defaultValue: p.defaultValue,
        bindField: ''
      })
      count++
    }
  })

  // 同时将行级权限核心控制语句注入到上方 SQL 中
  if (!form.value.sql.includes('s_read_permission')) {
    form.value.sql = insertClauseIntoSql(form.value.sql, `AND \${s_read_permission} = 1`, 's_read_permission')
  }

  ElMessage.success(`已注入 ${count} 个权限安全参数，并在 SQL 中自动添加 [AND \${s_read_permission} = 1] 校验！`)
}

const applyDateParamsPreset = () => {
  const dateParams = sharedParameters.value.filter(p => p.paramName === 'v_report_date' || p.paramName === 'v_month')
  dateParams.forEach(p => injectSingleParamPreset(p))
}

const applyDimensionParamsPreset = () => {
  const dimParams = sharedParameters.value.filter(p => ['v_country', 'v_bu', 'v_first_sort_desc', 'v_second_sort_desc'].includes(p.paramName))
  dimParams.forEach(p => injectSingleParamPreset(p))
}

// 步骤3快捷操作
const applyAutoLayoutRules = () => {
  columnConfigs.value.forEach(col => {
    const f = col.field.toLowerCase()
    if (f.endsWith('_sort') || f.endsWith('_rank') || f === 'sort_id' || f === 'rn') {
      col.isHidden = true
      col.direction = '1'
    } else if (f.includes('num') || f.includes('qty') || f.includes('amount') || f.includes('sales')) {
      col.direction = '0'
      col.autoSum = true
    } else if (f.includes('bu') || f.includes('dept') || f.includes('category')) {
      col.direction = '1'
      col.hasSubtotal = true
      col.subtotalLabel = '小计'
    }
  })
  ElMessage.success('已自动应用生产最佳排版规则（维度、指标与辅助隐藏列）')
}

const applyBatchNegativeRedRules = () => {
  let count = 0
  columnConfigs.value.forEach(col => {
    if (col.direction === '0') {
      if (!col.highlightRules) col.highlightRules = []
      if (!col.highlightRules.some(r => r.actionType === 'font_red')) {
        col.highlightRules.push({
          name: '负数标红',
          conditionType: 'value',
          formulaExpr: '',
          compareOp: '1',
          compareVal: '0',
          actionType: 'font_red'
        })
        count++
      }
    }
  })
  ElMessage.success(`已为 ${count} 个数值指标列批量注入【负数标红】条件属性`)
}

const applyBatchHideHelperCols = () => {
  let count = 0
  columnConfigs.value.forEach(col => {
    const f = col.field.toLowerCase()
    if (f.endsWith('_sort') || f.endsWith('_rank') || f.includes('rn') || f === 'sort_id') {
      col.isHidden = true
      count++
    }
  })
  ElMessage.success(`已将 ${count} 个排版辅助列置为静态隐藏 (列宽=0)`)
}

// 打开公式选择器
const openFormulaSelector = (row) => {
  targetColumnForFormula.value = row
  openSharedHub('formula')
}

const applyFormulaToTarget = (formula) => {
  if (targetColumnForFormula.value) {
    targetColumnForFormula.value.customFormula = formula.templateExpr
    ElMessage.success(`已将公式 [${formula.name}] 套用至列 [${targetColumnForFormula.value.title || targetColumnForFormula.value.field}]`)
    sharedHubVisible.value = false
  }
}

const applyRuleToCurrentRow = (rule) => {
  if (currentRow.value) {
    if (!currentRow.value.highlightRules) currentRow.value.highlightRules = []
    currentRow.value.highlightRules.push({
      name: rule.name,
      conditionType: rule.conditionType,
      formulaExpr: rule.formulaExpr,
      compareOp: rule.compareOp,
      compareVal: rule.compareVal,
      actionType: rule.actionType
    })
    ElMessage.success(`已将规则 [${rule.name}] 套用至列 [${currentRow.value.title || currentRow.value.field}]`)
    sharedHubVisible.value = false
  }
}

// 下拉菜单处理
const handleRuleDropdownCommand = async (row, cmd) => {
  currentRow.value = row
  if (cmd.startsWith('apply_')) {
    const ruleId = cmd.replace('apply_', '')
    const found = sharedRules.value.find(r => r.id === ruleId)
    if (found) {
      applyRuleToCurrentRow(found)
    }
  } else if (cmd === 'save_as_shared') {
    if (!row.highlightRules || row.highlightRules.length === 0) {
      ElMessage.warning('当前列尚未配置任何条件属性规则，无法另存为公用规则')
      return
    }
    const lastRule = row.highlightRules[row.highlightRules.length - 1]
    newAssetType.value = 'rule'
    newAssetForm.value.name = lastRule.name || '自定义共享规则'
    newAssetForm.value.conditionType = lastRule.conditionType
    newAssetForm.value.compareOp = lastRule.compareOp || '1'
    newAssetForm.value.compareVal = lastRule.compareVal || '0'
    newAssetForm.value.formulaExpr = lastRule.formulaExpr || ''
    newAssetForm.value.actionType = lastRule.actionType
    newAssetForm.value.description = `由 ${newAssetForm.value.author} 在开发报表时提炼沉淀`
    addCustomModalVisible.value = true
  }
}

// 打开新增资产弹窗
const openAddSharedAssetModal = () => {
  newAssetType.value = sharedHubTab.value
  newAssetForm.value.name = ''
  newAssetForm.value.description = ''
  addCustomModalVisible.value = true
}

// 提交新增自定义资产
const submitSaveCustomAsset = async () => {
  if (!newAssetForm.value.author.trim()) {
    ElMessage.warning('请填写贡献者姓名')
    return
  }
  localStorage.setItem('cpt_user_name', newAssetForm.value.author.trim())

  try {
    if (newAssetType.value === 'formula') {
      if (!newAssetForm.value.name.trim() || !newAssetForm.value.templateExpr.trim()) {
        ElMessage.warning('请填写公式名称与表达式')
        return
      }
      await axios.post('/api/cpt/common_rules/formula', {
        name: newAssetForm.value.name,
        category: newAssetForm.value.category || '业务自定义',
        templateExpr: newAssetForm.value.templateExpr,
        description: newAssetForm.value.description,
        author: newAssetForm.value.author,
        placeholders: ['target']
      })
    } else if (newAssetType.value === 'rule') {
      if (!newAssetForm.value.name.trim()) {
        ElMessage.warning('请填写规则名称')
        return
      }
      await axios.post('/api/cpt/common_rules/rule', {
        name: newAssetForm.value.name,
        category: '业务自定义',
        conditionType: newAssetForm.value.conditionType,
        compareOp: newAssetForm.value.compareOp,
        compareVal: newAssetForm.value.compareVal,
        formulaExpr: newAssetForm.value.formulaExpr,
        actionType: newAssetForm.value.actionType,
        description: newAssetForm.value.description,
        author: newAssetForm.value.author
      })
    } else if (newAssetType.value === 'parameter') {
      if (!newAssetForm.value.paramName.trim() || !newAssetForm.value.label.trim()) {
        ElMessage.warning('请填写参数标识与中文标签')
        return
      }
      const p = newAssetForm.value.paramName.startsWith('$') ? newAssetForm.value.paramName.substring(1) : newAssetForm.value.paramName
      await axios.post('/api/cpt/common_rules/parameter', {
        key: 'custom_' + p,
        paramName: p,
        label: newAssetForm.value.label,
        widgetType: newAssetForm.value.widgetType,
        defaultValue: newAssetForm.value.defaultValue,
        description: newAssetForm.value.description,
        isSecurity: newAssetForm.value.widgetType === 'Hidden',
        author: newAssetForm.value.author
      })
    }

    ElMessage.success('🎉 贡献成功！新公用规则已持久化保存，全团队同事立即可用！')
    addCustomModalVisible.value = false
    await loadCommonRules()
  } catch (err) {
    ElMessage.error('保存共享规则失败: ' + (err.response?.data?.message || err.message))
  }
}

// 删除非内置资产
const deleteSharedAsset = async (category, id) => {
  try {
    await ElMessageBox.confirm('确定要删除这条团队共享规则吗？', '删除确认', { type: 'warning' })
    await axios.delete(`/api/cpt/common_rules/${category}/${id}`)
    ElMessage.success('已删除规则')
    await loadCommonRules()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const formatOp = (op) => {
  const map = { '1': '<', '2': '<=', '0': '==', '3': '>=', '4': '>' }
  return map[op] || op
}

const formatAction = (act) => {
  const map = { font_red: '🔴 字体标红', bg_yellow: '🟡 背景黄色', hide_col: '🚫 隐藏列', hide_row: '👁️ 隐藏行' }
  return map[act] || act
}

onMounted(async () => {
  loadCommonRules()
  try {
    const res = await axios.get('/api/cpt/preset_templates')
    presetTemplates.value = res.data.templates || []
    if (presetTemplates.value.length > 0) {
      const found = presetTemplates.value.find(t => t.name.includes('事业部-运营专员') || t.name.includes('销量简表') || t.name.includes('供应链'))
      form.value.templatePath = found ? found.path : presetTemplates.value[0].path
      await handleTemplateChange()
    }
  } catch (err) {
    console.error(err)
  }
})

const handleTemplateChange = async () => {
  if (!form.value.templatePath) return
  try {
    const fd = new FormData()
    fd.append('path', form.value.templatePath)
    const res = await axios.post('/api/cpt/parse_template', fd)
    templateDatasets.value = res.data.datasets || []
    if (templateDatasets.value.length > 0) {
      form.value.datasetName = templateDatasets.value[0].name
    }
  } catch (err) {
    ElMessage.error('读取母版失败')
  }
}

const goToStep2 = () => {
  activeStep.value = 1
  handleAnalyzeSql()
}

const handleAnalyzeSql = async () => {
  if (!form.value.sql.trim()) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  analyzingSql.value = true
  try {
    const res = await axios.post('/api/cpt/analyze_sql', { sql: form.value.sql })
    extractedFields.value = res.data.fields_found || []
    const paramsFound = res.data.parameters_found || []

    if (filterConfigs.value.length === 0 && paramsFound.length > 0) {
      filterConfigs.value = paramsFound.map(p => {
        const pLower = p.toLowerCase()
        let widget = 'TextEditor'
        let label = p
        let defaultVal = ''
        let bindField = ''

        if (pLower.includes('date') || pLower.includes('time') || pLower.includes('day') || pLower.includes('wek') || pLower.includes('month')) {
          widget = 'DateEditor'
          label = pLower.includes('month') ? '查询月份' : '报告日期'
          bindField = extractedFields.value.find(f => f.toLowerCase().includes('date') || f.toLowerCase().includes('chr') || f.toLowerCase().includes('day')) || ''
        } else if (pLower.includes('fine_') || pLower.includes('permission') || pLower.includes('role')) {
          widget = 'Hidden'
          label = '系统鉴权参数'
        } else if (pLower.includes('type') || pLower.includes('status')) {
          widget = 'RadioGroup'
          label = '展示方式/状态'
          defaultVal = '1'
          bindField = extractedFields.value.find(f => f.toLowerCase().includes('type')) || ''
        } else {
          widget = 'ComboCheckBox'
          label = pLower.includes('bu') ? '事业部' : (pLower.includes('dept') ? '运营部门' : p)
          bindField = extractedFields.value.find(f => f.toLowerCase().includes('bu') || f.toLowerCase().includes('dept')) || ''
        }

        return {
          paramName: p,
          label: label,
          widgetType: widget,
          defaultValue: defaultVal,
          bindField: bindField
        }
      })
    }

    const autoDimKeywords = ['bu', 'dept', 'person', 'sort', 'desc', 'category', 'spu', 'sku', 'name', 'type', 'date']
    const autoCrossKeywords = ['port', 'month', 'country', 'channel']
    const autoHideKeywords = ['id', 'code', 'sort_order', 'delete_flag', 'create_time', 'update_time']

    columnConfigs.value = extractedFields.value.map(f => {
      const fLower = f.toLowerCase()
      let dir = '0'
      if (autoCrossKeywords.some(k => fLower.includes(k))) {
        dir = '2'
      } else if (autoDimKeywords.some(k => fLower.includes(k))) {
        dir = '1'
      }

      const isHiddenDefault = autoHideKeywords.some(k => fLower.endsWith(k))

      const defaultRules = []
      if (dir === '0' && !isHiddenDefault) {
        defaultRules.push({
          name: '负数标红',
          conditionType: 'value',
          formulaExpr: '',
          compareOp: '1',
          compareVal: '0',
          actionType: 'font_red'
        })
      }

      return {
        field: f,
        title: getFieldLabel(f),
        direction: dir,
        isHidden: isHiddenDefault,
        leftParentField: 'auto',
        hasSubtotal: dir === '1' && (fLower.includes('bu') || fLower.includes('dept')),
        subtotalLabel: '合计',
        autoSum: dir === '0' && !fLower.includes('rate') && !isHiddenDefault,
        customFormula: '',
        highlightRules: defaultRules
      }
    })
    ElMessage.success(`成功识别出 ${extractedFields.value.length} 个字段与 ${paramsFound.length} 个参数`)
  } catch (err) {
    ElMessage.error('SQL 分析失败')
  } finally {
    analyzingSql.value = false
  }
}

const goToStep3 = () => {
  if (columnConfigs.value.length === 0) {
    handleAnalyzeSql()
  }
  activeStep.value = 2
}

const removeColumn = (idx) => {
  columnConfigs.value.splice(idx, 1)
}

const openRuleDrawer = (row) => {
  currentRow.value = row
  tempRule.value = {
    name: '规则' + (row.highlightRules.length + 1),
    conditionType: 'value',
    formulaExpr: "$v_show_type != '3'",
    compareOp: '1',
    compareVal: '0',
    actionType: 'hide_col'
  }
  drawerVisible.value = true
}

const confirmAddRule = () => {
  if (currentRow.value) {
    if (!currentRow.value.highlightRules) currentRow.value.highlightRules = []
    currentRow.value.highlightRules.push({ ...tempRule.value })
  }
  drawerVisible.value = false
}

const removeRule = (row, idx) => {
  row.highlightRules.splice(idx, 1)
}

const getRuleTagType = (action) => {
  if (action === 'font_red') return 'danger'
  if (action === 'bg_yellow') return 'warning'
  if (action === 'hide_col') return 'primary'
  return 'info'
}

const formatRuleLabel = (rule) => {
  const actMap = { font_red: '标红', bg_yellow: '标黄', hide_row: '隐藏行', hide_col: '隐藏列' }
  if (rule.conditionType === 'formula') {
    return `当 ${rule.formulaExpr} ➔ ${actMap[rule.actionType] || ''}`
  }
  const opMap = { '1': '<', '2': '<=', '0': '==', '3': '>=', '4': '>' }
  return `当值 ${opMap[rule.compareOp] || ''} ${rule.compareVal} ➔ ${actMap[rule.actionType] || ''}`
}

const handleBuildNewReport = async () => {
  generating.value = true
  try {
    const payload = {
      templatePath: form.value.templatePath,
      datasetName: form.value.datasetName,
      sql: form.value.sql,
      reportTitle: form.value.reportTitle,
      filters: filterConfigs.value,
      columns: columnConfigs.value
    }

    const res = await axios.post('/api/cpt/build_new_report', payload, { responseType: 'blob' })
    const blob = new Blob([res.data])
    const link = document.createElement('a')
    link.href = window.URL.createObjectURL(blob)
    link.download = `${form.value.reportTitle || 'new_report'}.cpt`
    link.click()
    ElMessage.success('全新 CPT 报表下载成功！请直接用帆软设计器打开查看！')
  } catch (err) {
    ElMessage.error('组装新报表失败')
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.cpt-workbench-container {
  padding: 16px 24px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-left h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.step-bar {
  margin-bottom: 24px;
  background: #fff;
  padding: 16px 0;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.05);
}
.step-card {
  max-width: 1100px;
  margin: 0 auto;
}
.step-card-wide {
  max-width: 1450px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.step-actions {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.subtotal-config {
  display: flex;
  align-items: center;
}
.highlight-rules-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
.rule-badge {
  display: flex;
  align-items: center;
}
.summary-box {
  padding: 20px 0;
}
.archetype-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 10px;
}
.archetype-card {
  background: #ffffff;
  border: 2px solid #e2e8f0;
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.archetype-card:hover {
  border-color: #93c5fd;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.08);
}
.archetype-card.active {
  border-color: #2563eb;
  background: #f8faff;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.15);
}
.arc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.arc-icon {
  font-size: 20px;
  margin-right: 6px;
}
.arc-title {
  font-weight: 600;
  font-size: 14px;
  color: #1e293b;
  flex: 1;
}
.arc-desc {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
  margin-bottom: 10px;
  min-height: 36px;
}
.arc-features {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}
.arc-feat-tag {
  font-size: 11px;
  background: #f1f5f9;
  color: #475569;
  padding: 2px 6px;
  border-radius: 4px;
}
.archetype-card.active .arc-feat-tag {
  background: #e0e7ff;
  color: #3730a3;
}
.arc-pain {
  font-size: 11px;
  color: #b91c1c;
  background: #fef2f2;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 6px;
  line-height: 1.4;
}
.pain-label {
  font-weight: 600;
}
.arc-sol {
  font-size: 11px;
  color: #15803d;
  background: #f0fdf4;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 10px;
  line-height: 1.4;
}
.sol-label {
  font-weight: 600;
}
.scenario-banner {
  padding: 10px 16px;
  border: 1px solid;
  border-radius: 8px;
  margin-bottom: 16px;
}
.arc-sample {
  font-size: 11px;
  padding-top: 8px;
  border-top: 1px dashed #e2e8f0;
}
</style>
