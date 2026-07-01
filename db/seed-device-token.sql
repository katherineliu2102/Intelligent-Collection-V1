-- =====================================================================
-- MOCASA 智能催收升级 Phase 1 — jpush enrichment 测试数据（t_user_device_token）
-- 目标库：ai_collection_db
-- 用途：L4b jpushToken enrichment（payload 缺失时读新库）；L4b-5 快照字段断言。
-- 关联：user_id 99000000–99000005（db/seed-test-cases.sql）。
-- Token 与 L4a 一致：94200 / push-test-token = 1a0018970bf0c19de04（application-local.yml）。
-- 实际 Push 下发仍由 channel.notification.push-test-token 强制覆盖（适配器层）。
-- 幂等：PRIMARY KEY(user_id) + ON DUPLICATE KEY UPDATE。
-- 用法：mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < db/seed-device-token.sql
-- =====================================================================

INSERT INTO t_user_device_token (user_id, jpush_token, synced_at)
VALUES
    (99000000, '1a0018970bf0c19de04', NOW()),
    (99000001, '1a0018970bf0c19de04', NOW()),
    (99000002, '1a0018970bf0c19de04', NOW()),
    (99000003, '1a0018970bf0c19de04', NOW()),
    (99000004, '1a0018970bf0c19de04', NOW()),
    (99000005, '1a0018970bf0c19de04', NOW())
ON DUPLICATE KEY UPDATE
    jpush_token = VALUES(jpush_token),
    synced_at   = VALUES(synced_at);

SELECT user_id, jpush_token, synced_at FROM t_user_device_token
 WHERE user_id BETWEEN 99000000 AND 99000005 ORDER BY user_id;
