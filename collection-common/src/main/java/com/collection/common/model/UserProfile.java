package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.PhoneValidity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
<<<<<<< HEAD
 * 用户画像。ContextSnapshot 组成部分。对应领域模型 §3.2。 由 ProfileService.getFullProfile(userId) 构建；Phase 1
=======
 * 用户画像。ContextSnapshot 组成部分。对应领域模型 §4.2。 由 ProfileService.getFullProfile(userId) 构建；Phase 1
>>>>>>> origin/ca_branch
 * 部分维度渐进填充（可能为 null）。
 */
@Data
public class UserProfile {

    private Long userId;
    private BasicInfo basic;
    private WorkInfo work;
    private List<ContactInfo> contacts;
    private BehaviorProfile behavior;
    private DeviceInfo device;
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
        /** 邮箱地址，EMAIL 渠道 targetAddress 来源。来源 t_user_basis / 信贷用户表。 */
        private String email;
        /** 用户语言偏好 ISO 639-1（tl/en）；StepResolver → metadata.language；默认 en。 */
        private String language;

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
        /**
         * JPush Registration ID，PUSH 渠道 targetAddress 来源（经通知中心 → JPush）。 来源：App 登录/启动上报 → 信贷/App 后端
         * → t_user_equipment → ProfileService。 契约已拍板（2026-06-09）：以 jpushToken 为准，fcmToken 已移除。
         */
        private String jpushToken;
    }
}
