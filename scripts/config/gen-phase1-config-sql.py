#!/usr/bin/env python3
"""生成 db/seed-phase1-config.sql —— Phase 1 生产触达配置（文案槽位 + dayBlocks 计划模板）。

内容全部来自两份规格，脚本不发明任何文案或时间：
  - docs/channel/MOCASA催收系统升级_Phase1_渠道模板清单与配置.md §4.1/§5.1/§7（17 条 SMS/Push 文案、SendGrid 模板号）
  - docs/channel/MOCASA催收系统升级_Phase1_渠道编排规格.md §7.4~§7.9（各 Stage 日块与槽位时间）

S4 覆盖 D+31~D+90 共 60 个日块，手写 JSON 不现实，故用脚本生成并把生成物入库审阅。

用法：python3 scripts/config/gen-phase1-config-sql.py > db/seed-phase1-config.sql
"""

import json

TENANT = "mocasa-ph"

# --- 文案槽位（id 固定，供 plan_json 的 templateId 引用） -------------------
SMS_SCRIPTS = [
    (104, "S0_REMINDER", "MOCASA: {name}, your PHP {amount} payment is due soon. Please pay on time to keep your account current. Pay: {repaymentUrl}"),
    (105, "S0_REMINDER_URGENT", "MOCASA: {name}, your PHP {amount} payment is due tomorrow. Please pay today to keep your account current. Pay: {repaymentUrl}"),
    (106, "S0_DUE_TODAY", "MOCASA: {name}, your PHP {amount} payment is due today. Please pay now to stay current. Pay: {repaymentUrl}"),
    (101, "S1_SMS_STANDARD", "MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Please settle PHP {amount} promptly. Pay: {repaymentUrl}"),
    (107, "S2_SMS_STANDARD", "MOCASA Collections: {name}, {dpd} days overdue. See your personalized payment options for PHP {amount}. Pay: {repaymentUrl}"),
    (103, "S2_SMS_FIRM", "MOCASA Collections: {name}, {dpd} days overdue; your account may be reported as delinquent. See your options for PHP {amount}. Pay: {repaymentUrl}"),
    (108, "S3_SMS_STANDARD", "MOCASA Collections: {name}, {dpd} days overdue. Delay may limit your account and future loan eligibility. Please settle PHP {amount} or view your payment options. Pay: {repaymentUrl}"),
    (109, "S3_SMS_FIRM", "MOCASA Collections: {name}, {dpd} days overdue and may be recorded as delinquent. Please settle PHP {amount} or view your payment options. Pay: {repaymentUrl}"),
    (110, "S4_SMS_STANDARD", "MOCASA Collections: {name}, final notice. {dpd} days overdue and at risk of a delinquency record. Please resolve PHP {amount} using your payment options. Pay: {repaymentUrl}"),
    (111, "S4_SMS_FIRM", "MOCASA Collections: {name}, severely overdue ({dpd} days) and may be recorded as delinquent. Please resolve PHP {amount} promptly. Pay: {repaymentUrl}"),
]

PUSH_SCRIPTS = [
    (121, "S0_REMINDER", "Payment due soon", "{name}, PHP {amount} is due soon. Tap to pay in the SKYPAYLOANS app."),
    (122, "S0_REMINDER_URGENT", "Due tomorrow", "{name}, PHP {amount} is due tomorrow. Tap to pay in the SKYPAYLOANS app."),
    (123, "S0_DUE_TODAY", "Due today", "{name}, PHP {amount} is due today. Tap to pay now."),
    (102, "S1_PUSH_STANDARD", "Overdue: PHP {amount}", "{name}, your payment is past due. Tap to settle in the app."),
    (124, "S2_PUSH_STANDARD", "{dpd} days overdue", "See your personalized payment options for PHP {amount}. Tap to view."),
    (125, "S3_PUSH_STANDARD", "{dpd} days overdue", "Delay may limit your account. View your options for PHP {amount}. Tap now."),
    (126, "S4_PUSH_STANDARD", "Final notice: {dpd} days overdue", "Resolve PHP {amount} now. View your options in the app."),
]

