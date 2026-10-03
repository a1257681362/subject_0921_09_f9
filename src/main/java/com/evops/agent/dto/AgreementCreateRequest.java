package com.evops.agent.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 建代理协议：一代理一协议，建档自动带 v1 草稿，首签起始期必填。 */
@Data
public class AgreementCreateRequest {
    @NotBlank(message = "代理编号不能为空")
    private String agentNo;
    /** 首版生效起始期间 yyyy-MM（含当月），首签允许补录过去期 */
    @NotBlank(message = "首签生效起始期间不能为空（yyyy-MM）")
    private String effectiveFromPeriod;
    private String operatorName;
    private String remark;
}
