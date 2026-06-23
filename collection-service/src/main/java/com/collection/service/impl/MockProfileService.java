package com.collection.service.impl;

import com.collection.common.model.UserProfile;
import com.collection.common.service.ProfileService;
import org.springframework.stereotype.Service;

import java.util.Collections;

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

        // SMS 联调：94100–94102 指定手机号（见 MockSmsTestCases）
        if (MockSmsTestCases.isSmsTestCase(userId)) {
            MockSmsTestCases.find(userId).ifPresent(tc -> {
                basic.setName(tc.displayName());
                basic.setPrimaryPhone(tc.primaryPhone);
            });
        }

        // Push 联调：94200/94201（假 jpushToken 占位 / 无 token 验 fallback，见 MockPushTestCases）
        if (MockPushTestCases.isPushTestCase(userId)) {
            MockPushTestCases.find(userId).ifPresent(tc -> {
                basic.setName(tc.displayName());
                basic.setPrimaryPhone(tc.primaryPhone);
                if (tc.jpushToken != null) {
                    device.setJpushToken(tc.jpushToken);
                }
            });
        }

        // Email E2E 联调：92001–93404 → wzynju@126.com（见 MockEmailTestCases）
        if (MockEmailTestCases.isEmailTestCase(userId)) {
            MockEmailTestCases.find(userId).ifPresent(tc -> basic.setName(tc.displayName()));
            basic.setEmail(MockEmailTestCases.TEST_EMAIL);
        } else if (userId != 90005L) {
            basic.setEmail("mock" + userId + "@mocasa.test");
        }

        // 94001：三渠道合成 case（L4a-1）——同一 caseId 同时挂真 SMS + 真 JPush + 真 Email，
        // 供 channel.debug.three-channel-step（SMS→PUSH→EMAIL）逐步取址。
        // Email 用 Gmail（zoewang532@gmail.com）：多数用户为 Gmail 且投递已验证可达，正常断言收到。
        if (userId == 94001L) {
            basic.setName("Tri Channel Synthetic");
            basic.setPrimaryPhone(MockSmsTestCases.PROD_MOBILE_A);
            device.setJpushToken(MockPushTestCases.PUSH_TEST_JPUSH_TOKEN);
            basic.setEmail("zoewang532@gmail.com");
        }

        // 90002：联调真实 jpushToken（与 MockPushTestCases 94200 同源，供 ingest 全链路）
        if (userId == 90002L) {
            device.setJpushToken(MockPushTestCases.PUSH_TEST_JPUSH_TOKEN);
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
