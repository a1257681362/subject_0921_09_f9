package com.evops.agent;

import com.evops.agent.dto.AgentCreateRequest;
import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementVersionActionRequest;
import com.evops.agent.dto.AgreementVersionConfigureRequest;
import com.evops.agent.dto.AgreementVersionDetailView;
import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.dto.SiteShareSpec;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgreementVersion;
import com.evops.agent.entity.Settlement;
import com.evops.agent.entity.SettlementItem;
import com.evops.agent.mapper.SettlementItemMapper;
import com.evops.agent.service.AgentService;
import com.evops.agent.service.AgreementService;
import com.evops.agent.service.SettlementEngine;
import com.evops.agent.support.SettlementViewer;
import com.evops.common.BusinessException;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CustomerService;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.service.QuoteService;
import com.evops.ratecard.service.RateCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F9 代理协议与结算快照端到端（真实 H2 + 真实事务，无 Shiro，用显式 SettlementViewer 验证隔离）。 */
@SpringBootTest
class AgentSettlementFlowTest {

    @Autowired private AgentService agentService;
    @Autowired private AgreementService agreementService;
    @Autowired private SettlementEngine settlementEngine;
    @Autowired private SiteService siteService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private CustomerService customerService;
    @Autowired private OrderService orderService;
    @Autowired private SchemaVersionService schemaVersionService;
    @Autowired private SettlementItemMapper itemMapper;

    private final SettlementViewer finance = SettlementViewer.finance("finance1");

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    // ---- 造数 ----

    private Site site() {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(uniqueNo("AG-SITE"));
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict("代理测试商圈");
        req.setOperatorName("tester");
        return siteService.register(req);
    }

    private Agent agent(String login) {
        AgentCreateRequest req = new AgentCreateRequest();
        req.setAgentName(uniqueNo("代理"));
        req.setLoginUsername(login);
        req.setContact("13800000000");
        req.setOperatorName("finance1");
        return agentService.create(req);
    }

    /** 某年一份刊例，单档不打折，给点位挂价。 */
    private Site rateCard(int year, String price) {
        Site s = site();
        RateCardCreateRequest cr = new RateCardCreateRequest();
        cr.setRateYear(year);
        cr.setCardName(year + " 代理测试刊例");
        cr.setOperatorName("tester");
        com.evops.ratecard.entity.RateCard card = rateCardService.createCard(cr);
        long v1 = rateCardService.listVersions(card.getId()).get(0).getId();

        TierSpec tier = new TierSpec();
        tier.setMinWeeks(1);
        tier.setMaxWeeks(null);
        tier.setDiscountRate(new BigDecimal("1.0000"));
        SitePriceSpec sp = new SitePriceSpec();
        sp.setSiteNo(s.getSiteNo());
        sp.setListPrice(new BigDecimal(price));
        VersionConfigureRequest cfg = new VersionConfigureRequest();
        cfg.setTiers(new ArrayList<>(Arrays.asList(tier)));
        cfg.setSitePrices(new ArrayList<>(Arrays.asList(sp)));
        cfg.setOperatorName("tester");
        rateCardService.configure(v1, cfg);
        VersionActionRequest a = new VersionActionRequest();
        a.setOperatorName("tester");
        rateCardService.publish(v1, a);
        return s;
    }

    private Customer customer() {
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(uniqueNo("分账客户"));
        req.setCreditLimit(new BigDecimal("9999999.00"));
        req.setOwnerSales("finance1");
        req.setOperatorName("finance1");
        return customerService.create(req, com.evops.common.Viewer.finance("finance1"));
    }

