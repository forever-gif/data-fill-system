package com.example.datafill.controller;

import com.example.datafill.entity.ReportCatalog;
import com.example.datafill.service.ReportCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表资产目录 Rest 控制器
 */
@RestController
@RequestMapping("/api/report/catalog")
public class ReportCatalogController {

    @Autowired
    private ReportCatalogService reportCatalogService;

    /**
     * 根据 reportId 查询报表资产卡片详情
     * GET /api/report/catalog/{reportId}
     */
    @GetMapping("/{reportId}")
    public ResponseEntity<ReportCatalog> getReportById(@PathVariable("reportId") String reportId) {
        ReportCatalog catalog = reportCatalogService.getReportById(reportId);
        return ResponseEntity.ok(catalog);
    }

    /**
     * 分页与多维条件查询报表资产列表
     * GET /api/report/catalog/page?page=1&size=20&query=xxx&dept=yyy&status=zzz&l1=aaa&l3=bbb&l4=ccc
     */
    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> pageCatalogs(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "l1", required = false) String l1,
            @RequestParam(value = "l3", required = false) String l3,
            @RequestParam(value = "l4", required = false) String l4,
            @RequestParam(value = "dept", required = false) String dept,
            @RequestParam(value = "status", required = false) String status) {
        Map<String, Object> pageData = reportCatalogService.pageCatalogs(query, l1, l3, l4, dept, status, page, size);
        return ResponseEntity.ok(pageData);
    }

    /**
     * 获取所有报表资产列表 (直接从 dwd.dwd_fid_report_catalog 表实时查询)
     * GET /api/report/catalog/list
     */
    @GetMapping("/list")
    public ResponseEntity<List<ReportCatalog>> listCatalogs() {
        List<ReportCatalog> list = reportCatalogService.listCatalogs();
        return ResponseEntity.ok(list);
    }
}
