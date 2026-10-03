package com.evops.agent.dto;

import lombok.Data;

/**
 * 版本动作（发布 / 作废 / 封存）经手信息。
 * 另起新版本时 effectiveFromPeriod 必填：中途调整从下期生效，必须晚于当前期间。
 */
@Data
public class AgreementVersionActionRequest {
    /** 另起新版本的生效起始期间 yyyy-MM（发布/作废/封存不用） */
    private String effectiveFromPeriod;
    private String operatorName;
    private String reason;
}
