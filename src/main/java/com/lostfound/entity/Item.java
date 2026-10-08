package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("item")
public class Item {

    /** 寻求帮助：我丢了东西 */
    public static final String TYPE_LOST = "LOST";
    /** 提供帮助：我捡到东西 */
    public static final String TYPE_FOUND = "FOUND";

    /** 待认领：无人申请 */
    public static final String STATUS_UNCLAIMED = "UNCLAIMED";
    /** 认领中：至少存在一条活跃（PENDING/APPROVED）的认领申请 */
    public static final String STATUS_CLAIMING = "CLAIMING";
    /** 已认领：认领流程走完，物品已归还 */
    public static final String STATUS_CLAIMED = "CLAIMED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** LOST: 寻物启事, FOUND: 失物招领 */
    private String type;

    /** 类别: 电子产品/证件/衣物/书籍/其他 */
    private String category;

    private String location;

    private String description;

    private String imageUrl;

    private String contact;

    /** UNCLAIMED: 待认领, CLAIMING: 认领中, CLAIMED: 已认领 */
    private String status;

    /** 乐观锁版本号 — 每次更新自增 1，防止并发覆盖 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
