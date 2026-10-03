package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 结算流水明细：每张授信上刊单一行，协议号/版本/比例/代理名生成时固化。
 * 逐单恒等：flow = agent + platform（残余法）；
 * roundingDiff = flow - agent - platformRounded，标出独立取整产生的一分钱尾差。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settlement_item")
public class SettlementItem extends BaseEntity {
    private Long settlementId;
    private String period;
    private Long orderId;
    private String orderNo;
    private Long siteId;
    private String siteNo;
    private String creativeName;
    /** 未挂协议时为 null */
    private Long agentId;
    private String agentNo;
    private String agentNameSnapshot;
    private String agreementNo;
    private Integer agreementVersionNo;
    private BigDecimal shareRate;
    /** 1 已挂协议 / 0 未挂协议（全额归平台） */
    private Integer assigned;
    private BigDecimal flowAmount;
    private BigDecimal agentAmount;
    /** 残余法平台实得 = flow - agent */
    private BigDecimal platformAmount;
    /** 平台按 1-shareRate 独立四舍五入额 */
    private BigDecimal platformRoundedAmount;
    /** 一分钱尾差 */
    private BigDecimal roundingDiff;
}
