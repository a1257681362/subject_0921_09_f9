package com.evops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.agent.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
    @Select("SELECT NEXT VALUE FOR seq_agent_no")
    long nextAgentSeq();
}
