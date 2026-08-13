package com.collection.service.impl;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.service.CaseService;
import com.collection.service.mapper.AiCollectionCaseMapper;
import com.collection.service.mapper.AiCollectionCaseRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** t_ai_collection 的运行时 CaseService；启用 collection.case-service=ai。 */
@Service
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "ai")
public class AiCollectionCaseService implements CaseService {

    @Resource private AiCollectionCaseMapper mapper;

    @Value("${collection.repayment-url-template:https://app.mocasa.test/repay/{caseId}}")
    private String repaymentUrlTemplate;

    @Override
    public CaseInfo getCaseInfo(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        CaseInfo info = new CaseInfo();
        info.setCaseId(caseId);
        info.setUserId(row.getUserId());
        info.setDpd(row.getDpd());
        info.setStage(stage(row));
        info.setProduct(row.getProduct());
        info.setCaseStatus(row.getCollectionStatus());
        info.setTotalOutstanding(row.getTotalOutstanding());
        info.setDueDate(row.getDueDate());
        info.setRepaid(isSettled(row));
        return info;
    }

    @Override
    public CaseContext buildContext(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        CaseContext context = new CaseContext();
        context.setCaseId(caseId);
        context.setUserId(row.getUserId());
        context.setDpd(row.getDpd());
        context.setStage(stage(row));
        context.setProduct(row.getProduct());
        context.setTotalOutstanding(row.getTotalOutstanding());
        context.setPenaltyAmount(row.getPenaltyAmount());
        context.setDueDate(row.getDueDate());
        context.setCaseStatus(row.getCollectionStatus());
        context.setCollectionStatus(
                "CEASED".equalsIgnoreCase(row.getCollectionStatus()) ? "CEASED" : "ACTIVE");
        context.setRepaymentUrl(repaymentUrlTemplate.replace("{caseId}", String.valueOf(caseId)));
        context.setStrategyTone("STANDARD");
        context.setComplaintFrozen(false);
        return context;
    }

    @Override
    public ContactHistory buildContactHistory(Long userId, Long caseId) {
        ContactHistory history = new ContactHistory();
        history.setStageEntryDate(LocalDate.now());
        return history;
    }

    @Override
    public ContextSnapshot getContextSnapshot(Long caseId) {
        AiCollectionCaseRow row = require(caseId);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(buildContext(caseId));
        snapshot.setContactHistory(buildContactHistory(row.getUserId(), caseId));
        UserProfile profile = new UserProfile();
        profile.setUserId(row.getUserId());
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName(row.getBorrowerName());
        basic.setPrimaryPhone(row.getBorrowerPhone());
        basic.setEmail(row.getBorrowerEmail());
        basic.setLanguage(row.getBorrowerLanguage() == null ? "en" : row.getBorrowerLanguage());
        profile.setBasic(basic);
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        device.setJpushToken(row.getPushToken());
        profile.setDevice(device);
        snapshot.setUserProfile(profile);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setSnapshotVersion("ai-" + row.getCaseVersion());
        return snapshot;
    }

    @Override
    public boolean isRepaid(Long caseId) {
        AiCollectionCaseRow row = mapper.selectByCaseId(caseId);
        return row != null && isSettled(row);
    }

    @Override
    public List<Long> findActiveCaseIdsAfter(Long lastCaseId, int limit) {
        return mapper.selectCaseIdsAfter(lastCaseId == null ? 0L : lastCaseId, limit);
    }

    private AiCollectionCaseRow require(Long caseId) {
        AiCollectionCaseRow row = mapper.selectByCaseId(caseId);
        if (row == null) {
            throw new IllegalStateException("t_ai_collection 无 case_id=" + caseId + " 的案件");
        }
        return row;
    }

    private Stage stage(AiCollectionCaseRow row) {
        return row.getStage() == null ? Stage.fromDpd(row.getDpd()) : Stage.valueOf(row.getStage());
    }

    private boolean isSettled(AiCollectionCaseRow row) {
        return "SETTLED".equalsIgnoreCase(row.getCollectionStatus());
    }
}
