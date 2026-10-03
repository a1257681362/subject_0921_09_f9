package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 版本点位分成行：版本内每个点位一行，shareRate 为代理分成比例，平台得 1-shareRate。
 * 发布后随版本冻结；调价/改点位只能另起新版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agreement_site")
public class AgreementSite extends BaseEntity {
    private Long versionId;
    private Long agreementId;
    private Long agentId;
    private Long siteId;
    /** 点位编号快照 */
    private String siteNo;
    /** 代理分成比例（0,1]，如 0.3000 = 30% */
    private BigDecimal shareRate;
}
