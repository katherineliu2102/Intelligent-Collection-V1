package com.collection.service.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** t_admin_case_freeze 读取（供 PreFlight/CaseService 实时守卫）。 */
@Mapper
public interface AdminCaseFreezeMapper {

    @Select(
            "SELECT COUNT(1) FROM t_admin_case_freeze "
                    + "WHERE case_id = #{caseId} AND status = 'FROZEN'")
    int countFrozenByCaseId(@Param("caseId") Long caseId);
}

