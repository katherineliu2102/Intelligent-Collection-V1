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

    /**
     * 还款增量更新余额、dpd 与 {@code stage}。
     *
     * <p>还款改变未结清的到期期次，数仓在 `repaymentEvent` 里给出的 stage 与 dpd 是同一时刻的口径； 只写 dpd 会让投影出现「stage 来自入案、dpd
     * 来自还款」的自相矛盾组合。
     *
     * <p>{@code stage} 是普通赋值而非 {@code COALESCE} 兜底：数仓口径下 {@code null} 是有含义的取值（下一个未还 dueDate 超过 3
     * 天即不属任何催收阶段），{@code COALESCE} 会把它当缺省值吞掉，导致基线阶段永远清不掉。 「缺字段时保基线」由合并逻辑负责——{@code mergeRepayment}
     * 的入参就是 {@code SELECT ... FOR UPDATE} 读出的基线行， 不覆盖时它本身就携带基线值。
     *
     * <p>注意本 SQL 不发 {@code STAGE_CHANGED}：投影阶段与计划阶段的对齐仍由日切负责（引擎 §4.6），所以升档 最迟在下个日切生效，不会因为还款绕过升档决策。
     */
    @Update(
            "UPDATE t_ai_collection SET user_id = #{userId}, dpd = #{dpd}, "
                    + "stage = #{stage}, "
                    + "collection_status = #{collectionStatus}, overdue_amount = #{overdueAmount}, "
                    + "total_outstanding = #{totalOutstanding}, penalty_amount = #{penaltyAmount}, "
                    + "upcoming_amount = #{upcomingAmount}, next_due_date = #{nextDueDate}, "
                    + "updated_at = #{updatedAt}, synced_at = NOW() WHERE case_id = #{caseId}")
    int updateRepaymentDelta(CaseProjection projection);
}
