package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.PhoneValidity;
import com.collection.common.enums.SensitivityTag;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户画像。ContextSnapshot 组成部分。对应领域模型 §3.2。
 * 由 ProfileService.getFullProfile(userId) 构建；Phase 1 部分维度渐进填充（可能为 null）。
 */
@Data
public class UserProfile {

    private Long userId;
    private BasicInfo basic;
    private WorkInfo work;
    private List<ContactInfo> contacts;
    private RepaymentInfo repayment;
    private BehaviorProfile behavior;
    private DeviceInfo device;
    private RiskScore risk;
    /** 画像完整度 0.0-1.0（非空字段数 / 总字段数）。 */
    private double profileCompleteness;

    @Data
    public static class BasicInfo {
        private String name;
        private String gender;
        private Integer age;
        private String education;
        private String maritalStatus;
        private String idNumber;
        private String address;
        private String primaryPhone;
        private String email;
        private List<String> alternatePhones;
    }

    @Data
    public static class WorkInfo {
        private String occupation;
        private String companyName;
        private String workPhone;
        private String monthlyIncomeRange;
    }

    @Data
    public static class ContactInfo {
        private String name;
        private String phone;
        private String relationship;
        private String source;
    }

    @Data
    public static class RepaymentInfo {
        private int totalLoans;
        private BigDecimal paidAmount;
        private BigDecimal remainingAmount;
        private BigDecimal overdueFee;
        private BigDecimal penaltyFee;
        private LocalDate lastRepaymentDate;
        private BigDecimal lastRepaymentAmount;
        private int payCount;
    }

    @Data
    public static class BehaviorProfile {
        private Integer bestContactHour;
        private ChannelType preferredChannel;
        private Map<ChannelType, Boolean> channelReachability;
        private LocalDateTime lastEffectiveContactTime;
        private ChannelType lastEffectiveChannel;
        private LocalDateTime appLastActiveTime;
    }

    @Data
    public static class DeviceInfo {
        private String deviceModel;
        private String osVersion;
        private PhoneValidity phoneValidity;
        private Boolean viberRegistered;
        private Boolean whatsappRegistered;
        private String fcmToken;
    }

    @Data
    public static class RiskScore {
        private Double repaymentAbilityScore;
        private Double collectionDifficultyScore;
        private Double collectionPriority;
        private SensitivityTag sensitivityTag;
        private Double ptpFulfillRate;
        private int complaintCount;
    }
}
