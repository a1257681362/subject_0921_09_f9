package com.evops.agent.support;

import com.evops.common.BusinessException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * 结算数据隔离视角：代理只能查自己的账单，别家的看不见。
 *
 * <ul>
 *   <li>财务 / 系统（finance、system 角色）：all=true，可生成结算、看全部代理与平台行；</li>
 *   <li>代理（agent 角色）：按登录账号绑定唯一代理（t_agent.login_username），
 *       只读自己的汇总/明细/CSV，看不到流水总额、平台分成与别家代理；</li>
 *   <li>其余角色调结算接口直接拒收。</li>
 * </ul>
 *
 * 无 Shiro 的集成测试可显式 {@link #finance(String)} / {@link #agent(String, Long)} 构造。
 */
public class SettlementViewer {

    private final String username;
    /** 财务/系统视角：不受归属限制，可生成结算 */
    private final boolean all;
    /** 代理视角绑定的代理 id；财务视角为 null */
    private final Long agentId;

    private SettlementViewer(String username, boolean all, Long agentId) {
        this.username = username;
        this.all = all;
        this.agentId = agentId;
    }

    public static SettlementViewer finance(String username) {
        return new SettlementViewer(username, true, null);
    }

    public static SettlementViewer agent(String username, Long agentId) {
        if (agentId == null) {
            throw new BusinessException("代理账号尚未绑定代理档案，无法查账");
        }
        return new SettlementViewer(username, false, agentId);
    }

    /**
     * 从当前 Shiro 登录主体构造视角。
     *
     * @param boundAgentId 代理角色登录账号绑定的代理 id（由服务层按 login_username 查出后传入）；
     *                     财务/系统角色不需要
     */
    public static SettlementViewer current(Long boundAgentId) {
        Subject subject;
        try {
            subject = SecurityUtils.getSubject();
        } catch (Throwable e) {
            return null;
        }
        if (subject == null || !subject.isAuthenticated() || subject.getPrincipal() == null) {
            return null;
        }
        String name = subject.getPrincipal().toString();
        if (subject.hasRole("system") || subject.hasRole("finance")) {
            return finance(name);
        }
        if (subject.hasRole("agent")) {
            return agent(name, boundAgentId);
        }
        throw new BusinessException("当前账号无权查看分账结算（仅财务/系统与代理账号可访问）");
    }

    /** 财务动作（生成结算）鉴权。 */
    public void requireFinance() {
        if (!all) {
            throw new BusinessException("只有财务/系统可以生成结算，代理只能查看自己的账单");
        }
    }

    /** 明细归属校验：代理视角访问非自己代理的单据即拒。 */
    public void checkOwn(Long rowAgentId) {
        if (!all && !agentId.equals(rowAgentId)) {
            throw new BusinessException("该结算单不在你名下，无权查看");
        }
    }

    public String getUsername() { return username; }
    public boolean isAll() { return all; }
    public Long getAgentId() { return agentId; }
    /** 是否代理（受限）视角 */
    public boolean isAgentScoped() { return !all; }
}
