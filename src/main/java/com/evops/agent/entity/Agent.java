package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理档案。一个代理一行；loginUsername 绑定自助查账登录账号；
 * 一个代理最多一份协议（见 {@link AgentAgreement}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent")
public class Agent extends BaseEntity {
    /** 代理编号 AG+序列，全局唯一 */
    private String agentNo;
    /** 代理名称，全局唯一 */
    private String agentName;
    /** 自助查账登录账号（唯一，可空=不开登录） */
    private String loginUsername;
    private String contact;
    /** ACTIVE 在册 / DISABLED 停用 */
    private String status;
    /** 当前协议 id（建档协议后回写） */
    private Long currentAgreementId;
    private String operatorName;
    private String remark;
}
