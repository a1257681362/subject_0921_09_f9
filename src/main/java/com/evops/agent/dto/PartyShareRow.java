package com.evops.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算按方汇总行：每个代理一行 + 平台一行（PLATFORM）。
 * 代理视角下只含自己那一行；平台行的 shareAmount 为残余法实得（已含舍入尾差）。
 */
@Data
public class PartyShareRow {
    /** AGENT 代理 / PLATFORM 平台 */
    private String partyType;
    private Long agentId;
    private String agentNo;
    private String agentName;
    /** 归到该方的流水额 */
    private BigDecimal flowAmount;
    /** 该方分得金额 */
    private BigDecimal shareAmount;
    /** 该方按比例独立四舍五入额（仅平台行用于展示尾差口径，代理行为空） */
    private BigDecimal roundedAmount;
    /** 舍入尾差（仅平台行） */
    private BigDecimal roundingDiff;
    private Integer itemCount;
}
