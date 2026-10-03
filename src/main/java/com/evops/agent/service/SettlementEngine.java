package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.PartyShareRow;
import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgreementSite;
import com.evops.agent.entity.AgreementVersion;
import com.evops.agent.entity.Settlement;
import com.evops.agent.entity.SettlementItem;
import com.evops.agent.mapper.AgentMapper;
import com.evops.agent.mapper.AgentAgreementMapper;
import com.evops.agent.mapper.AgreementSiteMapper;
import com.evops.agent.mapper.AgreementVersionMapper;
import com.evops.agent.mapper.SettlementItemMapper;
import com.evops.agent.mapper.SettlementMapper;
import com.evops.agent.support.SettlementViewer;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import com.evops.ratecard.service.PricingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 结算引擎——全系统分账算钱只走这一处。
 *
 * 口径：
 * 1. 流水取授信上刊单（credit_used=1、非 OFFSET 对冲单）固化在单据上的报价含税总额，
 *    按固化的 biz_period 归集；HUNG 在挂 / TAKEN_DOWN 已落架都算当期实际流水。
 * 2. 逐单按点位找该期间生效（EFFECTIVE/SUPERSEDED 且有效期窗口覆盖当期）的协议版本分成行，
 *    代理名/协议号/版本号/比例全部复制进明细快照；找不到协议的单全额归平台（assigned=0）。
 * 3. 四舍五入到分（HALF_UP）：agent_amount = flow×rate；platform_rounded = flow×(1-rate)；
 *    rounding_diff = flow − agent − platform_rounded（一分钱尾差，单列标出）；
 *    platform_amount = flow − agent_amount（残余法），逐单两方严格对平，尾差归平台。
 * 4. 一期一条快照，生成即 CONFIRMED 冻结，没有改单/重算接口；协议之后调整与已生成期无关。
 */
@Service
public class SettlementEngine {

    static final int MONEY_SCALE = PricingEngine.MONEY_SCALE;

    private final SettlementMapper settlementMapper;
    private final SettlementItemMapper itemMapper;
    private final PostingOrderMapper orderMapper;
    private final AgreementSiteMapper siteShareMapper;
    private final AgreementVersionMapper versionMapper;
    private final AgentAgreementMapper agreementMapper;
    private final AgentMapper agentMapper;
    private final SiteMapper siteMapper;

    public SettlementEngine(SettlementMapper settlementMapper,
                            SettlementItemMapper itemMapper,
                            PostingOrderMapper orderMapper,
                            AgreementSiteMapper siteShareMapper,
                            AgreementVersionMapper versionMapper,
                            AgentAgreementMapper agreementMapper,
                            AgentMapper agentMapper,
                            SiteMapper siteMapper) {
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.siteShareMapper = siteShareMapper;
        this.versionMapper = versionMapper;
        this.agreementMapper = agreementMapper;
        this.agentMapper = agentMapper;
        this.siteMapper = siteMapper;
    }