# Phase 1 只发这 5 封里程碑 Email（编排规格 §7.9）。
# subject 逐字取自 docs/email-templates/subjects.md（SSOT）；正文托管在 SendGrid，
# 本表只存 external_template_id 与 subject 留痕，ConfigTemplateProvider 不读 content_json。
EMAIL_SCRIPTS = [
    (201, "S0_DUE_TODAY_EMAIL", "⚠️ Action Required: Your Payment Is Due Today", "d-9b485bfd24e14950a7811faf33c2b22f"),
    (203, "S1_EMAIL_OVERDUE_NOTICE", "Overdue: Payment Not Received—Late Fees Apply", "d-bc7f5aee7e304caf93ca4d435a73a1d7"),
    (202, "S2_EMAIL_ENTRY", "Notice: Account Escalated—Late Fees Accruing", "d-86ed8faae3b24489ad7db8a11067b8c4"),
    (204, "S4_EMAIL_ENTRY", "Formal Collection Notice—Pay Full Balance Immediately", "d-658d5be184ab4710a19c8419ed66bca9"),
    (206, "S4_EMAIL_PRE_CLOSE", "⚠️ Final Notice: Account Scheduled for Final Review", "d-881ce23667cc4df2abf82097b890cae1"),
]

# --- 计划模板 -------------------------------------------------------------
# Resolver 由 Stage+渠道+strategyTone(+dpd) 推导 scriptSlot，templateId 只作审计留痕，
# 故 FIRM 不需要独立 plan 模板（选模板只按 stage，见 ConfigTemplateProvider#reload）。
SMS_ID = {s[1]: s[0] for s in SMS_SCRIPTS}
PUSH_ID = {p[1]: p[0] for p in PUSH_SCRIPTS}
EMAIL_ID = {e[1]: e[0] for e in EMAIL_SCRIPTS}

MILESTONE_EMAIL = {
    0: "S0_DUE_TODAY_EMAIL",
    1: "S1_EMAIL_OVERDUE_NOTICE",
    4: "S2_EMAIL_ENTRY",
    31: "S4_EMAIL_ENTRY",
    75: "S4_EMAIL_PRE_CLOSE",
}

# AI 外呼话术在 Facade 侧维护，本系统不存正文，templateId 仅作审计留痕（Resolver 对 AI_CALL
# 走 MOCK_<templateId> 占位，不查 t_script_template）。
AI_CALL_ID = {"S1": 301, "S2": 302, "S3": 303, "S4": 304}

# 编排规格 §7.11：S1~S3 ≤2 呼/日；S4 D+31~60 维持 2 呼，D+61~90 降至 1 呼。
WAVE1_TIME = "09:15"
WAVE2_TIME = "14:30"
S4_WAVE2_LAST_DPD = 60


def slot(channel, time, template_id):
    return {"channel": channel, "time": time, "observeMin": 0, "templateId": template_id}


def day_block(dpd, slots):
    return {"dpdDay": dpd, "slots": slots}


def s0_blocks():
    """S0 全程 Push→SMS 单槽 08:00；D0 追加 14:00 Email（编排规格 §7.4）。"""
    blocks = []
    for dpd in (-3, -2):
        blocks.append(day_block(dpd, [slot("PUSH", "08:00", PUSH_ID["S0_REMINDER"])]))
    blocks.append(day_block(-1, [slot("PUSH", "08:00", PUSH_ID["S0_REMINDER_URGENT"])]))
    blocks.append(
        day_block(
            0,
            [
                slot("PUSH", "08:00", PUSH_ID["S0_DUE_TODAY"]),
                slot("EMAIL", "14:00", EMAIL_ID["S0_DUE_TODAY_EMAIL"]),
            ],
        )
    )
    return blocks


def overdue_blocks(dpd_from, dpd_to, stage, sms_slot, push_slot):
    """S1~S4 共用时间表：08:00 SMS / 09:15 AI / 12:00 Push / 14:30 AI，里程碑日加 14:00 Email。

    见编排规格 §7.5~§7.8。两次外呼都无条件铺槽：Phase 1 引擎不实现 VoiceQueue，
    「Wave-1 已接通则当日不再 Wave-2」由 LTH / Facade 侧承担（§7.1）。
    """
    blocks = []
    for dpd in range(dpd_from, dpd_to + 1):
        slots = [
            slot("SMS", "08:00", SMS_ID[sms_slot]),
            slot("AI_CALL", WAVE1_TIME, AI_CALL_ID[stage]),
            slot("PUSH", "12:00", PUSH_ID[push_slot]),
        ]
        if dpd in MILESTONE_EMAIL:
            slots.append(slot("EMAIL", "14:00", EMAIL_ID[MILESTONE_EMAIL[dpd]]))
        if stage != "S4" or dpd <= S4_WAVE2_LAST_DPD:
            slots.append(slot("AI_CALL", WAVE2_TIME, AI_CALL_ID[stage]))
        blocks.append(day_block(dpd, slots))
    return blocks


