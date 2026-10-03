package com.evops.agent.service;

import com.evops.agent.support.SettlementProperties;
import com.evops.agent.support.SettlementViewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 结算期到自动出快照：按配置 cron（默认每月 1 日 02:17）给刚结束的上月生成结算。
 * 已存在的期间引擎会直接拒（历史期不重算），所以重复触发/多节点天然幂等；
 * 可用 settlement.auto-generate-enabled 关闭。异常只记录，不停调度线程。
 */
@Component
public class SettlementScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementEngine engine;
    private final SettlementProperties properties;

    public SettlementScheduler(SettlementEngine engine, SettlementProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.isAutoGenerateEnabled()) {
            return;
        }
        registrar.addTriggerTask(this::generateLastMonth,
                new CronTrigger(properties.getAutoGenerateCron()));
    }

    /** 手工触发入口（POST /api/settlements/scan）：补生成上月。 */
    public int generateLastMonth() {
        String period = YearMonth.now().minusMonths(1).toString();
        try {
            engine.generate(period, SettlementViewer.finance("SYSTEM"));
            log.warn("自动生成 {} 期代理分账结算快照", period);
            return 1;
        } catch (com.evops.common.BusinessException e) {
            // 已生成（幂等）/ 无流水不视为故障
            log.info("{} 期结算自动生成跳过：{}", period, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.error(period + " 期结算自动生成失败", e);
            return 0;
        }
    }
}
