package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算快照头：一期一条，生成即 CONFIRMED 冻结，没有改单/重算接口（历史期不重算）。
 * 恒有 totalFlow = totalAgent + totalPlatform；totalRoundingDiff 为舍入尾差（归平台，单列标出）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settlement")
public class Settlement extends BaseEntity {
    /** 结算单号 ST+序列，全局唯一 */
    private String settlementNo;
    /** 归属期间 yyyy-MM，全局唯一 */
    private String period;
    /** 固定 CONFIRMED */
    private String status;
    private BigDecimal totalFlowAmount;
    private BigDecimal totalAgentAmount;
    private BigDecimal totalPlatformAmount;
    /** 平台按比例独立四舍五入的合计（展示口径） */
    private BigDecimal totalPlatformRoundedAmount;
    /** 舍入尾差合计 = totalFlow - totalAgent - totalPlatformRounded，多为 ±0.01 */
    private BigDecimal totalRoundingDiff;
    /** 未挂协议、全额归平台的流水额 */
    private BigDecimal unassignedFlowAmount;
    private Integer itemCount;
    private LocalDateTime generatedTime;
    private String operatorName;
    private String remark;
}
