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

    /** 行锁读当前版本：同案件的并发事实事件据此串行，避免旧版本覆盖新版本。 */
    @Select("SELECT case_version FROM t_ai_collection WHERE case_id = #{caseId} FOR UPDATE")
    Long selectVersionForUpdate(@Param("caseId") Long caseId);

    @Insert(
            "INSERT INTO t_ai_collection (case_id, user_id, case_version, dpd, stage, "
                    + "collection_status, product, total_outstanding, penalty_amount, remaining_amount, "
                    + "due_date, borrower_name, borrower_phone, borrower_email, borrower_language, "
                    + "push_token, updated_at, synced_at) VALUES "
                    + "(#{caseId}, #{userId}, #{caseVersion}, #{dpd}, #{stage}, "
                    + "#{collectionStatus}, #{product}, #{totalOutstanding}, #{penaltyAmount}, #{remainingAmount}, "
                    + "#{dueDate}, #{borrowerName}, #{borrowerPhone}, #{borrowerEmail}, #{borrowerLanguage}, "
                    + "#{pushToken}, #{updatedAt}, NOW())")
    int insert(CaseProjection projection);

    @Update(
            "UPDATE t_ai_collection SET user_id = #{userId}, case_version = #{caseVersion}, "
                    + "dpd = #{dpd}, stage = #{stage}, collection_status = #{collectionStatus}, "
                    + "product = #{product}, total_outstanding = #{totalOutstanding}, "
                    + "penalty_amount = #{penaltyAmount}, remaining_amount = #{remainingAmount}, "
                    + "due_date = #{dueDate}, borrower_name = #{borrowerName}, "
                    + "borrower_phone = #{borrowerPhone}, borrower_email = #{borrowerEmail}, "
                    + "borrower_language = #{borrowerLanguage}, push_token = #{pushToken}, "
                    + "updated_at = #{updatedAt}, synced_at = NOW() "
                    + "WHERE case_id = #{caseId} AND case_version < #{caseVersion}")
    int updateIfNewer(CaseProjection projection);
}
