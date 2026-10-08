package com.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交认领申请的请求体。
 */
@Data
public class ClaimApplyRequest {

    /** 要认领的物品ID */
    @NotNull(message = "物品ID不能为空")
    private Long itemId;

    /**
     * 物品特征证明 —— 拾到者据判断"这确实是你的东西"的唯一依据。
     * <p>设下限是因为「<b>是我的</b>」这种一句话证明等于没证明，审核时无法比对；
     * 设上限是因为它是个 VARCHAR(500) 字段，且太长对审核也没帮助。
     */
    @NotBlank(message = "请填写物品特征证明")
    @Size(min = 10, max = 500, message = "特征证明请填写 10~500 字，描述得越具体越容易通过")
    private String proofText;

    /** 证明图片URL（可选） */
    @Size(max = 500, message = "图片地址过长")
    private String proofImage;

    /** 申请人为本次认领留的联系方式（可选，为空时取账号手机号） */
    @Size(max = 100, message = "联系方式不能超过 100 字")
    private String contact;
}
