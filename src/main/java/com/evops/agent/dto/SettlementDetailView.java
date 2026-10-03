package com.evops.agent.dto;

import com.evops.agent.entity.Settlement;
import com.evops.agent.entity.SettlementItem;
import lombok.Data;

import java.util.List;

/**
 * 结算快照全量档案：头（总额对平 + 舍入尾差）+ 按方汇总 + 逐单明细。
 * 代理视角下 parties 只含自己那行、items 只含挂自己协议的单；头为 null（看不到全盘总额）。
 */
@Data
public class SettlementDetailView {
    /** 财务/系统视角有值；代理自助视角为 null */
    private Settlement settlement;
    private List<PartyShareRow> parties;
    private List<SettlementItem> items;
}
