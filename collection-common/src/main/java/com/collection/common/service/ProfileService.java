package com.collection.common.service;

import com.collection.common.model.UserProfile;

/**
 * 数据服务层 — 用户画像服务。对应领域模型 §3.2。
 *
 * <p>Phase 1 未填充的字段返回 null；消费方须做防御性 null 处理。
 */
public interface ProfileService {

    UserProfile getFullProfile(Long userId);
}