    /** 单行 1 周报价；submitDate 留空取今天。 */
    private QuoteDetailView quote(Customer c, int year, Site s, LocalDate submitDate) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(s.getSiteNo());
        line.setWeeks(1);
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(c.getCustomerName());
        req.setSubmitDate(submitDate);
        req.setLines(new ArrayList<>(Arrays.asList(line)));
        req.setOperatorName("tester");
        return quoteService.submit(req);
    }

    /** 造一张授信上刊单（含税流水=报价快照），occurDate 决定归属期间；报价提交日同 occurDate（均为过去日）。 */
    private PostingOrder posting(Customer c, int year, Site s, LocalDate occurDate) {
        QuoteDetailView q = quote(c, year, s, occurDate);
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(s.getSiteNo());
        req.setCreativeName("分账画面");
        req.setQuoteNo(q.getQuote().getQuoteNo());
        req.setOccurDate(occurDate);
        req.setOperatorName("finance1");
        return orderService.createPosting(req);
    }

    private SiteShareSpec share(String siteNo, String rate) {
        SiteShareSpec spec = new SiteShareSpec();
        spec.setSiteNo(siteNo);
        spec.setShareRate(new BigDecimal(rate));
        return spec;
    }

    private AgreementVersionConfigureRequest config(SiteShareSpec... shares) {
        AgreementVersionConfigureRequest req = new AgreementVersionConfigureRequest();
        req.setSites(new ArrayList<>(Arrays.asList(shares)));
        req.setOperatorName("finance1");
        return req;
    }

    private AgreementVersionActionRequest action() {
        AgreementVersionActionRequest a = new AgreementVersionActionRequest();
        a.setOperatorName("finance1");
        return a;
    }

    /** 建协议 → v1 草稿配上点位分成 → 发布。返回协议。 */
    private AgentAgreement signedAgreement(Agent a, String fromPeriod, SiteShareSpec... shares) {
        AgreementCreateRequest cr = new AgreementCreateRequest();
        cr.setAgentNo(a.getAgentNo());
        cr.setEffectiveFromPeriod(fromPeriod);
        cr.setOperatorName("finance1");
        AgentAgreement agreement = agreementService.createAgreement(cr);
        long v1 = agreementService.listVersions(agreement.getId()).stream()
                .filter(v -> v.getVersionNo() == 1).findFirst().get().getId();
        agreementService.configure(v1, config(shares));
        agreementService.publish(v1, action());
        // 返回发布后的协议（currentVersionId 已回写）
        return agreementService.requireAgreement(agreement.getId());
    }

    private ActionRequest orderAction() {
        ActionRequest a = new ActionRequest();
        a.setOperatorName("finance1");
        a.setReason("测试动作");
        return a;
    }

    private SettlementItem itemOf(Settlement s, String orderNo) {
        return itemMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SettlementItem>()
                        .eq(SettlementItem::getSettlementId, s.getId())
                        .eq(SettlementItem::getOrderNo, orderNo))
                .get(0);
    }

    // ---- 代理档案 ----

    @Test
    void 代理建档_编号前缀_名称登录唯一_停用() {
        Agent a = agent("login-" + uniqueNo("x"));
        assertTrue(a.getAgentNo().startsWith("AG"));
        assertEquals("ACTIVE", a.getStatus());

        AgentCreateRequest dupName = new AgentCreateRequest();
        dupName.setAgentName(a.getAgentName());
        dupName.setOperatorName("finance1");
        BusinessException e1 = assertThrows(BusinessException.class, () -> agentService.create(dupName));
        assertTrue(e1.getMessage().contains("全局唯一"));

        AgentCreateRequest dupLogin = new AgentCreateRequest();
        dupLogin.setAgentName(uniqueNo("另一代理"));
        dupLogin.setLoginUsername(a.getLoginUsername());
        dupLogin.setOperatorName("finance1");
        BusinessException e2 = assertThrows(BusinessException.class, () -> agentService.create(dupLogin));
        assertTrue(e2.getMessage().contains("已绑定其他代理"));

        assertEquals("DISABLED", agentService.disable(a.getAgentNo(),
                new com.evops.agent.dto.AgentStatusRequest()).getStatus());
        assertEquals("ACTIVE", agentService.enable(a.getAgentNo(),
                new com.evops.agent.dto.AgentStatusRequest()).getStatus());
    }

    // ---- 协议版本 / 状态 ----

    @Test
    void 协议建档自动v1草稿_点位不存在_重复_比例越界都拒收() {
        Agent a = agent(null);
        Site s = site();
        AgreementCreateRequest cr = new AgreementCreateRequest();
        cr.setAgentNo(a.getAgentNo());
        cr.setEffectiveFromPeriod("2020-06");
        cr.setOperatorName("finance1");
        AgentAgreement agreement = agreementService.createAgreement(cr);
        assertEquals("DRAFT", agreement.getStatus());
        long v1 = agreementService.listVersions(agreement.getId()).get(0).getId();

        BusinessException noSite = assertThrows(BusinessException.class,
                () -> agreementService.configure(v1, config(share("NO-SUCH-SITE", "0.30"))));
        assertTrue(noSite.getMessage().contains("不存在"));

        BusinessException dup = assertThrows(BusinessException.class,
                () -> agreementService.configure(v1, config(share(s.getSiteNo(), "0.30"), share(s.getSiteNo(), "0.40"))));
        assertTrue(dup.getMessage().contains("重复配置"));

        BusinessException zero = assertThrows(BusinessException.class,
                () -> agreementService.configure(v1, config(share(s.getSiteNo(), "0"))));
        assertTrue(zero.getMessage().contains("(0,1]"));
        BusinessException over = assertThrows(BusinessException.class,
                () -> agreementService.configure(v1, config(share(s.getSiteNo(), "1.2"))));
        assertTrue(over.getMessage().contains("(0,1]"));

        // 全被拒，草稿下没有任何分成行
        AgreementVersionDetailView detail = agreementService.versionDetail(v1);
        assertEquals(0, detail.getSites().size());

        // 一个代理只能有一份协议
        BusinessException oneOnly = assertThrows(BusinessException.class,
                () -> signedAgreement(a, "2020-07", share(s.getSiteNo(), "0.30")));
        assertTrue(oneOnly.getMessage().contains("已有协议"));
    }

    @Test
    void 空草稿不能发布_配置后发布生效即冻结() {
        Agent a = agent(null);
        Site s = site();
        AgreementCreateRequest cr = new AgreementCreateRequest();
        cr.setAgentNo(a.getAgentNo());
        cr.setEffectiveFromPeriod("2020-06");
        cr.setOperatorName("finance1");
        AgentAgreement agreement = agreementService.createAgreement(cr);
        long v1 = agreementService.listVersions(agreement.getId()).get(0).getId();

        BusinessException empty = assertThrows(BusinessException.class,
                () -> agreementService.publish(v1, action()));
        assertTrue(empty.getMessage().contains("不能发布"));

        agreementService.configure(v1, config(share(s.getSiteNo(), "0.3000")));
        AgreementVersion published = agreementService.publish(v1, action());
        assertEquals("EFFECTIVE", published.getStatus());
        assertNotNull(published.getPublishTime());
        assertEquals("EFFECTIVE", agreementService.requireAgreement(agreement.getId()).getStatus());

        // 生效即冻结
        BusinessException frozen = assertThrows(BusinessException.class,
                () -> agreementService.configure(v1, config(share(s.getSiteNo(), "0.40"))));
        assertTrue(frozen.getMessage().contains("已冻结"));
        BusinessException republish = assertThrows(BusinessException.class,
                () -> agreementService.publish(v1, action()));
        assertTrue(republish.getMessage().contains("只有草稿版本可发布"));
    }

    @Test
    void 中途调整另起v2必须从下期生效_发布后旧版回填截止期() {
        Agent a = agent(null);
        Site s = site();
        AgentAgreement agreement = signedAgreement(a, "2020-06", share(s.getSiteNo(), "0.30"));
        long v1 = agreement.getCurrentVersionId();
        String currentPeriod = java.time.YearMonth.now().toString();

        // 起始期不晚于当前期：拒（中途调整从下期生效）
        AgreementVersionActionRequest past = action();
        past.setEffectiveFromPeriod(currentPeriod);
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> agreementService.newVersion(agreement.getId(), past));
        assertTrue(e1.getMessage().contains("从下期生效"));
        AgreementVersionActionRequest old = action();
        old.setEffectiveFromPeriod("2020-07");
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> agreementService.newVersion(agreement.getId(), old));
        assertTrue(e2.getMessage().contains("从下期生效"));

        // 未来期：草稿继承上一版点位分成
        AgreementVersionActionRequest future = action();
        future.setEffectiveFromPeriod("2099-01");
        future.setReason("旺季提高分成");
        AgreementVersion v2 = agreementService.newVersion(agreement.getId(), future);
        assertEquals(2, v2.getVersionNo());
        assertEquals("DRAFT", v2.getStatus());
        AgreementVersionDetailView copied = agreementService.versionDetail(v2.getId());
        assertEquals(1, copied.getSites().size());
        assertEquals("0.3000", copied.getSites().get(0).getShareRate().toPlainString());

        agreementService.configure(v2.getId(), config(share(s.getSiteNo(), "0.40")));
        agreementService.publish(v2.getId(), action());

        AgreementVersion oldVersion = agreementService.versionDetail(v1).getVersion();
        assertEquals("SUPERSEDED", oldVersion.getStatus());
        assertEquals("2099-01", oldVersion.getEffectiveToPeriod()); // 历史期间仍归 v1 窗口
        AgreementVersion newVersion = agreementService.versionDetail(v2.getId()).getVersion();
        assertNull(newVersion.getEffectiveToPeriod());
    }

    @Test
    void 同一点位同一期间挂两个代理_发布直接拒_换点位不冲突() {
        Site s = site();
        Site other = site();
        Agent a1 = agent(null);
        Agent a2 = agent(null);
        signedAgreement(a1, "2020-06", share(s.getSiteNo(), "0.30"));

        AgreementCreateRequest cr = new AgreementCreateRequest();
        cr.setAgentNo(a2.getAgentNo());
        cr.setEffectiveFromPeriod("2020-06");
        cr.setOperatorName("finance1");
        AgentAgreement agreement2 = agreementService.createAgreement(cr);
        long v1 = agreementService.listVersions(agreement2.getId()).get(0).getId();
        agreementService.configure(v1, config(share(s.getSiteNo(), "0.20")));
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> agreementService.publish(v1, action()));
        assertTrue(conflict.getMessage().contains("不能挂两个代理"));

        // 冲突按点位隔离：a2 草稿改挂另一个点位，同期可正常发布
        agreementService.configure(v1, config(share(other.getSiteNo(), "0.20")));
        AgreementVersion published = agreementService.publish(v1, action());
        assertEquals("EFFECTIVE", published.getStatus());
    }

    // ---- 结算：算钱 / 对平 / 尾差 ----

    @Test
    void 多代理多单结算_挂起落架都算当期_金额与按方汇总正确() {
        Site s1 = rateCard(1991, "1000"); // 含税 1060.00/单
        Site s2 = rateCard(1992, "1000");
        Customer c = customer();
        Agent a1 = agent(null);
        Agent a2 = agent(null);
        signedAgreement(a1, "1991-06", share(s1.getSiteNo(), "0.30"));
        signedAgreement(a2, "1992-06", share(s2.getSiteNo(), "0.20"));

        PostingOrder hung = posting(c, 1991, s1, LocalDate.of(1991, 6, 2));   // HUNG
        PostingOrder down = posting(c, 1992, s2, LocalDate.of(1992, 6, 2));
        orderService.takeDown(down.getOrderNo(), orderAction());               // TAKEN_DOWN

        Settlement s = settlementEngine.generate("1991-06", finance);
        // 1991-06 只有 s1 那一单
        Settlement settlement31 = settlementEngine.requireByNo(s.getSettlementNo());
        assertEquals("1060.00", settlement31.getTotalFlowAmount().toPlainString());
        assertEquals("318.00", settlement31.getTotalAgentAmount().toPlainString());
        assertEquals("742.00", settlement31.getTotalPlatformAmount().toPlainString());
        assertEquals("0.00", settlement31.getTotalRoundingDiff().toPlainString());
        assertEquals(1, settlement31.getItemCount());

        Settlement s32 = settlementEngine.generate("1992-06", finance);
        assertEquals("1060.00", s32.getTotalFlowAmount().toPlainString());
        assertEquals("212.00", s32.getTotalAgentAmount().toPlainString());
        assertEquals("848.00", s32.getTotalPlatformAmount().toPlainString());

        SettlementDetailView view = settlementEngine.detail(s32.getSettlementNo(), finance);
        // 按方汇总：代理行 + 平台行
        assertEquals(2, view.getParties().size());
        assertEquals("AGENT", view.getParties().get(0).getPartyType());
        assertEquals(a2.getId(), view.getParties().get(0).getAgentId());
        assertEquals("PLATFORM", view.getParties().get(1).getPartyType());
        // 落架单状态不影响进流水
        assertNotNull(itemOf(s32, down.getOrderNo()));
        assertNotNull(itemOf(settlement31, hung.getOrderNo()));
    }

    @Test
    void 一分钱尾差单独标出_残余法保证合计严格对平() {
        // 挂牌价 9.43 一周：未税 9.43，税 0.57，含税流水 10.00
        Site s = rateCard(1993, "9.43");
        Customer c = customer();
        Agent a = agent(null);
        signedAgreement(a, "1993-06", share(s.getSiteNo(), "0.3335"));
        PostingOrder o = posting(c, 1993, s, LocalDate.of(1993, 6, 2));
        assertEquals("10.00", o.getOccupiedAmount().toPlainString());

        Settlement st = settlementEngine.generate("1993-06", finance);
        SettlementItem item = itemOf(st, o.getOrderNo());
        // 10 × 0.3335 = 3.335 → 3.34；10 × 0.6665 = 6.665 → 6.67；各自取整合计 10.01，差 -0.01
        assertEquals("3.34", item.getAgentAmount().toPlainString());
        assertEquals("6.67", item.getPlatformRoundedAmount().toPlainString());
        assertEquals("-0.01", item.getRoundingDiff().toPlainString());
        // 残余法平台实得兜底，逐单严格对平
        assertEquals("6.66", item.getPlatformAmount().toPlainString());
        assertEquals("10.00", item.getAgentAmount().add(item.getPlatformAmount()).toPlainString());

        assertEquals("10.00", st.getTotalFlowAmount().toPlainString());
        assertEquals("10.00",
                st.getTotalAgentAmount().add(st.getTotalPlatformAmount()).toPlainString());
        assertEquals("-0.01", st.getTotalRoundingDiff().toPlainString()); // 差一分也标出来
    }

    @Test
    void 同期不可重算_协议下期调整不影响历史快照() {
        Site s = rateCard(1994, "1000");
        Customer c = customer();
        Agent a = agent(null);
        AgentAgreement agreement = signedAgreement(a, "1994-06", share(s.getSiteNo(), "0.30"));
        posting(c, 1994, s, LocalDate.of(1994, 6, 2));

        Settlement first = settlementEngine.generate("1994-06", finance);
        assertEquals("318.00", first.getTotalAgentAmount().toPlainString());

        // 历史期不重算
        BusinessException again = assertThrows(BusinessException.class,
                () -> settlementEngine.generate("1994-06", finance));
        assertTrue(again.getMessage().contains("不能重算"));

        // 另起未来版调比例到 40%，历史期结算快照一个数都不变
        AgreementVersionActionRequest future = action();
        future.setEffectiveFromPeriod("2099-01");
        AgreementVersion v2 = agreementService.newVersion(agreement.getId(), future);
        agreementService.configure(v2.getId(), config(share(s.getSiteNo(), "0.40")));
        agreementService.publish(v2.getId(), action());

        Settlement refetched = settlementEngine.requireByNo(first.getSettlementNo());
        assertEquals("318.00", refetched.getTotalAgentAmount().toPlainString());
        assertEquals("742.00", refetched.getTotalPlatformAmount().toPlainString());
    }

    @Test
    void 对冲单不计流水_未挂协议流水全额归平台() {
        Site offsetSite = rateCard(1995, "1000");
        Site plainSite = rateCard(1996, "1000");
        Customer c = customer();

        // 一张上刊后对冲：OFFSET 不进流水
        PostingOrder offset = posting(c, 1995, offsetSite, LocalDate.of(1995, 6, 2));
        orderService.offset(offset.getOrderNo(), orderAction());

        // 另一张有流水但点位没挂任何协议
        PostingOrder unassigned = posting(c, 1996, plainSite, LocalDate.of(1996, 6, 2));

        Settlement s35 = settlementEngine.generate("1995-06", finance);
        assertEquals(0, s35.getItemCount());
        assertEquals("0.00", s35.getTotalFlowAmount().toPlainString());

        Settlement s36 = settlementEngine.generate("1996-06", finance);
        assertEquals(1, s36.getItemCount());
        assertEquals("1060.00", s36.getTotalFlowAmount().toPlainString());
        assertEquals("0.00", s36.getTotalAgentAmount().toPlainString());
        assertEquals("1060.00", s36.getTotalPlatformAmount().toPlainString());
        assertEquals("1060.00", s36.getUnassignedFlowAmount().toPlainString());
        SettlementItem item = itemOf(s36, unassigned.getOrderNo());
        assertEquals(0, item.getAssigned());
        assertNull(item.getAgentId());
    }

    @Test
    void 生效版本作废后下期不再参与结算_流水归平台_历史期不变() {
        Site s = rateCard(1988, "1000");
        Customer c = customer();
        Agent a = agent(null);
        AgentAgreement agreement = signedAgreement(a, "1988-06", share(s.getSiteNo(), "0.30"));
        PostingOrder june = posting(c, 1988, s, LocalDate.of(1988, 6, 2));
        PostingOrder july = posting(c, 1988, s, LocalDate.of(1988, 7, 2));

        // 6 月：协议有效，代理分得 318
        Settlement juneSt = settlementEngine.generate("1988-06", finance);
        assertEquals("318.00", juneSt.getTotalAgentAmount().toPlainString());
        assertEquals("318.00", itemOf(juneSt, june.getOrderNo()).getAgentAmount().toPlainString());

        // 7 月结算前作废生效版本
        AgreementVersion revoked = agreementService.revoke(agreement.getCurrentVersionId(), action());
        assertEquals("REVOKED", revoked.getStatus());

        // 7 月：作废版本不再参与，流水全额归平台
        Settlement julySt = settlementEngine.generate("1988-07", finance);
        assertEquals("0.00", julySt.getTotalAgentAmount().toPlainString());
        assertEquals("1060.00", julySt.getTotalPlatformAmount().toPlainString());
        assertEquals("1060.00", julySt.getUnassignedFlowAmount().toPlainString());
        assertEquals(0, itemOf(julySt, july.getOrderNo()).getAssigned());

        // 已生成的 6 月快照不受作废影响，且不能重算
        assertEquals("318.00", settlementEngine.requireByNo(juneSt.getSettlementNo())
                .getTotalAgentAmount().toPlainString());
        BusinessException regenerate = assertThrows(BusinessException.class,
                () -> settlementEngine.generate("1988-06", finance));
        assertTrue(regenerate.getMessage().contains("不能重算"));
    }

    // ---- 隔离 ----

    @Test
    void 代理只能查自己账单_别家平台总额都看不见() {
        Site s1 = rateCard(1997, "1000");
        Site s2 = rateCard(1998, "1000");
        Customer c = customer();
        Agent a1 = agent("agent1");
        Agent a2 = agent("agent2");
        signedAgreement(a1, "1997-06", share(s1.getSiteNo(), "0.30"));
        signedAgreement(a2, "1998-06", share(s2.getSiteNo(), "0.20"));
        PostingOrder o1 = posting(c, 1997, s1, LocalDate.of(1997, 6, 2));
        PostingOrder o2 = posting(c, 1998, s2, LocalDate.of(1998, 6, 2));
        Settlement st1 = settlementEngine.generate("1997-06", finance);
        Settlement st2 = settlementEngine.generate("1998-06", finance);

        SettlementViewer v1 = SettlementViewer.agent("agent1", a1.getId());
        SettlementViewer v2 = SettlementViewer.agent("agent2", a2.getId());

        // 列表：各自只见有自己分成的期
        List<Settlement> list1 = settlementEngine.list(null, v1);
        assertEquals(java.util.Collections.singletonList(st1.getSettlementNo()),
                list1.stream().map(Settlement::getSettlementNo).collect(Collectors.toList()));

        // 详情：代理视角无头（看不到全盘总额/平台），只有自己一行一单一
        SettlementDetailView d1 = settlementEngine.detail(st1.getSettlementNo(), v1);
        assertNull(d1.getSettlement());
        assertEquals(1, d1.getItems().size());
        assertEquals(o1.getOrderNo(), d1.getItems().get(0).getOrderNo());
        assertEquals(1, d1.getParties().size());
        assertEquals(a1.getId(), d1.getParties().get(0).getAgentId());
        assertEquals("318.00", d1.getParties().get(0).getShareAmount().toPlainString());

        // agent1 看不到 agent2 的账单
        BusinessException deny = assertThrows(BusinessException.class,
                () -> settlementEngine.detail(st2.getSettlementNo(), v1));
        assertTrue(deny.getMessage().contains("无权查看"));
        // agent2 访问 agent1 同样被拒
        assertThrows(BusinessException.class,
                () -> settlementEngine.detail(st1.getSettlementNo(), v2));

        // 期间明细也按代理过滤
        assertEquals(1, settlementEngine.itemsByPeriod("1997-06", v1).size());
        assertEquals(0, settlementEngine.itemsByPeriod("1997-06", v2).size());

        // 财务视角全量可见
        SettlementDetailView all = settlementEngine.detail(st1.getSettlementNo(), finance);
        assertNotNull(all.getSettlement());
        assertEquals(1, all.getItems().size());

        // 代理不能生成结算
        BusinessException noGen = assertThrows(BusinessException.class,
                () -> settlementEngine.generate("1997-06", v1));
        assertTrue(noGen.getMessage().contains("只有财务/系统"));
    }

    @Test
    void 结算CSV带BOM_财务含尾差列_代理只导自己行() {
        Site s1 = rateCard(1989, "1000");
        Site s2 = rateCard(1990, "1000");
        Customer c = customer();
        Agent a1 = agent(null);
        Agent a2 = agent(null);
        signedAgreement(a1, "1989-06", share(s1.getSiteNo(), "0.30"));
        signedAgreement(a2, "1990-06", share(s2.getSiteNo(), "0.20"));
        posting(c, 1989, s1, LocalDate.of(1989, 6, 2));
        posting(c, 1990, s2, LocalDate.of(1990, 6, 2));
        Settlement st1 = settlementEngine.generate("1989-06", finance);
        settlementEngine.generate("1990-06", finance);

        String csv = settlementEngine.csv("1989-06", finance);
        assertEquals('﻿', csv.charAt(0)); // BOM
        assertTrue(csv.contains("舍入尾差"));
        assertTrue(csv.contains(st1.getSettlementNo()));
        assertTrue(csv.contains("318.00"));

        // 代理视角导出：无平台/尾差列，只有自己 8 列表头
        SettlementViewer v1 = SettlementViewer.agent("agentA", a1.getId());
        String myCsv = settlementEngine.csv("1989-06", v1);
        assertEquals('﻿', myCsv.charAt(0));
        assertTrue(myCsv.contains("流水额,代理分成"));
        assertFalse(myCsv.contains("舍入尾差"));
        assertFalse(myCsv.contains("PLATFORM"));
        assertEquals(2, myCsv.split("\n").length); // 表头 + 自己一行

        // 代理导出别家期：无自己行但不报错（只含表头）
        SettlementViewer v2 = SettlementViewer.agent("agentB", a2.getId());
        String otherCsv = settlementEngine.csv("1989-06", v2);
        assertEquals(1, otherCsv.split("\n").length);
    }

    @Test
    void 未来期间不能结算_协议期间格式校验() {
        BusinessException future = assertThrows(BusinessException.class,
                () -> settlementEngine.generate("2099-01", finance));
        assertTrue(future.getMessage().contains("不能给未来期间"));
        BusinessException badFormat = assertThrows(BusinessException.class,
                () -> settlementEngine.generate("1989/06", finance));
        assertTrue(badFormat.getMessage().contains("yyyy-MM"));
    }

    @Test
    void 底账版本登记包含f9起步版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f9-agent-settlement-1"));
    }
}
