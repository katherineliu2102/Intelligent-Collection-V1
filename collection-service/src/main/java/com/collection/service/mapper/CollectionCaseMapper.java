package com.collection.service.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 旧库 t_collection 只读映射（真实案件数据来源）。 仅 Phase 1 真实链路测试使用；通过 loan_id 定位（caseId = loan_id）。 */
@Mapper
public interface CollectionCaseMapper {

    /** 按 loan_id 取最近一条催收记录（同一 loan_id 可能有多条，取 create_time 最新）。 */
    @Select(
            "SELECT loan_id, user_id, overdue_days, repayment_date, total_not_paid, overdue, "
                    + "       full_repay_time, real_name, phone, email, app_name, colleciton_status "
                    + "FROM t_collection WHERE loan_id = #{loanId} "
                    + "ORDER BY create_time DESC LIMIT 1")
    CollectionCaseRow selectByLoanId(@Param("loanId") String loanId);

    /** 日切全量扫描的 keyset 页；只返回当前页 loan_id，调用方逐笔读取最新记录，避免将旧库全表载入 JVM。 */
    @Select(
            "SELECT DISTINCT loan_id FROM t_collection "
                    + "WHERE loan_id > #{afterLoanId} "
                    + "  AND overdue_days > 0 "
                    + "  AND full_repay_time IS NULL "
                    + "  AND total_not_paid > 0 "
                    + "ORDER BY loan_id LIMIT #{limit}")
    List<Long> selectActiveLoanIdsAfter(
            @Param("afterLoanId") Long afterLoanId, @Param("limit") int limit);

    /**
     * 从 t_user_extend 取极光 Registration ID（ji_guang_token）。 PUSH 渠道 targetAddress 来源；token 不存在时返回
     * null（PushAdapter 自动 fallback SMS）。
     */
    @Select("SELECT ji_guang_token FROM t_user_extend WHERE user_id = #{userId} LIMIT 1")
    String selectJiGuangToken(@Param("userId") String userId);
}
