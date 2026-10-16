package com.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 撤回某个已记住的条件（用户点了「当前条件」标签上的 ×）。
 */
@Data
public class RetractRequest {

    /** 要删除的条件名，如「颜色」「地点」「主体」 */
    @NotBlank(message = "field 不能为空")
    @Size(max = 12, message = "field 过长")
    private String field;
}
