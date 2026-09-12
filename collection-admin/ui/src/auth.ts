import { createContext, useContext } from "react";

export type AdminRole = "VIEWER" | "OPERATOR" | "SYSTEM_ADMIN";

export function parseAdminRole(raw: unknown): AdminRole {
  if (raw === "OPERATOR" || raw === "SYSTEM_ADMIN" || raw === "VIEWER") {
    return raw;
  }
  return "VIEWER";
}

export function canWrite(role: AdminRole): boolean {
  return role === "OPERATOR" || role === "SYSTEM_ADMIN";
}

export function isSystemAdmin(role: AdminRole): boolean {
  return role === "SYSTEM_ADMIN";
}

export const RoleContext = createContext<AdminRole>("VIEWER");

export function useAdminRole(): AdminRole {
  return useContext(RoleContext);
}

export const READ_ONLY_HINT = "当前角色只读";
