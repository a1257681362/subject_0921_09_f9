package com.evops.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 版本内一个点位的分成行：代理分成比例 (0,1]，平台得剩余。 */
@Data
public class SiteShareSpec {
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    @NotNull(message = "代理分成比例不能为空")
    private BigDecimal shareRate;
}
