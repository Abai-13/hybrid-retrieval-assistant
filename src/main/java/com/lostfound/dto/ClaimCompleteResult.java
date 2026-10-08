package com.lostfound.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 确认收到物品的结果。
 *
 * <p>{@code closedOthers} 是「这一次连带自动关闭了几条其他申请」。
 * 单独把这个数返回出去，是为了让「自动关闭」这个行为在接口层面<b>可见、可测</b> ——
 * 否则它只是发生在库里的一件小事，前端没法提示，测试也没法断言。
 */
@Data
@AllArgsConstructor
public class ClaimCompleteResult {

    private Long claimId;

    /** 申请最终状态，正常与幂等两条路径都是 COMPLETED */
    private String status;

    /** 物品最终状态，正常与幂等两条路径都是 CLAIMED */
    private String itemStatus;

    /** 本次自动关闭的其他 PENDING 申请条数 */
    private int closedOthers;
}
