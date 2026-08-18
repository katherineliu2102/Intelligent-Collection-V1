package com.collection.service.mapper;

import com.collection.common.model.CaseProjection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** t_ai_collection 的写入映射。只允许 ingestion 投影管道调用，数仓不再直连业务库写表。 */
@Mapper
public interface AiCollectionProjectionMapper {

    /** 行锁读当前内容指纹：同案件的并发事实事件据此串行，避免相同快照重复写入。 */
    @Select("SELECT case_version FROM t_ai_collection WHERE case_id = #{caseId} FOR UPDATE")
    String selectVersionForUpdate(@Param("caseId") Long caseId);

    @Select(
            "SELECT case_id, user_id, case_version, dpd, stage, collection_status, product, "
                    + "overdue_amount, total_outstanding, penalty_amount, remaining_amount, upcoming_amount, "
                    + "due_date, next_due_date, borrower_name, borrower_phone, borrower_email, "
                    + "borrower_language, push_token, updated_at "
                    + "FROM t_ai_collection WHERE case_id = #{caseId} FOR UPDATE")
    CaseProjection selectProjectionForUpdate(@Param("caseId") Long caseId);

    @Insert(
            "INSERT INTO t_ai_collection (case_id, user_id, case_version, dpd, stage, "
                    + "collection_status, product, overdue_amount, total_outstanding, penalty_amount, "
                    + "remaining_amount, upcoming_amount, due_date, next_due_date, "
                    + "borrower_name, borrower_phone, borrower_email, borrower_language, "
                    + "push_token, updated_at, synced_at) VALUES "
                    + "(#{caseId}, #{userId}, #{caseVersion}, #{dpd}, #{stage}, "
                    + "#{collectionStatus}, #{product}, #{overdueAmount}, #{totalOutstanding}, "
                    + "#{penaltyAmount}, #{remainingAmount}, #{upcomingAmount}, #{dueDate}, #{nextDueDate}, "
                    + "#{borrowerName}, #{borrowerPhone}, #{borrowerEmail}, #{borrowerLanguage}, "
                    + "#{pushToken}, #{updatedAt}, NOW())")
    int insert(CaseProjection projection);

    @Update(
            "UPDATE t_ai_collection SET user_id = #{userId}, case_version = #{caseVersion}, "
                    + "dpd = #{dpd}, stage = #{stage}, collection_status = #{collectionStatus}, "
                    + "product = #{product}, overdue_amount = #{overdueAmount}, "
                    + "total_outstanding = #{totalOutstanding}, penalty_amount = #{penaltyAmount}, "
                    + "remaining_amount = #{remainingAmount}, upcoming_amount = #{upcomingAmount}, "
                    + "due_date = #{dueDate}, next_due_date = #{nextDueDate}, borrower_name = #{borrowerName}, "
                    + "borrower_phone = #{borrowerPhone}, borrower_email = #{borrowerEmail}, "
                    + "borrower_language = #{borrowerLanguage}, push_token = #{pushToken}, "
                    + "updated_at = #{updatedAt}, synced_at = NOW() "
                    + "WHERE case_id = #{caseId} AND case_version <> #{caseVersion}")
    int updateIfChanged(CaseProjection projection);

    @Update(
            "UPDATE t_ai_collection SET user_id = #{userId}, dpd = #{dpd}, stage = #{stage}, "
                    + "collection_status = #{collectionStatus}, overdue_amount = #{overdueAmount}, "
                    + "total_outstanding = #{totalOutstanding}, penalty_amount = #{penaltyAmount}, "
                    + "upcoming_amount = #{upcomingAmount}, next_due_date = #{nextDueDate}, "
                    + "updated_at = #{updatedAt}, synced_at = NOW() WHERE case_id = #{caseId}")
    int updateRepaymentDelta(CaseProjection projection);
}