PLANS = [
    ("PH1_S0_STANDARD", "S0", s0_blocks()),
    ("PH1_S1_STANDARD", "S1", overdue_blocks(1, 3, "S1", "S1_SMS_STANDARD", "S1_PUSH_STANDARD")),
    ("PH1_S2_STANDARD", "S2", overdue_blocks(4, 15, "S2", "S2_SMS_STANDARD", "S2_PUSH_STANDARD")),
    ("PH1_S3_STANDARD", "S3", overdue_blocks(16, 30, "S3", "S3_SMS_STANDARD", "S3_PUSH_STANDARD")),
    ("PH1_S4_STANDARD", "S4", overdue_blocks(31, 90, "S4", "S4_SMS_STANDARD", "S4_PUSH_STANDARD")),
]


def sql_str(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    out = []
    w = out.append

    w("-- =====================================================================")
    w("-- Phase 1 生产触达配置（自动生成，请勿手改）")
    w("-- 生成器：scripts/config/gen-phase1-config-sql.py")
    w("-- 来源：渠道模板清单与配置 §4.1/§5.1/§7 · 渠道编排规格 §7.4~§7.9")
    w("-- 依赖：db/schema.sql + db/schema-admin.sql")
    w("-- 用法：mysql -h<host> -P<port> -u<user> -p ai_collection_db < db/seed-phase1-config.sql")
    w("-- 幂等：按 script_slot / template_code 先删后建")
    w("-- =====================================================================")
    w("")
    w(f"SET @TENANT = {sql_str(TENANT)};")
    w("SET @CFG_VER = 2;")
    w("SET @OPERATOR = 'seed-phase1-config';")
    w("")
    w("START TRANSACTION;")
    w("")
    w("-- 旧的 delayMin 模板（SEED_%）是 L4 联调节奏：所有步骤相隔 1 分钟，")
    w("-- 在生产会让同一客户 3 分钟内连收 3 条。必须整体替换为 dayBlocks 绝对槽位。")
    w("DELETE FROM t_strategy_rule    WHERE tenant_id = @TENANT AND rule_name LIKE 'SEED\\_%';")
    w("DELETE FROM t_contact_plan_template WHERE tenant_id = @TENANT AND template_code LIKE 'SEED\\_%';")
    w("DELETE FROM t_contact_plan_template WHERE tenant_id = @TENANT AND template_code LIKE 'PH1\\_%';")
    w("")

    all_slots = sorted({s[1] for s in SMS_SCRIPTS} | {p[1] for p in PUSH_SCRIPTS} | {e[1] for e in EMAIL_SCRIPTS})
    w("DELETE FROM t_script_template WHERE tenant_id = @TENANT AND script_slot IN (")
    w("    " + ", ".join(sql_str(s) for s in all_slots))
    w(");")
    w("")

    w("-- 文案槽位：SMS 10 + Push 7 + Email 5。缺任意一条，Resolver 会跳过该步骤不发送，")
    w("-- 且 PilotReadinessValidator 拒绝启动。")
    w("INSERT INTO t_script_template")
    w("    (id, tenant_id, script_slot, channel, locale, content_json, external_template_id, status, config_version, version, created_by, updated_by)")
    w("VALUES")
    rows = []
    for tid, name, body in SMS_SCRIPTS:
        rows.append(
            f"    ({tid}, @TENANT, {sql_str(name)}, 'SMS', 'en',\n"
            f"     JSON_OBJECT('body', {sql_str(body)}),\n"
            f"     NULL, 'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR)"
        )
    for tid, name, title, body in PUSH_SCRIPTS:
        rows.append(
            f"    ({tid}, @TENANT, {sql_str(name)}, 'PUSH', 'en',\n"
            f"     JSON_OBJECT('title', {sql_str(title)}, 'body', {sql_str(body)}),\n"
            f"     NULL, 'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR)"
        )
    for tid, name, subject, external in EMAIL_SCRIPTS:
        rows.append(
            f"    ({tid}, @TENANT, {sql_str(name)}, 'EMAIL', 'en',\n"
            f"     JSON_OBJECT('subject', {sql_str(subject)}),\n"
            f"     {sql_str(external)}, 'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR)"
        )
    w(",\n".join(rows) + ";")
    w("")

    w("-- 计划模板：dayBlocks 绝对槽位（dpdDay 相对 due_date，time 为 PHT）。")
    w("-- 晚进案由 PhtSlotScheduleCalculator 跳过已过槽位，不追溯补发。")
    w("INSERT INTO t_contact_plan_template")
    w("    (tenant_id, template_code, stage, product_code, tone, plan_json, status, config_version, version, created_by, updated_by)")
    w("VALUES")
    plan_rows = []
    for code, stage, blocks in PLANS:
        payload = json.dumps({"dayBlocks": blocks}, separators=(",", ":"), ensure_ascii=False)
        plan_rows.append(
            f"    (@TENANT, {sql_str(code)}, {sql_str(stage)}, NULL, 'STANDARD',\n"
            f"     CAST({sql_str(payload)} AS JSON),\n"
            f"     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR)"
        )
    w(",\n".join(plan_rows) + ";")
    w("")

    w("-- Stage → plan 模板路由（DPD 区间对齐编排规格 §4.1；D+91 起停催，不建计划）")
    w("INSERT INTO t_strategy_rule")
    w("    (tenant_id, rule_name, priority, match_dpd_min, match_dpd_max, output_template_id, output_tone, status, config_version, version, created_by, updated_by)")
    w("SELECT @TENANT, CONCAT('PH1_', stage, '_RULE'), 100,")
    w("       CASE stage WHEN 'S0' THEN -3 WHEN 'S1' THEN 1 WHEN 'S2' THEN 4 WHEN 'S3' THEN 16 WHEN 'S4' THEN 31 END,")
    w("       CASE stage WHEN 'S0' THEN 0 WHEN 'S1' THEN 3 WHEN 'S2' THEN 15 WHEN 'S3' THEN 30 WHEN 'S4' THEN 90 END,")
    w("       id, 'STANDARD', 'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR")
    w("FROM t_contact_plan_template")
    w("WHERE tenant_id = @TENANT AND template_code LIKE 'PH1\\_%';")
    w("")

    w("UPDATE t_config_version_seq SET current_version = GREATEST(current_version, @CFG_VER), updated_at = NOW() WHERE id = 1;")
    w("")
    w("INSERT INTO t_config_change_log")
    w("    (tenant_id, config_type, config_key, from_version, to_version, diff_summary, operator, reason, created_at)")
    w("VALUES")
    w("    (@TENANT, 'plan_template', 'phase1-dayblocks', 1, @CFG_VER,")
    w("     JSON_OBJECT('summary', 'Replace delayMin integration cadence with PHT dayBlocks; complete all 17 SMS/Push slots'),")
    w("     @OPERATOR, 'seed-phase1-config.sql', NOW());")
    w("")
    w("COMMIT;")
    w("")

    expected_sms = len(SMS_SCRIPTS)
    expected_push = len(PUSH_SCRIPTS)
    expected_email = len(EMAIL_SCRIPTS)
    w("-- 自检：三个 count 必须分别等于 10 / 7 / 5，plan_templates 等于 5")
    w("SELECT 'SEED_PHASE1_CONFIG_OK' AS result,")
    w(f"       (SELECT COUNT(*) FROM t_script_template WHERE tenant_id = @TENANT AND channel = 'SMS'   AND status = 'ACTIVE') AS sms_slots,   -- expect {expected_sms}")
    w(f"       (SELECT COUNT(*) FROM t_script_template WHERE tenant_id = @TENANT AND channel = 'PUSH'  AND status = 'ACTIVE') AS push_slots,  -- expect {expected_push}")
    w(f"       (SELECT COUNT(*) FROM t_script_template WHERE tenant_id = @TENANT AND channel = 'EMAIL' AND status = 'ACTIVE') AS email_slots, -- expect {expected_email}")
    w("       (SELECT COUNT(*) FROM t_contact_plan_template WHERE tenant_id = @TENANT AND status = 'ACTIVE') AS plan_templates, -- expect 5")
    w("       (SELECT current_version FROM t_config_version_seq WHERE id = 1) AS config_version;")

    print("\n".join(out))


if __name__ == "__main__":
    main()
