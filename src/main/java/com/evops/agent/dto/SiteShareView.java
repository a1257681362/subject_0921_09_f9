package com.evops.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 版本点位分成行视图（带点位档案信息）。 */
@Data
public class SiteShareView {
    private Long siteId;
    private String siteNo;
    private String sizeSpec;
    private String orientation;
    private String businessDistrict;
    private BigDecimal shareRate;
}
