package com.lostfound.dto;

import lombok.Data;

/**
 * 认领申请的分页查询参数。
 * <p>比 {@link ItemPageQuery} 简单得多：认领列表只有「按状态筛」一个维度，
 * 时间一律倒序，所以不需要 type/category/keyword/排序方向。
 */
@Data
public class ClaimPageQuery {

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = 10;

    /** 状态筛选：PENDING / APPROVED / REJECTED / CANCELLED / COMPLETED，不传则全部 */
    private String status;
}
