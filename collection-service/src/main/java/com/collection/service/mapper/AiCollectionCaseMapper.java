package com.collection.service.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 新系统运行态案件表 t_ai_collection 的只读映射。 */
@Mapper
public interface AiCollectionCaseMapper {

    @Select(
            "SELECT case_id, user_id, case_version, dpd, stage, collection_status, product, "
                    + "total_outstanding, penalty_amount, remaining_amount, due_date, "
                    + "borrower_name, borrower_phone, borrower_email, borrower_language, push_token, updated_at "
                    + "FROM t_ai_collection WHERE case_id = #{caseId} LIMIT 1")
    AiCollectionCaseRow selectByCaseId(@Param("caseId") Long caseId);

    @Select(
            "SELECT case_id FROM t_ai_collection "
                    + "WHERE case_id > #{afterCaseId} "
                    + "  AND collection_status IN ('IN_COLLECTION', 'SETTLED') "
                    + "ORDER BY case_id LIMIT #{limit}")
    List<Long> selectCaseIdsAfter(
            @Param("afterCaseId") Long afterCaseId, @Param("limit") int limit);
}
