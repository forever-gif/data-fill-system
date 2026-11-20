package com.example.datafill.util;

public class SqlUtil {

    /**
     * 校验并安全包裹单个 SQL 标识符（兼容英文、数字、下划线、连字符及中文表名，拒绝引号逃逸与分号/注释注入）
     */
    public static String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        if (trimmed.indexOf('"') >= 0
                || trimmed.indexOf(';') >= 0
                || trimmed.indexOf('\0') >= 0
                || trimmed.indexOf('\r') >= 0
                || trimmed.indexOf('\n') >= 0
                || trimmed.contains("--")
                || trimmed.contains("/*")
                || trimmed.contains("*/")) {
            throw new IllegalArgumentException("检测到非法 SQL 标识符: " + trimmed);
        }
        return "\"" + trimmed + "\"";
    }

    public static String quoteTable(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            return quoteIdentifier(parts[0]) + "." + quoteIdentifier(parts[1]);
        }
        return quoteIdentifier(tableName);
    }
    
    public static String extractSchema(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            return tableName.split("\\.", 2)[0];
        }
        return null;
    }
    
    public static String extractTable(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            return tableName.split("\\.", 2)[1];
        }
        return tableName;
    }
}
