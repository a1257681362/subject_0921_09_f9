package com.evops.agent.dto;

import lombok.Data;

/** 停用/启用代理的经手动作。 */
@Data
public class AgentStatusRequest {
    private String operatorName;
    private String reason;
}
