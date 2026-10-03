package com.evops.agent.controller;

import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementVersionActionRequest;
import com.evops.agent.dto.AgreementVersionConfigureRequest;
import com.evops.agent.dto.AgreementVersionDetailView;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgreementVersion;
import com.evops.agent.service.AgreementService;
import com.evops.agent.support.AgentAccess;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * F9 代理协议：建档（自动 v1 草稿）→ 草稿配置点位分成 → 发布生效；
 * 调整另起新版本（从下期生效）→ 发布，旧版置 SUPERSEDED；生效版可作废。
 * 管理动作仅财务/系统。
 */
@RestController
@RequestMapping("/api/agent/agreements")
public class AgreementController {

    private final AgreementService agreementService;
    private final AgentAccess access;

    public AgreementController(AgreementService agreementService, AgentAccess access) {
        this.agreementService = agreementService;
        this.access = access;
    }

    /** 建档：一代理一协议，自动带 v1 草稿，首签起始期可补录过去期。 */
    @PostMapping
    public ApiResponse<AgentAgreement> create(@Valid @RequestBody AgreementCreateRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.createAgreement(request));
    }

    /** 翻查协议：?agentNo=&status=。 */
    @GetMapping
    public ApiResponse<List<AgentAgreement>> list(@RequestParam(required = false) String agentNo,
                                                  @RequestParam(required = false) String status) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.listAgreements(agentNo, status));
    }

    /** 协议全量档案（当前版本 + 版本链）。 */
    @GetMapping("/{id}")
    public ApiResponse<AgreementVersionDetailView> detail(@PathVariable Long id) {
        access.requireFinance();
        AgentAgreement agreement = agreementService.requireAgreement(id);
        Long versionId = agreement.getCurrentVersionId();
        if (versionId == null) {
            // 生效版被作废后无当前版本：回最新版本给档案页
            versionId = agreementService.listVersions(id).get(0).getId();
        }
        return ApiResponse.ok(agreementService.versionDetail(versionId));
    }

    /** 版本链翻查。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<AgreementVersion>> versions(@PathVariable Long id) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.listVersions(id));
    }

    /** 版本详情（点位分成行 + 有效期）。 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<AgreementVersionDetailView> versionDetail(@PathVariable Long versionId) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.versionDetail(versionId));
    }

    /** 另起新版本：从下期生效（起始期必须晚于当前期），自动拷贝上一版点位分成。 */
    @PostMapping("/{id}/versions")
    public ApiResponse<AgreementVersion> newVersion(
            @PathVariable Long id,
            @Valid @RequestBody AgreementVersionActionRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.newVersion(id, request));
    }

    /** 草稿整表配置点位分成（替换式）。 */
    @PutMapping("/versions/{versionId}")
    public ApiResponse<AgreementVersion> configure(
            @PathVariable Long versionId,
            @Valid @RequestBody AgreementVersionConfigureRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.configure(versionId, request));
    }

    /** 当版发布：校验点位行与跨代理同期冲突，旧生效版置 SUPERSEDED。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<AgreementVersion> publish(
            @PathVariable Long versionId,
            @RequestBody(required = false) AgreementVersionActionRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.publish(versionId,
                request != null ? request : new AgreementVersionActionRequest()));
    }

    /** 生效版本作废（留底不删，之后期间不再参与结算）。 */
    @PostMapping("/versions/{versionId}/revoke")
    public ApiResponse<AgreementVersion> revoke(
            @PathVariable Long versionId,
            @RequestBody(required = false) AgreementVersionActionRequest request) {
        access.requireFinance();
        return ApiResponse.ok(agreementService.revoke(versionId,
                request != null ? request : new AgreementVersionActionRequest()));
    }
}
