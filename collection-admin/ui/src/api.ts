export const API_BASE = import.meta.env.VITE_API_BASE || "";

async function request(path: string, init?: RequestInit) {
  const resp = await fetch(`${API_BASE}${path}`, {
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers || {})
    },
    ...init
  });
  const isJson = (resp.headers.get("content-type") || "").includes("application/json");
  const body = isJson ? await resp.json() : undefined;
  if (!resp.ok) {
    const msg = body?.message || `${resp.status} ${resp.statusText}`;
    throw new Error(msg);
  }
  return body;
}

export const api = {
  login(username: string, role: string) {
    return request("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, role })
    });
  },
  me() {
    return request("/admin/me");
  },
  searchCases(params: Record<string, string | number | boolean>) {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && String(v).length > 0) {
        query.set(k, String(v));
      }
    });
    return request(`/cases/search?${query.toString()}`);
  },
  listOps(params: Record<string, string | number>) {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => query.set(k, String(v)));
    return request(`/ops/exceptions?${query.toString()}`);
  },
  ackOp(id: number) {
    return request(`/ops/exceptions/${id}/ack`, { method: "POST" });
  },
  resolveOp(id: number, action: "RETRY" | "IGNORE" | "MANUAL_FIXED", note: string) {
    return request(`/ops/exceptions/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ action, note })
    });
  },
  compliance(path: "freeze" | "unfreeze" | "escalate", payload: Record<string, unknown>) {
    return request(`/compliance/${path}`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  auditLogs() {
    return request("/admin/audit-logs?page=1&pageSize=20");
  },
  getEvaluationSettings() {
    return request("/config/evaluation-settings");
  },
  updateEvaluationSettings(payload: {
    holdoutRatio: number;
    version: number;
    reason?: string;
  }) {
    return request("/config/evaluation-settings", {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  },
  listConfigVersions(page = 1, pageSize = 20) {
    return request(`/config/versions?page=${page}&pageSize=${pageSize}`);
  },
  rollbackConfig(targetVersion: number, reason: string) {
    return request("/config/rollback", {
      method: "POST",
      body: JSON.stringify({ targetVersion, reason })
    });
  },
  catalogOverview() {
    return request("/catalog/overview");
  },
  catalogTemplate(slot: string) {
    return request(`/catalog/template/${encodeURIComponent(slot)}`);
  },
  caseOverview(caseId: string | number, timelineLimit = 50) {
    return request(`/plans/overview/by-case/${caseId}?timelineLimit=${timelineLimit}`);
  },
  planHistoryByCase(caseId: string | number, limit = 10) {
    return request(`/plans/by-case/${caseId}/history?limit=${limit}`);
  },
  planSteps(planId: string | number) {
    return request(`/plans/${planId}/steps`);
  },
  timelineByUser(userId: string | number, limit = 50) {
    return request(`/plans/timeline/${userId}?limit=${limit}`);
  },
  listScriptTemplates(channel?: string) {
    const q = channel ? `?channel=${encodeURIComponent(channel)}` : "";
    return request(`/config/script-templates${q}`);
  },
  updateScriptTemplate(payload: {
    scriptSlot: string;
    channel: string;
    locale?: string;
    body?: string;
    title?: string;
    externalTemplateId?: string;
    version: number;
    reason?: string;
  }) {
    return request("/config/script-templates", {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  },
  deactivateScriptTemplate(scriptSlot: string, channel: string, locale = "en") {
    const q = new URLSearchParams({ scriptSlot, channel, locale }).toString();
    return request(`/config/script-templates?${q}`, { method: "DELETE" });
  },
  listPlanTemplates() {
    return request("/config/plan-templates");
  },
  dashboardOutreachRealtime(days = 7) {
    return request(`/dashboard/outreach/realtime?days=${days}`);
  },
  deactivatePlanTemplate(templateCode: string) {
    return request(`/config/plan-templates/${encodeURIComponent(templateCode)}`, {
      method: "DELETE"
    });
  },
  updatePlanTemplate(payload: {
    templateCode: string;
    stage: string;
    tone?: string;
    productCode?: string;
    steps: { channel: string; delayMin: number; observeMin: number; templateId: number }[];
    version: number;
    reason?: string;
  }) {
    return request("/config/plan-templates", {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  }
};
