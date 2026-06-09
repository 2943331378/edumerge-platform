"use client";

import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from "react";

interface UserInfo {
  id: number;
  username: string;
  email: string;
  displayName: string;
}

interface AuthState {
  user: UserInfo | null;
  token: string | null;
  loading: boolean;
  login: (username: string, password: string, rememberMe?: boolean) => Promise<void>;
  register: (username: string, email: string, password: string, displayName: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

const TOKEN_KEY = "edumerge_token";
const REFRESH_KEY = "edumerge_refresh_token";
const USER_KEY = "edumerge_user";
const BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";

function setCookie(name: string, value: string, days = 7) {
  const maxAge = days * 24 * 60 * 60;
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/; max-age=${maxAge}; SameSite=Lax${secure}`;
}

function deleteCookie(name: string) {
  document.cookie = `${name}=; path=/; max-age=0`;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Check localStorage first (rememberMe=true), then sessionStorage (rememberMe=false)
    const savedToken = localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
    const savedUser = localStorage.getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
    if (savedToken && savedUser) {
      try {
        const parsed: UserInfo = JSON.parse(savedUser);
        // Check JWT expiry by decoding the payload
        const payload = JSON.parse(atob(savedToken.split(".")[1]));
        if (payload.exp && payload.exp * 1000 < Date.now()) {
          throw new Error("Token expired");
        }
        setToken(savedToken);
        setUser(parsed);
      } catch {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        localStorage.removeItem(REFRESH_KEY);
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(USER_KEY);
        sessionStorage.removeItem(REFRESH_KEY);
        deleteCookie(TOKEN_KEY);
        import("sonner").then(({ toast }) => toast.error("登录已过期，请重新登录")).catch(() => {});
      }
    }
    setLoading(false);
  }, []);

  const saveAuth = (t: string, u: UserInfo, rt?: string, rememberMe = true) => {
    const storage = rememberMe ? localStorage : sessionStorage;
    // Clear both storages first to avoid stale tokens
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(REFRESH_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    storage.setItem(TOKEN_KEY, t);
    storage.setItem(USER_KEY, JSON.stringify(u));
    if (rt) storage.setItem(REFRESH_KEY, rt);
    setCookie(TOKEN_KEY, t, rememberMe ? 7 : 1);
    setToken(t);
    setUser(u);
  };

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    sessionStorage.removeItem(USER_KEY);
    deleteCookie(TOKEN_KEY);
    setToken(null);
    setUser(null);
  }, []);

  const login = useCallback(async (username: string, password: string, rememberMe = true) => {
    const res = await fetch(`${BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    if (res.status === 401) {
      logout();
      throw new Error("用户名或密码错误");
    }
    if (!res.ok) {
      let msg = `请求失败 (${res.status})`;
      try { msg = (await res.json()).message ?? msg; } catch { /* non-JSON response */ }
      throw new Error(msg);
    }
    let json;
    try { json = await res.json(); } catch { throw new Error("服务器返回了无效的数据格式"); }
    if (json.code !== 0) throw new Error(json.message ?? "登录失败");
    saveAuth(json.data.token, json.data.user, json.data.refreshToken, rememberMe);
  }, [logout]);

  const register = useCallback(async (username: string, email: string, password: string, displayName: string) => {
    const res = await fetch(`${BASE}/auth/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, email, password, displayName }),
    });
    if (res.status === 401) {
      logout();
      throw new Error("认证已过期，请重新登录");
    }
    if (!res.ok) {
      let msg = `请求失败 (${res.status})`;
      try { msg = (await res.json()).message ?? msg; } catch { /* non-JSON response */ }
      throw new Error(msg);
    }
    let json;
    try { json = await res.json(); } catch { throw new Error("服务器返回了无效的数据格式"); }
    if (json.code !== 0) throw new Error(json.message ?? "注册失败");
    saveAuth(json.data.token, json.data.user, json.data.refreshToken);
  }, [logout]);

  return (
    <AuthContext.Provider value={{ user, token, loading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

/** 获取当前存储的 token（在 api.ts fetch 拦截中使用） */
export function getStoredToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("edumerge_token") ?? sessionStorage.getItem("edumerge_token");
}