    /** 生成某归属期间的结算快照（财务/系统）。已存在即拒——历史期不重算。 */
    @Transactional(rollbackFor = Exception.class)
    public Settlement generate(String rawPeriod, SettlementViewer viewer) {
        viewer.requireFinance();
        String period = AgreementService.normalizePeriod(rawPeriod);
        YearMonth ym = YearMonth.parse(period);
        if (ym.isAfter(YearMonth.now())) {
            throw new BusinessException("不能给未来期间 " + period + " 生成结算，结算期还没到");
        }
        Long exists = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getPeriod, period));
        if (exists != null && exists > 0) {
            throw new BusinessException(period + " 期结算快照已生成并冻结，不能重算（历史期不重算）");
        }
        String operator = Operators.resolve(viewer.getUsername());

        List<PostingOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getBizPeriod, period)
                .eq(PostingOrder::getCreditUsed, 1)
                .in(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG, LedgerEnums.ORDER_TAKEN_DOWN)
                .orderByAsc(PostingOrder::getOrderNo));

        Map<Long, String> siteNoMap = loadSiteNos(orders);
        MatchIndex index = MatchIndex.build(period, orders, siteShareMapper, versionMapper,
                agreementMapper, agentMapper);

        List<SettlementItem> items = new ArrayList<>();
        BigDecimal totalFlow = money(0);
        BigDecimal totalAgent = money(0);
        BigDecimal totalPlatform = money(0);
        BigDecimal totalPlatformRounded = money(0);
        BigDecimal totalDiff = money(0);
        BigDecimal unassigned = money(0);

        for (PostingOrder order : orders) {
            BigDecimal flow = nz(order.getOccupiedAmount()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            MatchedAgreement match = index.find(order.getSiteId());

            SettlementItem item = new SettlementItem();
            item.setPeriod(period);
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            item.setSiteId(order.getSiteId());
            item.setSiteNo(siteNoMap.getOrDefault(order.getSiteId(), ""));
            item.setCreativeName(order.getCreativeName());
            item.setFlowAmount(flow);

            BigDecimal agentAmount;
            BigDecimal platformAmount;
            BigDecimal platformRounded;
            if (match == null) {
                // 当期该点位没有生效协议：流水全额归平台
                item.setAssigned(AgentEnums.ASSIGNED_NO);
                agentAmount = money(0);
                platformRounded = flow;
            } else {
                item.setAssigned(AgentEnums.ASSIGNED_YES);
                item.setAgentId(match.agentId);
                item.setAgentNo(match.agentNo);
                item.setAgentNameSnapshot(match.agentName);
                item.setAgreementNo(match.agreementNo);
                item.setAgreementVersionNo(match.versionNo);
                item.setShareRate(match.shareRate);
                agentAmount = flow.multiply(match.shareRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                platformRounded = flow.multiply(BigDecimal.ONE.subtract(match.shareRate))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal diff = flow.subtract(agentAmount).subtract(platformRounded)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            // 残余法：平台实得兜底，保证逐单 flow = 代理 + 平台
            platformAmount = flow.subtract(agentAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            item.setAgentAmount(agentAmount);
            item.setPlatformAmount(platformAmount);
            item.setPlatformRoundedAmount(platformRounded);
            item.setRoundingDiff(diff);
            items.add(item);

            totalFlow = totalFlow.add(flow);
            totalAgent = totalAgent.add(agentAmount);
            totalPlatform = totalPlatform.add(platformAmount);
            totalPlatformRounded = totalPlatformRounded.add(platformRounded);
            totalDiff = totalDiff.add(diff);
            if (match == null) {
                unassigned = unassigned.add(flow);
            }
        }

        Settlement settlement = new Settlement();
        settlement.setSettlementNo("ST" + settlementMapper.nextSettlementSeq());
        settlement.setPeriod(period);
        settlement.setStatus(AgentEnums.SETTLEMENT_CONFIRMED);
        settlement.setTotalFlowAmount(totalFlow);
        settlement.setTotalAgentAmount(totalAgent);
        settlement.setTotalPlatformAmount(totalPlatform);
        settlement.setTotalPlatformRoundedAmount(totalPlatformRounded);
        settlement.setTotalRoundingDiff(totalDiff);
        settlement.setUnassignedFlowAmount(unassigned);
        settlement.setItemCount(items.size());
        settlement.setGeneratedTime(LocalDateTime.now());
        settlement.setOperatorName(operator);
        settlementMapper.insert(settlement);

        for (SettlementItem item : items) {
            item.setSettlementId(settlement.getId());
            itemMapper.insert(item);
        }
        return settlement;
    }

    /** 按期间翻结算头（财务全部；代理只列有自己分成的期）。 */
    public List<Settlement> list(String rawPeriod, SettlementViewer viewer) {
        LambdaQueryWrapper<Settlement> w = new LambdaQueryWrapper<>();
        if (rawPeriod != null && !rawPeriod.trim().isEmpty()) {
            w.eq(Settlement::getPeriod, AgreementService.normalizePeriod(rawPeriod));
        }
        List<Settlement> all = settlementMapper.selectList(w.orderByDesc(Settlement::getPeriod));
        if (viewer.isAll()) {
            return all;
        }
        List<Long> ownIds = ownSettlementIds(viewer.getAgentId());
        return all.stream().filter(s -> ownIds.contains(s.getId())).collect(Collectors.toList());
    }

    /** 结算全量档案。财务：头+按方汇总+全部明细；代理：无头无平台行，只看挂自己协议的单。 */
    public SettlementDetailView detail(String settlementNo, SettlementViewer viewer) {
        Settlement settlement = requireByNo(settlementNo);
        List<SettlementItem> items = itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                .eq(SettlementItem::getSettlementId, settlement.getId())
                .orderByAsc(SettlementItem::getOrderNo));

        SettlementDetailView view = new SettlementDetailView();
        if (viewer.isAll()) {
            view.setSettlement(settlement);
            view.setItems(items);
            view.setParties(buildFinanceParties(settlement, items));
        } else {
            List<SettlementItem> own = items.stream()
                    .filter(i -> viewer.getAgentId().equals(i.getAgentId()))
                    .collect(Collectors.toList());
            if (own.isEmpty()) {
                throw new BusinessException("结算单 " + settlementNo + " 不在你名下，无权查看（别家的账单看不见）");
            }
            view.setItems(own);
            view.setParties(buildAgentParty(own));
        }
        return view;
    }

    /** 按期间查明细（财务全部；代理只返回自己的行）。 */
    public List<SettlementItem> itemsByPeriod(String rawPeriod, SettlementViewer viewer) {
        String period = AgreementService.normalizePeriod(rawPeriod);
        Settlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getPeriod, period));
        if (settlement == null) {
            return new ArrayList<>();
        }
        List<SettlementItem> items = itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                .eq(SettlementItem::getSettlementId, settlement.getId())
                .orderByAsc(SettlementItem::getOrderNo));
        if (viewer.isAll()) {
            return items;
        }
        return items.stream()
                .filter(i -> viewer.getAgentId().equals(i.getAgentId()))
                .collect(Collectors.toList());
    }

    /**
     * 结算导出 CSV（带 BOM，Excel 打开不乱码）。
     * 财务导逐单全量（含平台分成与舍入尾差）；代理只导自己的流水与分成，看不到平台/别家。
     */
    public String csv(String rawPeriod, SettlementViewer viewer) {
        String period = AgreementService.normalizePeriod(rawPeriod);
        Settlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getPeriod, period));
        if (settlement == null) {
            throw new BusinessException(period + " 期结算快照还没生成");
        }
        List<SettlementItem> items = itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                .eq(SettlementItem::getSettlementId, settlement.getId())
                .orderByAsc(SettlementItem::getOrderNo));

        StringBuilder csv = new StringBuilder("﻿");
        if (viewer.isAll()) {
            csv.append("归属期间,结算单号,上刊单号,点位编号,画面,是否挂协议,代理编号,代理名称,协议号,协议版本,分成比例,流水额,代理分成,平台分成(实得),平台分成(独立取整),舍入尾差\n");
            for (SettlementItem i : items) {
                csv.append(esc(i.getPeriod())).append(',')
                        .append(esc(settlement.getSettlementNo())).append(',')
                        .append(esc(i.getOrderNo())).append(',')
                        .append(esc(i.getSiteNo())).append(',')
                        .append(esc(i.getCreativeName())).append(',')
                        .append(i.getAssigned() == AgentEnums.ASSIGNED_YES ? "已挂协议" : "未挂协议").append(',')
                        .append(esc(i.getAgentNo())).append(',')
                        .append(esc(i.getAgentNameSnapshot())).append(',')
                        .append(esc(i.getAgreementNo())).append(',')
                        .append(i.getAgreementVersionNo() == null ? "" : "v" + i.getAgreementVersionNo()).append(',')
                        .append(i.getShareRate() == null ? "" : i.getShareRate().toPlainString()).append(',')
                        .append(i.getFlowAmount().toPlainString()).append(',')
                        .append(i.getAgentAmount().toPlainString()).append(',')
                        .append(i.getPlatformAmount().toPlainString()).append(',')
                        .append(i.getPlatformRoundedAmount().toPlainString()).append(',')
                        .append(i.getRoundingDiff().toPlainString()).append('\n');
            }
            // 合计行：与 16 列表头逐列对齐，差一分钱都能在末列“舍入尾差”看到
            List<String> total = new ArrayList<>();
            for (int c = 0; c < 16; c++) {
                total.add("");
            }
            total.set(0, period);
            total.set(1, "合计");
            total.set(2, "共 " + settlement.getItemCount() + " 单");
            total.set(11, settlement.getTotalFlowAmount().toPlainString());
            total.set(12, settlement.getTotalAgentAmount().toPlainString());
            total.set(13, settlement.getTotalPlatformAmount().toPlainString());
            total.set(14, settlement.getTotalPlatformRoundedAmount().toPlainString());
            total.set(15, settlement.getTotalRoundingDiff().toPlainString());
            csv.append(String.join(",", total)).append('\n');
        } else {
            List<SettlementItem> own = items.stream()
                    .filter(i -> viewer.getAgentId().equals(i.getAgentId()))
                    .collect(Collectors.toList());
            csv.append("归属期间,结算单号,上刊单号,点位编号,画面,分成比例,流水额,代理分成\n");
            for (SettlementItem i : own) {
                csv.append(esc(i.getPeriod())).append(',')
                        .append(esc(settlement.getSettlementNo())).append(',')
                        .append(esc(i.getOrderNo())).append(',')
                        .append(esc(i.getSiteNo())).append(',')
                        .append(esc(i.getCreativeName())).append(',')
                        .append(i.getShareRate().toPlainString()).append(',')
                        .append(i.getFlowAmount().toPlainString()).append(',')
                        .append(i.getAgentAmount().toPlainString()).append('\n');
            }
        }
        return csv.toString();
    }

    public Settlement requireByNo(String settlementNo) {
        Settlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getSettlementNo, settlementNo.trim()));
        if (settlement == null) {
            throw new BusinessException("结算单不存在: " + settlementNo);
        }
        return settlement;
    }

    // ---- 内部 ----

    private List<Long> ownSettlementIds(Long agentId) {
        return itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                        .eq(SettlementItem::getAgentId, agentId)
                        .select(SettlementItem::getSettlementId)).stream()
                .map(SettlementItem::getSettlementId)
                .distinct()
                .collect(Collectors.toList());
    }

    /** 财务视角按方汇总：每个代理一行（保持首次出现顺序），平台一行（实得含尾差，单列尾差）。 */
    private List<PartyShareRow> buildFinanceParties(Settlement settlement, List<SettlementItem> items) {
        Map<Long, PartyShareRow> byAgent = new LinkedHashMap<>();
        BigDecimal platformFlow = money(0);
        for (SettlementItem i : items) {
            platformFlow = platformFlow.add(i.getFlowAmount());
            if (i.getAgentId() == null) {
                continue;
            }
            PartyShareRow row = byAgent.computeIfAbsent(i.getAgentId(), k -> {
                PartyShareRow r = new PartyShareRow();
                r.setPartyType("AGENT");
                r.setAgentId(i.getAgentId());
                r.setAgentNo(i.getAgentNo());
                r.setAgentName(i.getAgentNameSnapshot());
                r.setFlowAmount(money(0));
                r.setShareAmount(money(0));
                r.setItemCount(0);
                return r;
            });
            row.setFlowAmount(row.getFlowAmount().add(i.getFlowAmount()));
            row.setShareAmount(row.getShareAmount().add(i.getAgentAmount()));
            row.setItemCount(row.getItemCount() + 1);
        }
        List<PartyShareRow> rows = new ArrayList<>(byAgent.values());

        PartyShareRow platform = new PartyShareRow();
        platform.setPartyType("PLATFORM");
        platform.setAgentName("平台方");
        platform.setFlowAmount(platformFlow);
        platform.setShareAmount(settlement.getTotalPlatformAmount());
        platform.setRoundedAmount(settlement.getTotalPlatformRoundedAmount());
        platform.setRoundingDiff(settlement.getTotalRoundingDiff());
        platform.setItemCount(settlement.getItemCount());
        rows.add(platform);
        return rows;
    }

    /** 代理自助视角：只有自己一行。 */
    private List<PartyShareRow> buildAgentParty(List<SettlementItem> own) {
        PartyShareRow row = new PartyShareRow();
        row.setPartyType("AGENT");
        SettlementItem first = own.get(0);
        row.setAgentId(first.getAgentId());
        row.setAgentNo(first.getAgentNo());
        row.setAgentName(first.getAgentNameSnapshot());
        row.setFlowAmount(money(0));
        row.setShareAmount(money(0));
        row.setItemCount(own.size());
        for (SettlementItem i : own) {
            row.setFlowAmount(row.getFlowAmount().add(i.getFlowAmount()));
            row.setShareAmount(row.getShareAmount().add(i.getAgentAmount()));
        }
        List<PartyShareRow> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** 一次结算内的协议匹配索引：把可能命中的版本/协议/代理批量捞进内存，逐单 O(1) 匹配。 */
    private static class MatchIndex {
        private final Map<Long, MatchedAgreement> bySite = new HashMap<>();

        static MatchIndex build(String period,
                                List<PostingOrder> orders,
                                AgreementSiteMapper siteShareMapper,
                                AgreementVersionMapper versionMapper,
                                AgentAgreementMapper agreementMapper,
                                AgentMapper agentMapper) {
            MatchIndex index = new MatchIndex();
            if (orders.isEmpty()) {
                return index;
            }
            List<Long> siteIds = orders.stream().map(PostingOrder::getSiteId)
                    .distinct().collect(Collectors.toList());

            List<AgreementSite> shares = siteShareMapper.selectList(new LambdaQueryWrapper<AgreementSite>()
                    .in(AgreementSite::getSiteId, siteIds));
            if (shares.isEmpty()) {
                return index;
            }
            List<Long> versionIds = shares.stream().map(AgreementSite::getVersionId).distinct()
                    .collect(Collectors.toList());
            List<AgreementVersion> versions = versionMapper.selectBatchIds(versionIds);
            Map<Long, AgreementVersion> versionById = new HashMap<>();
            for (AgreementVersion v : versions) {
                boolean activeWindow = AgentEnums.VERSION_EFFECTIVE.equals(v.getStatus())
                        || AgentEnums.VERSION_SUPERSEDED.equals(v.getStatus());
                boolean covers = activeWindow
                        && v.getEffectiveFromPeriod().compareTo(period) <= 0
                        && (v.getEffectiveToPeriod() == null
                            || v.getEffectiveToPeriod().compareTo(period) > 0);
                if (covers) {
                    versionById.put(v.getId(), v);
                }
            }
            if (versionById.isEmpty()) {
                return index;
            }
            List<Long> agreementIds = versionById.values().stream()
                    .map(AgreementVersion::getAgreementId).distinct().collect(Collectors.toList());
            Map<Long, AgentAgreement> agreementById = agreementMapper.selectBatchIds(agreementIds).stream()
                    .collect(Collectors.toMap(AgentAgreement::getId, a -> a));
            List<Long> agentIds = agreementById.values().stream()
                    .map(AgentAgreement::getAgentId).distinct().collect(Collectors.toList());
            Map<Long, Agent> agentById = agentMapper.selectBatchIds(agentIds).stream()
                    .collect(Collectors.toMap(Agent::getId, a -> a));

            for (AgreementSite share : shares) {
                AgreementVersion version = versionById.get(share.getVersionId());
                if (version == null) {
                    continue;
                }
                if (index.bySite.containsKey(share.getSiteId())) {
                    // 发布校验已防跨代理同期冲突；同协议版本窗口不重叠。命中两条即脏数据，宁可不猜
                    throw new BusinessException("该期点位匹配到多份生效代理协议，数据冲突，请核对协议有效期");
                }
                AgentAgreement agreement = agreementById.get(version.getAgreementId());
                Agent agent = agreement == null ? null : agentById.get(agreement.getAgentId());
                MatchedAgreement m = new MatchedAgreement();
                m.agentId = share.getAgentId();
                m.agentNo = agreement == null ? null : agreement.getAgentNo();
                m.agentName = agreement == null ? null : agreement.getAgentNameSnapshot();
                m.agreementNo = agreement == null ? null : agreement.getAgreementNo();
                m.versionNo = version.getVersionNo();
                m.shareRate = share.getShareRate();
                if (agent != null) {
                    // 以代理档案当前名为准，协议建档案照名兜底
                    m.agentName = agent.getAgentName();
                }
                index.bySite.put(share.getSiteId(), m);
            }
            return index;
        }

        MatchedAgreement find(Long siteId) {
            return bySite.get(siteId);
        }
    }

    /** 批量补点位编号（上刊单只存 site_id）。 */
    private Map<Long, String> loadSiteNos(List<PostingOrder> orders) {
        List<Long> siteIds = orders.stream().map(PostingOrder::getSiteId)
                .distinct().collect(Collectors.toList());
        if (siteIds.isEmpty()) {
            return new HashMap<>();
        }
        return siteMapper.selectBatchIds(siteIds).stream()
                .collect(Collectors.toMap(Site::getId, Site::getSiteNo));
    }

    private static class MatchedAgreement {
        private Long agentId;
        private String agentNo;
        private String agentName;
        private String agreementNo;
        private Integer versionNo;
        private BigDecimal shareRate;
    }
}
