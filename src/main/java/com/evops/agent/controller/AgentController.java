package com.evops.agent.controller;

import com.evops.agent.dto.AgentCreateRequest;
import com.evops.agent.dto.AgentStatusRequest;
import com.evops.agent.entity.Agent;
import com.evops.agent.service.AgentService;
import com.evops.agent.support.AgentAccess;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** F9 代理档案：建档 / 翻查 / 停用 / 启用（管理动作仅财务/系统）。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentAccess access;

    public AgentController(AgentService agentService, AgentAccess access) {
        this.agentService = agentService;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<Agent> create(@Valid @RequestBody AgentCreateRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agentService.create(request));
    }

    /** 翻查代理：?status=&keyword=（keyword 匹配编号/名称）。仅财务/系统。 */
    @GetMapping
    public ApiResponse<List<Agent>> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword) {
        access.requireFinance();
        return ApiResponse.ok(agentService.list(status, keyword));
    }

    @PostMapping("/{agentNo}/disable")
    public ApiResponse<Agent> disable(@PathVariable String agentNo,
                                      @RequestBody(required = false) AgentStatusRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agentService.disable(agentNo,
                request != null ? request : new AgentStatusRequest()));
    }

    @PostMapping("/{agentNo}/enable")
    public ApiResponse<Agent> enable(@PathVariable String agentNo,
                                     @RequestBody(required = false) AgentStatusRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agentService.enable(agentNo,
                request != null ? request : new AgentStatusRequest()));
    }
}
