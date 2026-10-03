package com.evops.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;

/**
 * 草稿版本整表配置（替换式）：一次性提交版本内全部点位分成行。
 * 只准改 DRAFT；生效版冻结，调整请另起新版本。
 */
@Data
public class AgreementVersionConfigureRequest {
    @NotEmpty(message = "至少要给一个点位挂代理分成")
    @Valid
    private List<SiteShareSpec> sites = new ArrayList<>();
    private String operatorName;
    private String remark;
}
