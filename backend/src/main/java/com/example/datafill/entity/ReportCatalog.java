package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 报表资产目录实体类
 * 对应表: dwd.dwd_fid_report_catalog
 */
@TableName("dwd.dwd_fid_report_catalog")
public class ReportCatalog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "report_id", type = IdType.INPUT)
    private String reportId;

    @TableField("parent_id")
    private String parentId;

    @TableField("report_name")
    private String reportName;

    private String l1;
    private String l2;
    private String l3;
    private String l4;
    private String l5;
    private String l6;
    private String l7;

    @TableField("report_url")
    private String reportUrl;

    @TableField("full_path")
    private String fullPath;

    @TableField("report_desc")
    private String reportDesc;

    @TableField("domain_l1")
    private String domainL1;

    @TableField("domain_l2")
    private String domainL2;

    @TableField("biz_domain")
    private String bizDomain;

    @TableField("subject_l1")
    private String subjectL1;

    @TableField("subject_l2")
    private String subjectL2;

    @TableField("analysis_cycle")
    private String analysisCycle;

    @TableField("data_granularity")
    private String dataGranularity;

    @TableField("data_source")
    private String dataSource;

    private String owner;

    @TableField("dept_name")
    private String deptName;

    @TableField("use_scope")
    private String useScope;

    private String dimensions;
    private String metrics;

    @TableField("update_frequency")
    private String updateFrequency;

    @TableField("data_update_time")
    private String dataUpdateTime;

    @TableField("is_confidential")
    private String isConfidential;

    private String developer;

    @TableField("lifecycle_status")
    private String lifecycleStatus;

    @TableField("sensitive_level")
    private String sensitiveLevel;

    @TableField("report_create_date")
    private String reportCreateDate;

    @TableField("report_update_date")
    private String reportUpdateDate;

    private String remark;

    @TableField("w_insert_dt")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime wInsertDt;

    @TableField("w_update_dt")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime wUpdateDt;

    @TableField("delete_flag")
    private Boolean deleteFlag;

    // --- 额外 UI 卡片 KPI 汇总扩展字段 (非数据库物理列) ---
    @TableField(exist = false)
    private Integer authUserCnt;

    @TableField(exist = false)
    private Integer authDeptCnt;

    @TableField(exist = false)
    private Double authUsageRate;

    @TableField(exist = false)
    private String lastAccessTime;

    // Getter and Setter
    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getReportName() {
        return reportName;
    }

    public void setReportName(String reportName) {
        this.reportName = reportName;
    }

    public String getL1() {
        return l1;
    }

    public void setL1(String l1) {
        this.l1 = l1;
    }

    public String getL2() {
        return l2;
    }

    public void setL2(String l2) {
        this.l2 = l2;
    }

    public String getL3() {
        return l3;
    }

    public void setL3(String l3) {
        this.l3 = l3;
    }

    public String getL4() {
        return l4;
    }

    public void setL4(String l4) {
        this.l4 = l4;
    }

    public String getL5() {
        return l5;
    }

    public void setL5(String l5) {
        this.l5 = l5;
    }

    public String getL6() {
        return l6;
    }

    public void setL6(String l6) {
        this.l6 = l6;
    }

    public String getL7() {
        return l7;
    }

    public void setL7(String l7) {
        this.l7 = l7;
    }

    public String getReportUrl() {
        return reportUrl;
    }

    public void setReportUrl(String reportUrl) {
        this.reportUrl = reportUrl;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public String getReportDesc() {
        return reportDesc;
    }

    public void setReportDesc(String reportDesc) {
        this.reportDesc = reportDesc;
    }

    public String getDomainL1() {
        return domainL1;
    }

    public void setDomainL1(String domainL1) {
        this.domainL1 = domainL1;
    }

    public String getDomainL2() {
        return domainL2;
    }

    public void setDomainL2(String domainL2) {
        this.domainL2 = domainL2;
    }

    public String getBizDomain() {
        return bizDomain != null ? bizDomain : domainL1;
    }

    public void setBizDomain(String bizDomain) {
        this.bizDomain = bizDomain;
    }

    public String getSubjectL1() {
        return subjectL1;
    }

    public void setSubjectL1(String subjectL1) {
        this.subjectL1 = subjectL1;
    }

    public String getSubjectL2() {
        return subjectL2;
    }

    public void setSubjectL2(String subjectL2) {
        this.subjectL2 = subjectL2;
    }

    public String getAnalysisCycle() {
        return analysisCycle;
    }

    public void setAnalysisCycle(String analysisCycle) {
        this.analysisCycle = analysisCycle;
    }

    public String getDataGranularity() {
        return dataGranularity;
    }

    public void setDataGranularity(String dataGranularity) {
        this.dataGranularity = dataGranularity;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public String getUseScope() {
        return useScope;
    }

    public void setUseScope(String useScope) {
        this.useScope = useScope;
    }

    public String getDimensions() {
        return dimensions;
    }

    public void setDimensions(String dimensions) {
        this.dimensions = dimensions;
    }

    public String getMetrics() {
        return metrics;
    }

    public void setMetrics(String metrics) {
        this.metrics = metrics;
    }

    public String getUpdateFrequency() {
        return updateFrequency;
    }

    public void setUpdateFrequency(String updateFrequency) {
        this.updateFrequency = updateFrequency;
    }

    public String getDataUpdateTime() {
        return dataUpdateTime;
    }

    public void setDataUpdateTime(String dataUpdateTime) {
        this.dataUpdateTime = dataUpdateTime;
    }

    public String getIsConfidential() {
        return isConfidential;
    }

    public void setIsConfidential(String isConfidential) {
        this.isConfidential = isConfidential;
    }

    public String getDeveloper() {
        return developer;
    }

    public void setDeveloper(String developer) {
        this.developer = developer;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getSensitiveLevel() {
        return sensitiveLevel;
    }

    public void setSensitiveLevel(String sensitiveLevel) {
        this.sensitiveLevel = sensitiveLevel;
    }

    public String getReportCreateDate() {
        return reportCreateDate;
    }

    public void setReportCreateDate(String reportCreateDate) {
        this.reportCreateDate = reportCreateDate;
    }

    public String getReportUpdateDate() {
        return reportUpdateDate;
    }

    public void setReportUpdateDate(String reportUpdateDate) {
        this.reportUpdateDate = reportUpdateDate;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getwInsertDt() {
        return wInsertDt;
    }

    public void setwInsertDt(LocalDateTime wInsertDt) {
        this.wInsertDt = wInsertDt;
    }

    public LocalDateTime getwUpdateDt() {
        return wUpdateDt;
    }

    public LocalDateTime getWUpdateDt() {
        return wUpdateDt;
    }

    public void setwUpdateDt(LocalDateTime wUpdateDt) {
        this.wUpdateDt = wUpdateDt;
    }

    public void setWUpdateDt(LocalDateTime wUpdateDt) {
        this.wUpdateDt = wUpdateDt;
    }

    public Boolean getDeleteFlag() {
        return deleteFlag;
    }

    public void setDeleteFlag(Boolean deleteFlag) {
        this.deleteFlag = deleteFlag;
    }

    public Integer getAuthUserCnt() {
        return authUserCnt;
    }

    public void setAuthUserCnt(Integer authUserCnt) {
        this.authUserCnt = authUserCnt;
    }

    public Integer getAuthDeptCnt() {
        return authDeptCnt;
    }

    public void setAuthDeptCnt(Integer authDeptCnt) {
        this.authDeptCnt = authDeptCnt;
    }

    public Double getAuthUsageRate() {
        return authUsageRate;
    }

    public void setAuthUsageRate(Double authUsageRate) {
        this.authUsageRate = authUsageRate;
    }

    public String getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(String lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }
}
