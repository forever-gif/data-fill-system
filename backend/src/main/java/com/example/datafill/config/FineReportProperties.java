package com.example.datafill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 帆软 FineReport 11.5.6 决策平台配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "finereport")
public class FineReportProperties {

    /**
     * 帆软决策平台访问基地址 (如 http://8.136.2.153:8081/webroot/decision)
     */
    private String baseUrl = "http://8.136.2.153:8081/webroot/decision";

    /**
     * 决策平台超级管理员用户名
     */
    private String adminUsername = "admin";

    /**
     * 决策平台管理员密码
     */
    private String adminPassword = "";

    /**
     * 请求超时时间 (秒)
     */
    private int timeoutSeconds = 15;
}
