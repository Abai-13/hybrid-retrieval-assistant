package com.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 驳回认领申请的请求体。
 *
 * <p>驳回理由<b>必填</b>，不是走过场：申请人有权知道自己的申请为什么没通过，
 * 否则他只会一遍遍重新提交同一条申请。理由也是这条流转日志里最有价值的内容。
 */
@Data
public class ClaimRejectRequest {

    @NotBlank(message = "请填写驳回理由，申请人需要知道原因")
    @Size(max = 200, message = "驳回理由不能超过 200 字")
    private String reason;
}
