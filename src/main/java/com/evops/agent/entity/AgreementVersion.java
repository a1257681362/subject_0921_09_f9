package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 代理协议版本（版本链，只追加）。
 * 有效期闭开区间 [effectiveFromPeriod, effectiveToPeriod)，期间为 yyyy-MM；
 * effectiveToPeriod 为空表示长期有效，发布新版时回填旧版。
 * 状态 DRAFT 草稿 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 / REVOKED 已作废。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_agreement_version")
public class AgreementVersion extends BaseEntity {
    private Long agreementId;
    private Integer versionNo;
    /** DRAFT / EFFECTIVE / SUPERSEDED / REVOKED */
    private String status;
    /** 生效起始期间（含），yyyy-MM */
    private String effectiveFromPeriod;
    /** 生效截止期间（不含），yyyy-MM；空=长期 */
    private String effectiveToPeriod;
    private LocalDateTime publishTime;
    private LocalDateTime revokeTime;
    private String revokeReason;
    private String operatorName;
    private String remark;
}
