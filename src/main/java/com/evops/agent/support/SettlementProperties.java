package com.evops.agent.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 结算配置：每月初自动生成上月结算快照。 */
@Data
@Component
@ConfigurationProperties(prefix = "settlement")
public class SettlementProperties {

    /** 是否启用定时自动生成（演示/手工环境可关） */
    private boolean autoGenerateEnabled = true;

    /** 自动生成时刻的 cron（默认每月 1 日 02:17，避开整点） */
    private String autoGenerateCron = "0 17 2 1 * *";
}
