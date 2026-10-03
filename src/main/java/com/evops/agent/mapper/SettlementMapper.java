package com.evops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.agent.entity.Settlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {
    @Select("SELECT NEXT VALUE FOR seq_settlement_no")
    long nextSettlementSeq();
}
