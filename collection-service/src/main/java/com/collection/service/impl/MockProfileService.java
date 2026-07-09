package com.collection.service.impl;

import com.collection.common.model.UserProfile;
import com.collection.common.service.ProfileService;
import java.util.Collections;
import org.springframework.stereotype.Service;

/**
 * Phase 1 Mock ProfileService —— 按约定 userId 返回联调画像（见功能测试指南 §1.3）。
 *
 * <p>⚠ 替换指引：服务层开发者用真实实现替换（映射 t_user_basis / t_user_work 等 8+ 张表）。
 */
@Service
public class MockProfileService implements ProfileService {

    @Override
    public UserProfile getFullProfile(Long userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);

        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName("Mock User " + userId);
        basic.setPrimaryPhone("63917" + String.format("%07d", userId % 10_000_000L));
        basic.setAlternatePhones(Collections.emptyList());

        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();

        // L4a-1 三渠道合成 case（94999）
        if (L4aCaseRegistry.isThreeChannel(userId)) {
            basic.setName("l4a three channel");
            basic.setPrimaryPhone(SmsCaseRegistry.PROD_MOBILE_A);
            basic.setEmail(EmailCaseRegistry.TEST_EMAIL);
            device.setJpushToken(PushCaseRegistry.PUSH_TEST_JPUSH_TOKEN);
        }

        // L4a Guard 频率：94805 两步 SMS（第二步 FREQUENCY_LIMIT）
        if (L4aCaseRegistry.isGuardFrequency(userId)) {
            basic.setName("l4a guard frequency");
            basic.setPrimaryPhone(SmsCaseRegistry.TEST_MOBILE_VIRTUAL);
        }

        // L4a Guard block：无手机号/邮箱（94801）
        if (L4aCaseRegistry.isGuardNoPhone(userId)) {
            basic.setName("l4a guard no phone");
            basic.setPrimaryPhone("");
            basic.setEmail("");
        }

        // L4a REBUILD：正常邮箱，计划内用无效 templateId 触发 dispatch 失败（94804）
        if (L4aCaseRegistry.isRebuildFail(userId)) {
            basic.setName("l4a rebuild fail");
            basic.setEmail(EmailCaseRegistry.TEST_EMAIL);
        }

        // SMS 联调：94100–94102 指定手机号（见 SmsCaseRegistry）
        if (SmsCaseRegistry.isSmsTestCase(userId)) {
            SmsCaseRegistry.find(userId)
                    .ifPresent(
                            tc -> {
                                basic.setName(tc.displayName());
                                basic.setPrimaryPhone(tc.primaryPhone);
                            });
        }

        // Push 联调：94200/94201（假 jpushToken 占位 / 无 token 验 fallback，见 PushCaseRegistry）
        if (PushCaseRegistry.isPushTestCase(userId)) {
            PushCaseRegistry.find(userId)
                    .ifPresent(
                            tc -> {
                                basic.setName(tc.displayName());
                                basic.setPrimaryPhone(tc.primaryPhone);
                                if (tc.jpushToken != null) {
                                    device.setJpushToken(tc.jpushToken);
                                }
                            });
        }

        // Email E2E 联调：92001–93404 → 126；95xxx → Gmail（收件箱见各 case testEmail）
        if (EmailCaseRegistry.isEmailTestCase(userId)) {
            EmailCaseRegistry.find(userId)
                    .ifPresent(
                            tc -> {
                                basic.setName(tc.displayName());
                                basic.setEmail(tc.testEmail);
                            });
        } else if (!L4aCaseRegistry.isL4aCase(userId) && userId != 90005L) {
            basic.setEmail("mock" + userId + "@mocasa.test");
        }

        // 90002：联调真实 jpushToken（与 PushCaseRegistry 94200 同源，供 ingest 全链路）
        if (userId == 90002L) {
            device.setJpushToken(PushCaseRegistry.PUSH_TEST_JPUSH_TOKEN);
        }
        // 90003：故意无 jpushToken，供 TC-PUSH-02
        if (userId == 91000L || userId == 91001L) {
            device.setJpushToken("mock-jpush-rid-" + userId);
        }

        profile.setBasic(basic);
        profile.setDevice(device);
        profile.setProfileCompleteness(0.5);
        return profile;
    }
}
