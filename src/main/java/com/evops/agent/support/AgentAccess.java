package com.evops.agent.support;

import com.evops.agent.entity.Agent;
import com.evops.agent.service.AgentService;
import com.evops.common.BusinessException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Component;

/**
 * Web 请求侧的代理域鉴权帮手：从当前 Shiro 主体解析结算视角、校验财务动作。
 * 服务层不碰 Shiro，隔离规则在无 Shiro 的集成测试里可用显式 SettlementViewer 验证。
 */
@Component
public class AgentAccess {

    private final AgentService agentService;

    public AgentAccess(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 解析当前登录主体的结算视角；未登录/无权角色抛业务异常。 */
    public SettlementViewer currentViewer() {
        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated() || subject.getPrincipal() == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        String name = subject.getPrincipal().toString();
        if (subject.hasRole("system") || subject.hasRole("finance")) {
            return SettlementViewer.finance(name);
        }
        if (subject.hasRole("agent")) {
            Agent agent = agentService.findByLoginUsername(name);
            if (agent == null) {
                throw new BusinessException("代理账号尚未绑定代理档案，无法查账");
            }
            return SettlementViewer.agent(name, agent.getId());
        }
        throw new BusinessException("当前账号无权访问代理分账（仅财务/系统与代理账号可访问）");
    }

    /** 代理建档/协议配置等管理动作：仅财务/系统。 */
    public void requireFinance() {
        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated()
                || !(subject.hasRole("system") || subject.hasRole("finance"))) {
            throw new BusinessException("只有财务/系统可以管理代理档案与代理协议");
        }
    }
}
