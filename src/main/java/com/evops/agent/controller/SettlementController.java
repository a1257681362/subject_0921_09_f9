package com.evops.agent.controller;

import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.entity.Settlement;
import com.evops.agent.entity.SettlementItem;
import com.evops.agent.service.SettlementEngine;
import com.evops.agent.service.SettlementScheduler;
import com.evops.agent.support.AgentAccess;
import com.evops.agent.support.SettlementViewer;
import com.evops.common.ApiResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F9 结算快照：财务按期生成（生成即冻结、历史期不重算）、翻查总额对平与舍入尾差、导出 CSV；
 * 代理通过 /api/agent/my/* 只查自己的账单，别家与平台分成看不见。
 */
@RestController
public class SettlementController {

    private final SettlementEngine engine;
    private final SettlementScheduler scheduler;
    private final AgentAccess access;

    public SettlementController(SettlementEngine engine,
                                SettlementScheduler scheduler,
                                AgentAccess access) {
        this.engine = engine;
        this.scheduler = scheduler;
        this.access = access;
    }

    /** 生成某期结算快照（财务/系统）：period=yyyy-MM。 */
    @PostMapping("/api/settlements/generate")
    public ApiResponse<Settlement> generate(@RequestParam String period) {
        SettlementViewer viewer = access.currentViewer();
        return ApiResponse.ok(engine.generate(period, viewer));
    }

    /** 手工触发一轮月初自动生成（补生成上月；已存在则跳过）。 */
    @PostMapping("/api/settlements/scan")
    public ApiResponse<Map<String, Integer>> scan() {
        access.requireFinance();
        int generated = scheduler.generateLastMonth();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("generated", generated);
        return ApiResponse.ok(result);
    }

    /** 结算头翻查：?period=；财务全部，代理只见有自己分成的期。 */
    @GetMapping("/api/settlements")
    public ApiResponse<List<Settlement>> list(@RequestParam(required = false) String period) {
        return ApiResponse.ok(engine.list(period, access.currentViewer()));
    }

    /** 结算全量档案：财务含头+按方汇总+全明细；代理只含自己那行汇总与明细。 */
    @GetMapping("/api/settlements/{settlementNo}")
    public ApiResponse<SettlementDetailView> detail(@PathVariable String settlementNo) {
        return ApiResponse.ok(engine.detail(settlementNo, access.currentViewer()));
    }

    /** 按期间查明细：财务全部，代理只返回自己的行。 */
    @GetMapping("/api/settlements/period/{period}/items")
    public ApiResponse<List<SettlementItem>> periodItems(@PathVariable String period) {
        return ApiResponse.ok(engine.itemsByPeriod(period, access.currentViewer()));
    }

    /** 导出结算 CSV（BOM）：财务全量含平台分成与尾差；代理只导自己的行。 */
    @GetMapping("/api/settlements/export")
    public ResponseEntity<byte[]> export(@RequestParam String period) {
        byte[] csv = engine.csv(period, access.currentViewer()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-settlement-" + period + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ---- 代理自助账单：只看自己 ----

    /** 我的结算单（有自己分成的期，可按期间过滤）。 */
    @GetMapping("/api/agent/my/settlements")
    public ApiResponse<List<Settlement>> mySettlements(@RequestParam(required = false) String period) {
        return ApiResponse.ok(engine.list(period, access.currentViewer()));
    }

    /** 我的结算单详情（只有自己的汇总行与明细，看不到头/平台/别家）。 */
    @GetMapping("/api/agent/my/settlements/{settlementNo}")
    public ApiResponse<SettlementDetailView> myDetail(@PathVariable String settlementNo) {
        return ApiResponse.ok(engine.detail(settlementNo, access.currentViewer()));
    }
}
