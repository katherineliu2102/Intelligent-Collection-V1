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

        // Email E2E 联调：93xxx / 92001-92002 → wzynju@126.com
        if (MockEmailTestCases.isEmailTestCase(userId)) {
            MockEmailTestCases.find(userId).ifPresent(tc -> basic.setName(tc.displayName()));
            basic.setEmail(MockEmailTestCases.TEST_EMAIL);
        } else if (userId != 90005L) {
            basic.setEmail("mock" + userId + "@mocasa.test");
        }

        // 90003：故意无 fcmToken，供 TC-PUSH-02
        if (userId == 90002L || userId == 91000L || userId == 91001L) {
            device.setFcmToken("mock-fcm-token-" + userId);
        }

        profile.setBasic(basic);
        profile.setDevice(device);
        profile.setProfileCompleteness(0.5);
        return profile;
    }
}
