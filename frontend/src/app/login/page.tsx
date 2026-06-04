"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { toast } from "sonner";
import {
  LogIn, Lock, Eye, EyeOff, ArrowRight, Sparkles, Play, User as UserIcon,
  FileText, NotebookText, GitFork, Layers, HelpCircle, BookOpen, ChevronRight,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";

/* ─── 打字效果 ─── */
function TypeWriter({ texts }: { texts: string[] }) {
  const [idx, setIdx] = useState(0);
  const [charIdx, setCharIdx] = useState(0);
  const [del, setDel] = useState(false);

  useEffect(() => {
    const cur = texts[idx];
    if (!del && charIdx < cur.length) { const t = setTimeout(() => setCharIdx(v => v + 1), 60); return () => clearTimeout(t); }
    if (!del && charIdx === cur.length) { const t = setTimeout(() => setDel(true), 2500); return () => clearTimeout(t); }
    if (del && charIdx > 0) { const t = setTimeout(() => setCharIdx(v => v - 1), 30); return () => clearTimeout(t); }
    if (del && charIdx === 0) { setDel(false); setIdx(v => (v + 1) % texts.length); }
  }, [charIdx, del, idx, texts]);

  return (
    <span>
      {texts[idx].slice(0, charIdx)}
      <span className="animate-pulse text-primary">|</span>
    </span>
  );
}

/* ─── 6 步流程图标 ─── */
const STEPS = [
  { icon: FileText, label: "大纲", c: "text-blue-400", bg: "bg-blue-500/10", ring: "ring-blue-500/20" },
  { icon: NotebookText, label: "笔记", c: "text-violet-400", bg: "bg-violet-500/10", ring: "ring-violet-500/20" },
  { icon: GitFork, label: "导图", c: "text-emerald-400", bg: "bg-emerald-500/10", ring: "ring-emerald-500/20" },
  { icon: Layers, label: "闪卡", c: "text-amber-400", bg: "bg-amber-500/10", ring: "ring-amber-500/20" },
  { icon: HelpCircle, label: "测验", c: "text-rose-400", bg: "bg-rose-500/10", ring: "ring-rose-500/20" },
  { icon: BookOpen, label: "日志", c: "text-cyan-400", bg: "bg-cyan-500/10", ring: "ring-cyan-500/20" },
];

export default function LoginPage() {
  const router = useRouter();
  const auth = useAuth();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [focused, setFocused] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) { toast.error("请填写用户名和密码"); return; }
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
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background p-4">
      {/* ════════ Unified Background ════════ */}
      <div className="absolute inset-0">
        {/* Gradient orbs — spread across entire viewport */}
        <div className="absolute left-[-10%] top-[-15%] h-[60vh] w-[60vh] rounded-full bg-primary/[0.07] blur-[120px] animate-[drift1_20s_ease-in-out_infinite]" />
        <div className="absolute right-[-5%] bottom-[-10%] h-[50vh] w-[50vh] rounded-full bg-amber-500/[0.05] blur-[100px] animate-[drift2_25s_ease-in-out_infinite]" />
        <div className="absolute left-[40%] top-[60%] h-[35vh] w-[35vh] rounded-full bg-rose-500/[0.03] blur-[80px] animate-[drift3_18s_ease-in-out_infinite]" />
        {/* Dot grid — spans full width */}
        <div
          className="absolute inset-0 opacity-[0.035] dark:opacity-[0.04]"
          style={{
            backgroundImage: "radial-gradient(circle, currentColor 0.8px, transparent 0.8px)",
            backgroundSize: "28px 28px",
          }}
        />
      </div>

      {/* ════════ Main Content — Two-column within one canvas ════════ */}
      <div className="relative z-10 flex w-full max-w-5xl items-center gap-8 lg:gap-12">
        {/* ─── Left: Brand Story ─── */}
        <div className="hidden md:flex md:w-[340px] lg:w-[400px] shrink-0 flex-col gap-6 animate-in fade-in slide-in-from-left-6 duration-700">
          {/* Logo */}
          <BrandMark variant="hero" showSubtitle />

          {/* Headline */}
          <div>
            <h2 className="text-2xl lg:text-[28px] font-bold tracking-tight leading-[1.3]">
              上传一份材料
              <br />
              <span className="text-primary">AI 帮你 6 步吃透它</span>
            </h2>
            <p className="mt-3 text-sm text-muted-foreground leading-relaxed">
              <TypeWriter texts={[
                "自动提取大纲，生成结构化笔记……",
                "思维导图可视化你的知识脉络……",
                "闪卡 + 测验，科学巩固记忆……",
                "AI 对话助手，随时答疑解惑……",
              ]} />
            </p>
          </div>

          {/* 6-step flow — inline pill row */}
          <div className="flex flex-wrap gap-2">
            {STEPS.map((s, i) => (
              <div
                key={s.label}
                className={cn(
                  "flex items-center gap-1.5 rounded-full border px-2.5 py-1.5",
                  "bg-background/60 backdrop-blur-sm",
                  s.ring, "border-current/10",
                  "hover:scale-105 transition-all duration-300 cursor-default",
                )}
                style={{ animationDelay: `${i * 80}ms` }}
              >
                <s.icon className={cn("h-3.5 w-3.5", s.c)} />
                <span className="text-[11px] font-medium text-foreground/80">{s.label}</span>
              </div>
            ))}
          </div>

          {/* Social proof */}
          <div className="flex items-center gap-3 pt-1">
            <div className="flex -space-x-2">
              {["bg-primary", "bg-amber-600", "bg-rose-500", "bg-stone-500"].map((c, i) => (
                <div key={i} className={cn("h-7 w-7 rounded-full border-2 border-background flex items-center justify-center text-[10px] font-bold text-white", c)}>
                  {["L", "Y", "M", "K"][i]}
                </div>
              ))}
            </div>
            <p className="text-[11px] text-muted-foreground">
              <span className="text-foreground font-medium">128+</span> 位学习者在使用
            </p>
          </div>
        </div>

        {/* ─── Right: Login Form ─── */}
        <div className="flex-1 max-w-[420px] ml-auto animate-in fade-in slide-in-from-right-6 duration-700">
          {/* Mobile logo + headline */}
          <div className="mb-7 flex flex-col items-center gap-3 md:hidden">
            <BrandMark variant="hero" showSubtitle />
            <p className="text-xs text-muted-foreground">AI 驱动的零幻觉学习伴侣</p>
          </div>

          {/* Desktop header */}
          <div className="hidden md:block mb-6">
            <h1 className="text-xl font-bold tracking-tight">欢迎回来</h1>
            <p className="mt-1 text-sm text-muted-foreground">登录你的 EduMerge 账号</p>
          </div>

          {/* Form card — glass effect */}
          <div className="rounded-2xl border border-border/50 bg-card/70 backdrop-blur-xl p-6 shadow-2xl shadow-black/[0.04] dark:shadow-black/20">
            <form onSubmit={handleSubmit} className="space-y-4">
              {/* Username */}
              <div className="space-y-1.5">
                <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">用户名</label>
                <div className="relative">
                  <UserIcon className={cn(
                    "absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200",
                    focused === "username" ? "text-primary" : "text-muted-foreground/40",
                  )} />
                  <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    onFocus={() => setFocused("username")}
                    onBlur={() => setFocused(null)}
                    placeholder="输入用户名或邮箱"
                    className={cn(
                      "h-11 w-full rounded-xl border bg-background/60 pl-10 pr-4 text-sm",
                      "placeholder:text-muted-foreground/35 transition-all duration-200",
                      "border-border/50 hover:border-border",
                      "focus:border-primary/40 focus:outline-none focus:ring-[3px] focus:ring-primary/[0.08]",
                    )}
                    autoFocus
                  />
                </div>
              </div>

              {/* Password */}
              <div className="space-y-1.5">
                <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">密码</label>
                <div className="relative">
                  <Lock className={cn(
                    "absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200",
                    focused === "password" ? "text-primary" : "text-muted-foreground/40",
                  )} />
                  <input
                    type={showPassword ? "text" : "password"}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    onFocus={() => setFocused("password")}
                    onBlur={() => setFocused(null)}
                    placeholder="输入密码"
                    className={cn(
                      "h-11 w-full rounded-xl border bg-background/60 pl-10 pr-10 text-sm",
                      "placeholder:text-muted-foreground/35 transition-all duration-200",
                      "border-border/50 hover:border-border",
                      "focus:border-primary/40 focus:outline-none focus:ring-[3px] focus:ring-primary/[0.08]",
                    )}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground/30 hover:text-foreground transition-colors"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>

              {/* Remember */}
              <label className="flex items-center gap-2 cursor-pointer select-none group">
                <input type="checkbox" className="h-3.5 w-3.5 rounded border-border text-primary focus:ring-primary/20" />
                <span className="text-[11px] text-muted-foreground group-hover:text-foreground transition-colors">记住登录</span>
              </label>

              {/* Submit */}
              <button
                type="submit"
                disabled={loading}
                className={cn(
                  "relative flex h-11 w-full items-center justify-center gap-2 rounded-xl text-sm font-semibold transition-all duration-200",
                  "bg-primary text-primary-foreground",
                  "shadow-lg shadow-primary/20 hover:shadow-xl hover:shadow-primary/30",
                  "hover:brightness-110 active:scale-[0.98]",
                  "disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100",
                  "overflow-hidden",
                )}
              >
                <div className="absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/15 to-transparent animate-[shimmer_2.5s_ease-in-out_infinite]" />
                {loading ? <Sparkles className="h-4 w-4 animate-spin" /> : (
                  <><LogIn className="h-4 w-4" />登录<ArrowRight className="h-4 w-4" /></>
                )}
              </button>
            </form>
          </div>

          {/* Footer */}
          <div className="mt-5 flex flex-col items-center gap-3">
            <p className="text-[13px] text-muted-foreground">
              还没有账号？{" "}
              <Link href="/register" className="text-primary hover:underline font-semibold underline-offset-2">立即注册</Link>
            </p>
            <Link
              href="/demo"
              className="group inline-flex items-center gap-1.5 text-[12px] text-muted-foreground/60 hover:text-primary transition-colors"
            >
              <Play className="h-3 w-3" />
              先体验演示
              <ChevronRight className="h-3 w-3 opacity-0 -translate-x-1 group-hover:opacity-100 group-hover:translate-x-0 transition-all" />
            </Link>
          </div>
        </div>
      </div>

      {/* Animations */}
      <style jsx global>{`
        @keyframes shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
        @keyframes drift1 { 0%, 100% { transform: translate(0, 0); } 33% { transform: translate(30px, -20px); } 66% { transform: translate(-15px, 15px); } }
        @keyframes drift2 { 0%, 100% { transform: translate(0, 0); } 33% { transform: translate(-25px, 15px); } 66% { transform: translate(20px, -25px); } }
        @keyframes drift3 { 0%, 100% { transform: translate(0, 0); } 50% { transform: translate(15px, -30px); } }
      `}</style>
    </div>
  );
}
