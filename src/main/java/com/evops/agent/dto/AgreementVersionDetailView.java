package com.evops.agent.dto;

import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgreementVersion;
import lombok.Data;

import java.util.List;

/** 协议版本全量档案：协议头 + 版本 + 点位分成行。 */
@Data
public class AgreementVersionDetailView {
    private AgentAgreement agreement;
    private AgreementVersion version;
    private List<SiteShareView> sites;
}
