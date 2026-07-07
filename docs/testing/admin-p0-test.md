# MOCASA Collection Admin P0 Test Doc

> Version: v1.0
> Mode: single document (cases + run guide + latest results)
> Script: `scripts/test/admin-p0-selftest.ps1`

## 1. Scope

- Auth: `/auth/login`, `/admin/me`
- Case query: `/cases/search`
- Compliance: `/compliance/freeze|unfreeze|escalate`
- Ops queue: `/ops/exceptions`, `/ops/exceptions/{id}/ack|resolve`
- Audit logs: `/admin/audit-logs`

## 2. Test Cases

| Case ID | Scenario | Expected |
|---|---|---|
| ADM-P0-001 | Unauth guard | HTTP 401 |
| ADM-P0-002 | Login | success=true |
| ADM-P0-003 | Session me | current user returned |
| ADM-P0-004 | Case search | paged data |
| ADM-P0-005 | Freeze | status=FROZEN |
| ADM-P0-006 | Unfreeze | status=RELEASED |
| ADM-P0-007 | Escalate | status=ESCALATED |
| ADM-P0-008 | Ops list | paged data |
| ADM-P0-009 | Ops ACK | status updated |
| ADM-P0-010 | Ops resolve | status updated |
| ADM-P0-011 | Audit logs | paged data |

## 3. Run Guide

```powershell
cd Intelligent-Collection-V1
powershell -ExecutionPolicy Bypass -File scripts/test/admin-p0-selftest.ps1
```

Prerequisites:

- `collection-admin` is running (default `http://localhost:8888`)
- Database has `db/schema.sql` and `db/schema-admin.sql`

## 4. Latest Run Result

> Run At: 2026-07-07 16:41:01
> Base URL: http://localhost:8888
> CaseId: 92002
> Overall: **PASS** (11/11 PASS)

| Case ID | Scenario | Expected | Actual | Result |
|---|---|---|---|---|
| ADM-P0-001 | Unauth guard | HTTP 401 | HTTP 401 | PASS |
| ADM-P0-002 | Login | success=true | ok | PASS |
| ADM-P0-003 | Session me | user returned | admin | PASS |
| ADM-P0-004 | Case search | paged data | items=20 | PASS |
| ADM-P0-005 | Freeze | FROZEN | FROZEN | PASS |
| ADM-P0-006 | Unfreeze | RELEASED | RELEASED | PASS |
| ADM-P0-007 | Escalate | ESCALATED | ESCALATED | PASS |
| ADM-P0-008 | Ops list OPEN | paged data | items=1 | PASS |
| ADM-P0-009 | Ops ACK | ACK success | acked id=6 | PASS |
| ADM-P0-010 | Ops resolve | RESOLVED success | resolved id=6 | PASS |
| ADM-P0-011 | Audit logs | paged data | logs=18 | PASS |

## 5. Notes

- If ADM-P0-008~010 fails, check schema-admin.sql and /mock/admin/seed-exception.
- If auth cases fail, check session interceptor and /auth/login.
