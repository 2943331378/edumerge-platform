"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { toast } from "sonner";
import {
  UserPlus, Mail, Lock, Eye, EyeOff, ArrowRight, Sparkles, User, AtSign,
} from "lucide-react";
import { cn } from "@/lib/utils";

export default function RegisterPage() {
  const router = useRouter();
  const auth = useAuth();

  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!username.trim() || !email.trim() || !displayName.trim() || !password) {
      toast.error("请填写所有必填字段");
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      toast.error("邮箱格式不正确");
      return;
    }
    if (password.length < 6) {
      toast.error("密码至少需要 6 个字符");
      return;
    }
    if (password !== confirmPassword) {
      toast.error("两次输入的密码不一致");
      return;
    }

    setLoading(true);
    try {
      await auth.register(username.trim(), email.trim(), password, displayName.trim());
      toast.success("注册成功，欢迎加入 EduMerge");
      router.push("/");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "注册失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-slate-950 p-4">
      {/* Background atmosphere */}
      <div className="absolute inset-0">
        <div className="absolute -right-40 -top-40 h-[500px] w-[500px] rounded-full bg-emerald-500/10 blur-[120px]" />
        <div className="absolute -bottom-40 -left-40 h-[400px] w-[400px] rounded-full bg-amber-500/8 blur-[100px]" />
        <div className="absolute left-1/2 top-1/3 h-[300px] w-[300px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-teal-400/5 blur-[80px]" />
        <div
          className="absolute inset-0 opacity-[0.03]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)",
            backgroundSize: "60px 60px",
          }}
        />
      </div>

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
              创建账号
            </h1>
            <p className="mt-1 text-[13px] leading-relaxed text-slate-400">
              开始你的零幻觉学习之旅
            </p>
          </div>
        </div>

        <div className="rounded-2xl border border-white/10 bg-white/5 p-6 backdrop-blur-xl shadow-2xl shadow-black/20">
          <form onSubmit={handleSubmit} className="space-y-3.5">
            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                用户名
              </label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="用于登录"
                  className={cn(
                    "h-10 w-full rounded-xl border bg-transparent pl-10 pr-4 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-emerald-500/50 focus:outline-none focus:ring-2 focus:ring-emerald-500/20",
                  )}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                邮箱
              </label>
              <div className="relative">
                <AtSign className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="your@email.com"
                  className={cn(
                    "h-10 w-full rounded-xl border bg-transparent pl-10 pr-4 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-emerald-500/50 focus:outline-none focus:ring-2 focus:ring-emerald-500/20",
                  )}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                显示名称
              </label>
              <div className="relative">
                <Sparkles className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type="text"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="你的昵称"
                  className={cn(
                    "h-10 w-full rounded-xl border bg-transparent pl-10 pr-4 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-emerald-500/50 focus:outline-none focus:ring-2 focus:ring-emerald-500/20",
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
                  placeholder="至少 6 个字符"
                  className={cn(
                    "h-10 w-full rounded-xl border bg-transparent pl-10 pr-10 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-emerald-500/50 focus:outline-none focus:ring-2 focus:ring-emerald-500/20",
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

            <div className="space-y-1.5">
              <label className="text-[11px] font-medium uppercase tracking-widest text-slate-400">
                确认密码
              </label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  type={showPassword ? "text" : "password"}
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="再次输入密码"
                  className={cn(
                    "h-10 w-full rounded-xl border bg-transparent pl-10 pr-4 text-sm text-white placeholder:text-slate-600",
                    "border-white/10 transition-all duration-200",
                    "focus:border-emerald-500/50 focus:outline-none focus:ring-2 focus:ring-emerald-500/20",
                    password && confirmPassword && password !== confirmPassword && "border-red-500/50",
                  )}
                />
              </div>
              {password && confirmPassword && password !== confirmPassword && (
                <p className="text-[11px] text-red-400">两次输入的密码不一致</p>
              )}
            </div>

            <button
              type="submit"
              disabled={loading}
              className={cn(
                "flex h-11 w-full items-center justify-center gap-2 rounded-xl text-sm font-medium transition-all duration-200 mt-1",
                "bg-emerald-500 text-slate-950 hover:bg-emerald-400",
                "shadow-lg shadow-emerald-500/20 hover:shadow-emerald-500/30",
                "disabled:opacity-50 disabled:cursor-not-allowed",
              )}
            >
              {loading ? (
                <Sparkles className="h-4 w-4 animate-spin" />
              ) : (
                <>
                  <UserPlus className="h-4 w-4" />
                  创建账号
                  <ArrowRight className="h-4 w-4" />
                </>
              )}
            </button>
          </form>
        </div>

        <p className="mt-4 text-center text-[12px] text-slate-500">
          已有账号？{" "}
          <Link href="/login" className="text-emerald-400 hover:text-emerald-300 transition-colors font-medium">
            立即登录
          </Link>
        </p>
      </div>
    </div>
  );
}
