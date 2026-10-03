package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementVersionActionRequest;
import com.evops.agent.dto.AgreementVersionConfigureRequest;
import com.evops.agent.dto.AgreementVersionDetailView;
import com.evops.agent.dto.SiteShareSpec;
import com.evops.agent.dto.SiteShareView;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgreementSite;
import com.evops.agent.entity.AgreementVersion;
import com.evops.agent.mapper.AgentAgreementMapper;
import com.evops.agent.mapper.AgentMapper;
import com.evops.agent.mapper.AgreementSiteMapper;
import com.evops.agent.mapper.AgreementVersionMapper;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代理协议配置域：一代理一协议，建档自动 v1 草稿；草稿整表配点位分成，发布后冻结。
 *
 * 口径：
 * 1. 点位分成：版本内每个点位一行，一个点位挂一个代理分成比例（(0,1]，平台得剩余）；
 *    点位必须已挂牌，版本内不重复。
 * 2. 有效期按归属期间（yyyy-MM）闭开区间；v1 首签允许补录过去期，
 *    之后另起新版本的生效起始期必须晚于当前期——中途调整从下期生效。
 * 3. 同一点位在同一归属期间不得挂在两个不同代理的生效版本上（发布时按期间窗口校验冲突）。
 * 4. 新版发布：旧生效版置 SUPERSEDED 并回填 effective_to_period=新版起始期；历史期仍按旧版窗口结算。
 * 5. 生效版可作废（REVOKED，留底不删），作废后不再参与任何期间结算。
 */
@Service
public class AgreementService {

    private final AgentMapper agentMapper;
    private final AgentAgreementMapper agreementMapper;
    private final AgreementVersionMapper versionMapper;
    private final AgreementSiteMapper siteShareMapper;
    private final SiteMapper siteMapper;

    public AgreementService(AgentMapper agentMapper,
                            AgentAgreementMapper agreementMapper,
                            AgreementVersionMapper versionMapper,
                            AgreementSiteMapper siteShareMapper,
                            SiteMapper siteMapper) {
        this.agentMapper = agentMapper;
        this.agreementMapper = agreementMapper;
        this.versionMapper = versionMapper;
        this.siteShareMapper = siteShareMapper;
        this.siteMapper = siteMapper;
    }

