-- T3o 收尾：终止 T5-R5 / F10 演练刻意构造的两条毒丸，使 DLQ 无未决项。
--
-- 为何不走 /ops/dlq/redrive：该端点只对 failure_reason != 'MAX_DELIVERY_EXCEEDED' 的行判
-- NON_RECOVERABLE 并直接终止；这两条恰好被分类为 MAX_DELIVERY_EXCEEDED（"重投次数用尽"），
-- 于是只能走重放路径。而它们的 payload 是 planId="not-a-number"，重放必然再失败，
-- 要连打三轮把 MAX_REDRIVE_COUNT 耗尽才会自动 REDRIVE_LIMIT_EXCEEDED 终止 ——
-- 代价是往 events stream 里再注六条注定失败的事件。故此处按 API 的审计格式直接落终态。
--
-- redrive_reason 沿用 DlqController.terminationNote 的三段式（分类|by=操作人|reason=理由），
-- 终态行才能独立回答「何时终止 / 哪类不可恢复 / 谁决定 / 依据什么」四问。
--
-- 缺口已记台账：MAX_DELIVERY_EXCEEDED 只描述重投次数，不描述失败性质。真正不可恢复的毒丸
-- 若每次都落进通用 catch（而非被判 DESERIALIZATION_FAILURE / MISSING_EVENT_FIELD），
-- 就会带着"可恢复"标签进 DLQ，运维缺一个显式终止入口。

UPDATE t_event_dlq
SET status = 'TERMINATED',
    terminated_at = NOW(),
    redrive_reason = CONCAT(
        'NON_RECOVERABLE:POISON_PAYLOAD|by=ops|reason=',
        'T3o 收尾：T5-R5/F10 演练刻意构造的毒丸（planId 非数字），重放必然再失败，按不可恢复终止'
    )
WHERE event_id IN ('t5r-poison-001', 't5r-poison-002')
  AND status = 'PENDING';

-- 校验：DLQ 不应再有 PENDING。
SELECT status, COUNT(*) AS cnt FROM t_event_dlq GROUP BY status;
