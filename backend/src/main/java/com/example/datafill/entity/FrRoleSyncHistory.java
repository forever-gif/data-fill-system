package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帆软用户角色批量同步快照与审计历史
 */
@Data
@TableName("fr_role_sync_history")
public class FrRoleSyncHistory {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String batchNo;
    private String operator;
    private String sourceTitle;
    private String syncMode; // incremental | overwrite
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;

    /** JSON 序列化存储变更前的原始角色与变更后生效角色快照 */
    private String snapshotData;

    /** COMPLETED (已同步) | ROLLED_BACK (已回滚) | FAILED (同步失败) */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime rollbackTime;
}