    /** 建代理协议：一代理一协议，自动带 v1 草稿，首签起始期必填（可补录过去期）。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentAgreement createAgreement(AgreementCreateRequest request) {
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentNo, request.getAgentNo().trim()));
        if (agent == null) {
            throw new BusinessException("代理不存在: " + request.getAgentNo());
        }
        if (!AgentEnums.AGENT_ACTIVE.equals(agent.getStatus())) {
            throw new BusinessException("代理已停用，不能新建协议：" + agent.getAgentName());
        }
        Long exists = agreementMapper.selectCount(new LambdaQueryWrapper<AgentAgreement>()
                .eq(AgentAgreement::getAgentId, agent.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException("代理「" + agent.getAgentName() + "」已有协议，一个代理只能有一份协议，调价请另起新版本");
        }
        String fromPeriod = normalizePeriod(request.getEffectiveFromPeriod());
        String operator = Operators.resolve(request.getOperatorName());

        AgentAgreement agreement = new AgentAgreement();
        agreement.setAgreementNo("AA" + agreementMapper.nextAgreementSeq());
        agreement.setAgentId(agent.getId());
        agreement.setAgentNo(agent.getAgentNo());
        agreement.setAgentNameSnapshot(agent.getAgentName());
        agreement.setStatus(AgentEnums.AGREEMENT_DRAFT);
        agreement.setOperatorName(operator);
        agreement.setRemark(request.getRemark());
        agreementMapper.insert(agreement);

        AgreementVersion v1 = new AgreementVersion();
        v1.setAgreementId(agreement.getId());
        v1.setVersionNo(AgentEnums.FIRST_VERSION_NO);
        v1.setStatus(AgentEnums.VERSION_DRAFT);
        v1.setEffectiveFromPeriod(fromPeriod);
        v1.setOperatorName(operator);
        versionMapper.insert(v1);

        // 回写代理当前协议指针
        Agent agentPatch = new Agent();
        agentPatch.setId(agent.getId());
        agentPatch.setCurrentAgreementId(agreement.getId());
        agentMapper.updateById(agentPatch);
        return agreement;
    }

    /** 翻查协议，可按代理编号/状态过滤。 */
    public List<AgentAgreement> listAgreements(String agentNo, String status) {
        LambdaQueryWrapper<AgentAgreement> w = new LambdaQueryWrapper<>();
        if (agentNo != null && !agentNo.trim().isEmpty()) {
            Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getAgentNo, agentNo.trim()));
            if (agent == null) {
                return new ArrayList<>();
            }
            w.eq(AgentAgreement::getAgentId, agent.getId());
        }
        if (status != null && !status.trim().isEmpty()) {
            w.eq(AgentAgreement::getStatus, status.trim());
        }
        return agreementMapper.selectList(w.orderByDesc(AgentAgreement::getId));
    }

    public AgentAgreement requireAgreement(Long agreementId) {
        AgentAgreement agreement = agreementMapper.selectById(agreementId);
        if (agreement == null) {
            throw new BusinessException("代理协议不存在: id=" + agreementId);
        }
        return agreement;
    }

    /** 版本链翻查：新版本号在前。 */
    public List<AgreementVersion> listVersions(Long agreementId) {
        requireAgreement(agreementId);
        return versionMapper.selectList(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .orderByDesc(AgreementVersion::getVersionNo));
    }

    /**
     * 另起新版本（调整入口）：从上一版拷贝点位分成做起点，拿到可改草稿。
     * 中途调整从下期生效：起始期必须晚于当前归属期间；同时只准一个草稿。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgreementVersion newVersion(Long agreementId, AgreementVersionActionRequest request) {
        AgentAgreement agreement = requireAgreement(agreementId);
        if (AgentEnums.AGREEMENT_CLOSED.equals(agreement.getStatus())) {
            throw new BusinessException("协议已封存，不能另起新版本");
        }
        Long drafts = versionMapper.selectCount(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .eq(AgreementVersion::getStatus, AgentEnums.VERSION_DRAFT));
        if (drafts != null && drafts > 0) {
            throw new BusinessException("还有未发布的草稿版本，请先发布或作废该草稿后再起新版");
        }
        String fromPeriod = normalizePeriod(request.getEffectiveFromPeriod());
        String currentPeriod = YearMonth.now().toString();
        if (fromPeriod.compareTo(currentPeriod) <= 0) {
            throw new BusinessException("协议中途调整只能从下期生效：新版本起始期 " + fromPeriod
                    + " 必须晚于当前期间 " + currentPeriod);
        }
        String operator = Operators.resolve(request.getOperatorName());

        List<AgreementVersion> all = versionMapper.selectList(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .orderByDesc(AgreementVersion::getVersionNo));
        AgreementVersion latest = all.isEmpty() ? null : all.get(0);
        int nextNo = (latest == null ? 0 : latest.getVersionNo()) + 1;

        AgreementVersion version = new AgreementVersion();
        version.setAgreementId(agreementId);
        version.setVersionNo(nextNo);
        version.setStatus(AgentEnums.VERSION_DRAFT);
        version.setEffectiveFromPeriod(fromPeriod);
        version.setOperatorName(operator);
        version.setRemark(request.getReason());
        versionMapper.insert(version);

        if (latest != null) {
            copySites(latest.getId(), agreement, version.getId());
        }
        return version;
    }

    /**
     * 草稿整表配置（替换式）：版本内全部点位分成一次性提交。
     * 只准改 DRAFT；生效版/历史版一律拒绝——调整走新版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgreementVersion configure(Long versionId, AgreementVersionConfigureRequest request) {
        AgreementVersion version = requireVersion(versionId);
        if (!AgentEnums.VERSION_DRAFT.equals(version.getStatus())) {
            throw new BusinessException("只有草稿版本可配置；该版本是 " + version.getStatus()
                    + " 状态，已冻结，调整请另起新版本");
        }
        Map<String, Site> sites = validateSiteShares(request.getSites());
        String operator = Operators.resolve(request.getOperatorName());

        version.setOperatorName(operator);
        if (request.getRemark() != null) {
            version.setRemark(request.getRemark());
        }
        versionMapper.updateById(version);

        siteShareMapper.delete(new LambdaQueryWrapper<AgreementSite>()
                .eq(AgreementSite::getVersionId, versionId));

        AgentAgreement agreement = requireAgreement(version.getAgreementId());
        for (SiteShareSpec spec : request.getSites()) {
            Site site = sites.get(spec.getSiteNo().trim());
            AgreementSite row = new AgreementSite();
            row.setVersionId(versionId);
            row.setAgreementId(agreement.getId());
            row.setAgentId(agreement.getAgentId());
            row.setSiteId(site.getId());
            row.setSiteNo(site.getSiteNo());
            row.setShareRate(spec.getShareRate().setScale(4, RoundingMode.HALF_UP));
            siteShareMapper.insert(row);
        }
        return version;
    }

    /**
     * 发布草稿：校验已配点位行；按有效期窗口校验跨代理点位冲突；
     * 本协议旧生效版置 SUPERSEDED 并回填截止期=新版起始期，协议头置 EFFECTIVE。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgreementVersion publish(Long versionId, AgreementVersionActionRequest request) {
        AgreementVersion version = requireVersion(versionId);
        AgentAgreement agreement = requireAgreement(version.getAgreementId());
        if (AgentEnums.AGREEMENT_CLOSED.equals(agreement.getStatus())) {
            throw new BusinessException("协议已封存，不能发布新版本");
        }
        if (!AgentEnums.VERSION_DRAFT.equals(version.getStatus())) {
            throw new BusinessException("只有草稿版本可发布，该版本当前状态: " + version.getStatus());
        }
        List<AgreementSite> rows = siteShareMapper.selectList(new LambdaQueryWrapper<AgreementSite>()
                .eq(AgreementSite::getVersionId, versionId));
        if (rows.isEmpty()) {
            throw new BusinessException("还没给任何点位挂代理分成，不能发布");
        }
        Operators.resolve(request.getOperatorName());
        checkCrossAgentConflict(agreement.getId(), version.getEffectiveFromPeriod(), rows);

        LocalDateTime now = LocalDateTime.now();
        version.setStatus(AgentEnums.VERSION_EFFECTIVE);
        version.setPublishTime(now);
        versionMapper.updateById(version);

        // 本协议上一生效版：置 SUPERSEDED，有效期截止到新版起始期（闭开），历史期间仍按旧版窗口结算
        if (agreement.getCurrentVersionId() != null) {
            AgreementVersion previous = versionMapper.selectById(agreement.getCurrentVersionId());
            if (previous != null && AgentEnums.VERSION_EFFECTIVE.equals(previous.getStatus())) {
                AgreementVersion superseded = new AgreementVersion();
                superseded.setId(previous.getId());
                superseded.setStatus(AgentEnums.VERSION_SUPERSEDED);
                superseded.setEffectiveToPeriod(version.getEffectiveFromPeriod());
                versionMapper.updateById(superseded);
            }
        }

        AgentAgreement patch = new AgentAgreement();
        patch.setId(agreement.getId());
        patch.setStatus(AgentEnums.AGREEMENT_EFFECTIVE);
        patch.setCurrentVersionId(version.getId());
        patch.setCurrentVersionNo(version.getVersionNo());
        agreementMapper.updateById(patch);
        return version;
    }

    /** 作废生效版本：置 REVOKED 留底，之后任何期间都不再参与结算；协议回到编制中。 */
    @Transactional(rollbackFor = Exception.class)
    public AgreementVersion revoke(Long versionId, AgreementVersionActionRequest request) {
        AgreementVersion version = requireVersion(versionId);
        if (!AgentEnums.VERSION_EFFECTIVE.equals(version.getStatus())) {
            throw new BusinessException("只有生效中的版本可作废，该版本当前状态: " + version.getStatus());
        }
        Operators.resolve(request.getOperatorName());

        version.setStatus(AgentEnums.VERSION_REVOKED);
        version.setRevokeTime(LocalDateTime.now());
        version.setRevokeReason(request.getReason());
        versionMapper.updateById(version);

        agreementMapper.update(null, new LambdaUpdateWrapper<AgentAgreement>()
                .eq(AgentAgreement::getId, version.getAgreementId())
                .set(AgentAgreement::getStatus, AgentEnums.AGREEMENT_DRAFT)
                .set(AgentAgreement::getCurrentVersionId, null)
                .set(AgentAgreement::getCurrentVersionNo, null));
        return version;
    }

    /** 版本详情：协议头 + 版本 + 点位分成行（点位编号升序）。 */
    public AgreementVersionDetailView versionDetail(Long versionId) {
        AgreementVersion version = requireVersion(versionId);
        AgentAgreement agreement = requireAgreement(version.getAgreementId());

        List<AgreementSite> rows = siteShareMapper.selectList(new LambdaQueryWrapper<AgreementSite>()
                .eq(AgreementSite::getVersionId, versionId));
        Map<Long, Site> siteById = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Site> sites = siteMapper.selectBatchIds(rows.stream()
                    .map(AgreementSite::getSiteId).collect(Collectors.toList()));
            for (Site s : sites) {
                siteById.put(s.getId(), s);
            }
        }

        List<SiteShareView> views = new ArrayList<>();
        for (AgreementSite row : rows) {
            SiteShareView view = new SiteShareView();
            view.setSiteId(row.getSiteId());
            view.setShareRate(row.getShareRate());
            Site site = siteById.get(row.getSiteId());
            if (site != null) {
                view.setSiteNo(site.getSiteNo());
                view.setSizeSpec(site.getSizeSpec());
                view.setOrientation(site.getOrientation());
                view.setBusinessDistrict(site.getBusinessDistrict());
            } else {
                view.setSiteNo(row.getSiteNo());
            }
            views.add(view);
        }
        views.sort(Comparator.comparing(v -> v.getSiteNo() == null ? "" : v.getSiteNo()));

        AgreementVersionDetailView detail = new AgreementVersionDetailView();
        detail.setAgreement(agreement);
        detail.setVersion(version);
        detail.setSites(views);
        return detail;
    }

    // ---- 内部方法 ----

    /**
     * 跨代理点位冲突校验：本版本 [fromPeriod, 长期) 窗口内，每个点位不得落在
     * *其他代理*协议的生效/已取代版本窗口里。窗口均为闭开 [from, to)，to 空=长期；
     * 定长 yyyy-MM 可直接按字符串比较。重叠条件：other.to 为空或晚于本版起始，
     * 且 other.from 早于（开区间上的）本版窗口——本版窗口向前开放，故只需 other.to > from
     * （other 恰好在本版起始期结束 other.to == from 不算撞，首尾相接）。
     */
    private void checkCrossAgentConflict(Long agreementId, String fromPeriod, List<AgreementSite> rows) {
        Set<Long> siteIds = rows.stream().map(AgreementSite::getSiteId).collect(Collectors.toSet());

        // 其他代理协议仍承接期间的版本：EFFECTIVE（含未来起始的新版）/ SUPERSEDED（历史窗口仍覆盖旧期）
        List<AgreementVersion> others = versionMapper.selectList(new LambdaQueryWrapper<AgreementVersion>()
                .ne(AgreementVersion::getAgreementId, agreementId)
                .in(AgreementVersion::getStatus,
                        AgentEnums.VERSION_EFFECTIVE, AgentEnums.VERSION_SUPERSEDED));
        if (others.isEmpty()) {
            return;
        }
        Map<Long, AgreementVersion> versionById = new HashMap<>();
        for (AgreementVersion v : others) {
            versionById.put(v.getId(), v);
        }
        List<AgreementSite> otherSites = siteShareMapper.selectList(new LambdaQueryWrapper<AgreementSite>()
                .in(AgreementSite::getVersionId, versionById.keySet())
                .in(AgreementSite::getSiteId, siteIds));
        for (AgreementSite other : otherSites) {
            AgreementVersion ov = versionById.get(other.getVersionId());
            boolean overlap = ov.getEffectiveToPeriod() == null
                    || ov.getEffectiveToPeriod().compareTo(fromPeriod) > 0;
            // 对方起始期晚于/等于本版窗口上界才不撞；本版窗口上界开放（长期），故对方只要还没在 from 前结束就重叠。
            // 但对方若起始期本身晚于本版起始（同一起始期或未来并行起始），同样属于重叠冲突。
            if (overlap) {
                throw new BusinessException("点位 " + other.getSiteNo() + " 在期间 " + fromPeriod
                        + " 起已挂在另一份代理协议上（起始期 " + ov.getEffectiveFromPeriod()
                        + "），同一点位同一期间不能挂两个代理");
            }
        }
    }

    /** 点位分成校验：点位必须已挂牌、版本内不重复、比例在 (0,1]。 */
    private Map<String, Site> validateSiteShares(List<SiteShareSpec> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("至少要给一个点位挂代理分成");
        }
        Set<String> seen = new HashSet<>();
        for (SiteShareSpec spec : raw) {
            String no = spec.getSiteNo().trim();
            if (!seen.add(no)) {
                throw new BusinessException("点位 " + no + " 的分成行重复配置了，请合并成一行");
            }
            if (spec.getShareRate() == null
                    || spec.getShareRate().signum() <= 0
                    || spec.getShareRate().compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException("点位 " + no + " 的代理分成比例必须在 (0,1] 之间（如 0.3000 表示 30%）");
            }
        }
        List<Site> sites = siteMapper.selectList(new LambdaQueryWrapper<Site>()
                .in(Site::getSiteNo, seen));
        Map<String, Site> byNo = new HashMap<>();
        for (Site site : sites) {
            byNo.put(site.getSiteNo(), site);
        }
        for (String no : seen) {
            if (!byNo.containsKey(no)) {
                throw new BusinessException("点位 " + no + " 不存在，请先挂牌建档再挂代理分成");
            }
        }
        return byNo;
    }

    private void copySites(Long fromVersionId, AgentAgreement agreement, Long toVersionId) {
        List<AgreementSite> rows = siteShareMapper.selectList(new LambdaQueryWrapper<AgreementSite>()
                .eq(AgreementSite::getVersionId, fromVersionId));
        for (AgreementSite src : rows) {
            AgreementSite row = new AgreementSite();
            row.setVersionId(toVersionId);
            row.setAgreementId(agreement.getId());
            row.setAgentId(agreement.getAgentId());
            row.setSiteId(src.getSiteId());
            row.setSiteNo(src.getSiteNo());
            row.setShareRate(src.getShareRate());
            siteShareMapper.insert(row);
        }
    }

    private AgreementVersion requireVersion(Long versionId) {
        AgreementVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("协议版本不存在: id=" + versionId);
        }
        return version;
    }

    /** 期间格式校验与归一：必须 yyyy-MM。 */
    public static String normalizePeriod(String period) {
        if (period == null || period.trim().isEmpty()) {
            throw new BusinessException("归属期间不能为空（yyyy-MM）");
        }
        String p = period.trim();
        try {
            YearMonth.parse(p);
        } catch (DateTimeParseException e) {
            throw new BusinessException("归属期间格式必须是 yyyy-MM：" + p);
        }
        return p;
    }
}
