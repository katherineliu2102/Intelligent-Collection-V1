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
  }
};
