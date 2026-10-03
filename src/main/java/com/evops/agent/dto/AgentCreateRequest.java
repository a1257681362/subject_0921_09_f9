package com.evops.agent.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 代理建档：代理名全局唯一；loginUsername 留空表示不开自助查账登录。 */
@Data
public class AgentCreateRequest {
    @NotBlank(message = "代理名称不能为空")
    private String agentName;
    /** 自助查账登录账号（需与 Shiro 账号一致，如 agent1）；可空 */
    private String loginUsername;
    private String contact;
    private String operatorName;
    private String remark;
}
