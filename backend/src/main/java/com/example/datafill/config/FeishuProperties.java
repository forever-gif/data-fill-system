package com.example.datafill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书开放平台与知识库同步配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /**
     * 飞书自建应用 App ID (如 cli_a1b2c3d4e5f6)
     */
    private String appId;

    /**
     * 飞书自建应用 App Secret
     */
    private String appSecret;

    /**
     * 飞书知识库空间 ID (Space ID)
     */
    private String wikiSpaceId;

    /**
     * 可选：根节点 Token，留空则默认挂载在知识库根目录
     */
    private String rootNodeToken;

    /**
     * 帆软报表前台基础访问 URL
     */
    private String reportBaseUrl = "https://bi.meiying.homes/webroot/decision/#?activeTab=";
}
