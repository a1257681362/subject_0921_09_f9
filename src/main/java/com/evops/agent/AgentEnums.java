package com.evops.agent;

/** 代理协议与结算状态口径。 */
public final class AgentEnums {
    private AgentEnums() {}

    /** 代理档案状态：在册 / 停用 */
    public static final String AGENT_ACTIVE = "ACTIVE";
    public static final String AGENT_DISABLED = "DISABLED";

    /** 协议主档状态：编制中 / 生效中 / 已封存 */
    public static final String AGREEMENT_DRAFT = "DRAFT";
    public static final String AGREEMENT_EFFECTIVE = "EFFECTIVE";
    public static final String AGREEMENT_CLOSED = "CLOSED";

    /** 协议版本状态：草稿 / 生效中 / 已被新版取代 / 已作废 */
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";
    public static final String VERSION_REVOKED = "REVOKED";

    /** 结算快照状态：生成即确认冻结（无改单路径） */
    public static final String SETTLEMENT_CONFIRMED = "CONFIRMED";

    /** 结算明细：已挂协议 / 未挂协议（全额归平台） */
    public static final int ASSIGNED_YES = 1;
    public static final int ASSIGNED_NO = 0;

    /** 首版版本号 */
    public static final int FIRST_VERSION_NO = 1;
}
