package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理协议主档：一代理一协议。状态随当前版本走：
 * DRAFT 编制中 / EFFECTIVE 生效中 / CLOSED 已封存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_agreement")
public class AgentAgreement extends BaseEntity {
    /** 协议编号 AA+序列，全局唯一 */
    private String agreementNo;
    private Long agentId;
    /** 代理编号快照 */
    private String agentNo;
    /** 代理名称快照 */
    private String agentNameSnapshot;
    /** DRAFT / EFFECTIVE / CLOSED */
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private String operatorName;
    private String remark;
}
