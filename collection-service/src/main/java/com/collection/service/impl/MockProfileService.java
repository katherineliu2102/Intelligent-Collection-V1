package com.collection.service.impl;

import com.collection.common.model.UserProfile;
import com.collection.common.service.ProfileService;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Phase 1 Mock ProfileService —— 返回最小可用画像。
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
        basic.setPrimaryPhone("0917" + String.format("%07d", userId % 10_000_000));
        basic.setAlternatePhones(Collections.emptyList());
        profile.setBasic(basic);

        profile.setProfileCompleteness(0.2);
        return profile;
    }
}
