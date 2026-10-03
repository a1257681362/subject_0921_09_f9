package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.AgentCreateRequest;
import com.evops.agent.dto.AgentStatusRequest;
import com.evops.agent.entity.Agent;
import com.evops.agent.mapper.AgentMapper;
import com.evops.common.BusinessException;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 代理档案域：代理名全局唯一；loginUsername 绑定自助查账登录账号。
 * 停用只影响新协议配置与新结算认领，不改变已生成的结算快照。
 */
@Service
public class AgentService {

    private final AgentMapper agentMapper;

    public AgentService(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /** 代理建档。 */
    @Transactional(rollbackFor = Exception.class)
    public Agent create(AgentCreateRequest request) {
        String name = request.getAgentName().trim();
        String login = request.getLoginUsername() == null || request.getLoginUsername().trim().isEmpty()
                ? null : request.getLoginUsername().trim();
        String operator = Operators.resolve(request.getOperatorName());

        ensureNameFree(name);
        if (login != null) {
            ensureLoginFree(login);
        }

        Agent agent = new Agent();
        agent.setAgentNo("AG" + agentMapper.nextAgentSeq());
        agent.setAgentName(name);
        agent.setLoginUsername(login);
        agent.setContact(request.getContact());
        agent.setStatus(AgentEnums.AGENT_ACTIVE);
        agent.setOperatorName(operator);
        agent.setRemark(request.getRemark());
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 名称/登录名唯一约束兜底（并发建档）
            throw new BusinessException("代理名称或登录账号已存在：" + name);
        }
        return agent;
    }

    /** 翻查代理，可按状态/关键字（编号、名称）过滤。 */
    public List<Agent> list(String status, String keyword) {
        LambdaQueryWrapper<Agent> w = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            w.eq(Agent::getStatus, status.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            w.and(q -> q.like(Agent::getAgentNo, kw).or().like(Agent::getAgentName, kw));
        }
        return agentMapper.selectList(w.orderByDesc(Agent::getId));
    }

    public Agent requireByNo(String agentNo) {
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentNo, agentNo.trim()));
        if (agent == null) {
            throw new BusinessException("代理不存在: " + agentNo);
        }
        return agent;
    }

    /** 按自助查账登录账号认领代理；查不到返回 null（调用方决定如何拒绝）。 */
    public Agent findByLoginUsername(String loginUsername) {
        if (loginUsername == null) {
            return null;
        }
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getLoginUsername, loginUsername));
    }

    /** 停用：不影响已生成结算快照，只是不能再被新协议版本引用。 */
    @Transactional(rollbackFor = Exception.class)
    public Agent disable(String agentNo, AgentStatusRequest request) {
        Agent agent = requireByNo(agentNo);
        agent.setStatus(AgentEnums.AGENT_DISABLED);
        agent.setRemark(request.getReason() != null ? request.getReason() : agent.getRemark());
        agentMapper.updateById(agent);
        return agent;
    }

    @Transactional(rollbackFor = Exception.class)
    public Agent enable(String agentNo, AgentStatusRequest request) {
        Agent agent = requireByNo(agentNo);
        agent.setStatus(AgentEnums.AGENT_ACTIVE);
        agentMapper.updateById(agent);
        return agent;
    }

    private void ensureNameFree(String name) {
        Long count = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getAgentName, name));
        if (count != null && count > 0) {
            throw new BusinessException("代理「" + name + "」已建档，代理名全局唯一");
        }
    }

    private void ensureLoginFree(String login) {
        Long count = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getLoginUsername, login));
        if (count != null && count > 0) {
            throw new BusinessException("登录账号 " + login + " 已绑定其他代理，一个账号只能绑一个代理");
        }
    }
}
