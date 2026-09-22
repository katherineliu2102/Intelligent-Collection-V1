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
    const err = new Error(msg) as Error & {
      code?: string;
      errors?: Array<{ field?: string; code?: string; message?: string }>;
    };
    err.code = body?.code;
    err.errors = body?.errors;
    throw err;
  }
  return body;
}

export const api = {
  login(username: string, password: string) {
    return request("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
  },
  me() {
    return request("/admin/me");
  },
  logout() {
    return request("/auth/logout", { method: "POST" });
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
  getCase(caseId: string | number) {
    return request(`/cases/${caseId}`);
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
  listAccounts() {
    return request("/admin/accounts");
  },
  createAccount(payload: { username: string; password: string; role: string }) {
    return request("/admin/accounts", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  patchAccount(
    id: number,
    payload: { enabled?: boolean; role?: string; password?: string }
  ) {
    return request(`/admin/accounts/${id}`, {
      method: "PATCH",
      body: JSON.stringify(payload)
    });
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
      body: JSON.stringify({ targetVersion, reason, confirm: true })
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
  validateScriptTemplate(payload: {
    scriptSlot: string;
    channel: string;
    locale?: string;
    body?: string;
    title?: string;
    version: number;
  }) {
    return request("/config/script-templates/validate", {
      method: "POST",
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
  dashboardOutreachRealtime(days = 30) {
    return request(`/dashboard/outreach/realtime?days=${days}`);
  },
  dashboardToday() {
    return request("/dashboard/today");
  },
  dashboardDailyByChannel(days = 7) {
    return request(`/dashboard/daily-by-channel?days=${days}`);
  },
  dashboardPortfolio() {
    return request("/dashboard/portfolio");
  },
  dashboardAicallRealtime(days = 7) {
    return request(`/dashboard/aicall/realtime?days=${days}`);
  },
  dashboardAging() {
    return request("/dashboard/aging");
  },
  dashboardMatrix(days = 7) {
    return request(`/dashboard/matrix?days=${days}`);
  },
  dashboardAicallDetail(
    page = 1,
    pageSize = 25,
    days = 7,
    includeSynthetic = false,
    filters?: {
      resultLabel?: string;
      stage?: string;
      waveKey?: string;
      connectKind?: string;
      party?: string;
      effectiveConversation?: string;
      rightParty?: string;
    }
  ) {
    const q = new URLSearchParams({
      page: String(page),
      pageSize: String(pageSize),
      days: String(days),
      includeSynthetic: String(includeSynthetic)
    });
    if (filters?.resultLabel != null) q.set("resultLabel", filters.resultLabel);
    if (filters?.stage != null) q.set("stage", filters.stage);
    if (filters?.waveKey != null) q.set("waveKey", filters.waveKey);
    if (filters?.connectKind != null) q.set("connectKind", filters.connectKind);
    if (filters?.party != null) q.set("party", filters.party);
    if (filters?.effectiveConversation != null) {
      q.set("effectiveConversation", filters.effectiveConversation);
    }
    if (filters?.rightParty != null) q.set("rightParty", filters.rightParty);
    return request(`/dashboard/aicall/detail?${q.toString()}`);
  },
  dashboardRisk(days = 7) {
    return request(`/dashboard/risk?days=${days}`);
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
