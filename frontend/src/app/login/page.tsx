"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { toast } from "sonner";
import { LogIn, Mail, Lock, Eye, EyeOff, ArrowRight, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";

export default function LoginPage() {
  const router = useRouter();
  const auth = useAuth();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      toast.error("请填写用户名和密码");
      return;
    }
    setLoading(true);
    try {
      await auth.login(username.trim(), password);
      toast.success("欢迎回来");
      router.push("/");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "登录失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-slate-950 p-4">
      {/* Background atmosphere */}
      <div className="absolute inset-0">
        <div className="absolute -left-40 -top-40 h-[500px] w-[500px] rounded-full bg-amber-500/10 blur-[120px]" />
        <div className="absolute -bottom-40 -right-40 h-[400px] w-[400px] rounded-full bg-blue-500/8 blur-[100px]" />
        <div className="absolute left-1/2 top-1/2 h-[300px] w-[300px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-amber-400/5 blur-[80px]" />
        {/* Subtle grid */}
        <div
          className="absolute inset-0 opacity-[0.03]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)",
            backgroundSize: "60px 60px",
          }}
        />
      </div>

      {/* Card */}
      <div className="relative w-full max-w-md animate-in fade-in zoom-in-95 duration-500">
        {/* Logo + brand */}
        <div className="mb-8 flex flex-col items-center gap-3">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-white/5 ring-1 ring-white/10 backdrop-blur">
            <Image
              src="/logo_converted.svg"
              alt="EduMerge"
              width={32}
              height={32}
              priority
              className="h-8 w-8"
            />
          </div>
          <div className="text-center">
            <h1 className="text-xl font-semibold tracking-tight text-white">
              EduMerge 智融
            </h1>
            <p className="mt-1 text-[13px] leading-relaxed text-slate-400">
              AI 驱动的零幻觉学习伴侣
            </p>
          </div>
        </div>

        {/* Form card */}
        <div className="rounded-2xl border border-white/10 bg-white/5 p-6 backdrop-blur-xl shadow-2xl shadow-black/20">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                用户名
              </label>
              <div className="relative">
                <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="输入用户名或邮箱"
                  className={cn(
                    "h-11 w-full rounded-xl border bg-transparent pl-10 pr-4 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-amber-500/50 focus:outline-none focus:ring-2 focus:ring-amber-500/20",
                  )}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                密码
              </label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="输入密码"
                  className={cn(
                    "h-11 w-full rounded-xl border bg-transparent pl-10 pr-10 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-amber-500/50 focus:outline-none focus:ring-2 focus:ring-amber-500/20",
                  )}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300 transition-colors"
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                  className="h-3.5 w-3.5 rounded border-white/20 bg-transparent text-amber-500 focus:ring-amber-500/30"
                />
                <span className="text-[11px] text-slate-400">记住登录</span>
              </label>
            </div>

            <button
              type="submit"
              disabled={loading}
              className={cn(
                "flex h-11 w-full items-center justify-center gap-2 rounded-xl text-sm font-medium transition-all duration-200",
                "bg-amber-500 text-slate-950 hover:bg-amber-400",
                "shadow-lg shadow-amber-500/20 hover:shadow-amber-500/30",
                "disabled:opacity-50 disabled:cursor-not-allowed",
              )}
            >
              {loading ? (
                <Sparkles className="h-4 w-4 animate-spin" />
              ) : (
                <>
                  <LogIn className="h-4 w-4" />
                  登录
                  <ArrowRight className="h-4 w-4" />
                </>
              )}
            </button>
          </form>
        </div>

        <p className="mt-4 text-center text-[12px] text-slate-500">
          还没有账号？{" "}
          <Link href="/register" className="text-amber-400 hover:text-amber-300 transition-colors font-medium">
            立即注册
          </Link>
        </p>
      </div>
    </div>
  );
}
