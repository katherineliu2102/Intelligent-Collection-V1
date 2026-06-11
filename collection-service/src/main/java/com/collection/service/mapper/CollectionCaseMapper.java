package com.collection.service.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 旧库 t_collection 只读映射（真实案件数据来源）。
 * 仅 Phase 1 真实链路测试使用；通过 loan_id 定位（caseId = loan_id）。
 */
@Mapper
public interface CollectionCaseMapper {

    /** 按 loan_id 取最近一条催收记录（同一 loan_id 可能有多条，取 create_time 最新）。 */
    @Select("SELECT loan_id, user_id, overdue_days, repayment_date, total_not_paid, " +
            "       full_repay_time, real_name, phone, email, app_name, colleciton_status " +
            "FROM t_collection WHERE loan_id = #{loanId} " +
            "ORDER BY create_time DESC LIMIT 1")
    CollectionCaseRow selectByLoanId(@Param("loanId") String loanId);
}
