"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { toast } from "sonner";
import {
  UserPlus, Lock, Eye, EyeOff, ArrowRight, Sparkles, User, AtSign, Play,
  ChevronRight, BrainCircuit, Zap, Shield,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";

/* ─── 价值点 ─── */
const VALUE_PROPS = [
  { icon: BrainCircuit, title: "AI 深度理解", desc: "不是简单摘要，而是多维度知识加工", color: "text-primary", bg: "bg-primary/10" },
  { icon: Zap, title: "6 步学习闭环", desc: "大纲→笔记→导图→闪卡→测验→日志", color: "text-amber-400", bg: "bg-amber-500/10" },
  { icon: Shield, title: "零幻觉 RAG", desc: "每个回答都可追溯到原文出处", color: "text-blue-400", bg: "bg-blue-500/10" },
];

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
  const [focused, setFocused] = useState<string | null>(null);
  const [step, setStep] = useState(1);

  const canProceed = username.trim() && email.trim() && displayName.trim();
  const passwordsMatch = password === confirmPassword;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (step === 1) {
      if (!canProceed) { toast.error("请填写所有必填字段"); return; }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) { toast.error("邮箱格式不正确"); return; }
      setStep(2);
      return;
    }
    if (password.length < 6) { toast.error("密码至少需要 6 个字符"); return; }
    if (!passwordsMatch) { toast.error("两次输入的密码不一致"); return; }

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

  const inputCls = cn(
    "h-11 w-full rounded-xl border bg-background/60 text-sm",
    "placeholder:text-muted-foreground/35 transition-all duration-200",
    "border-border/50 hover:border-border",
    "focus:border-primary/40 focus:outline-none focus:ring-[3px] focus:ring-primary/[0.08]",
  );

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background p-4">
      {/* ════════ Unified Background ════════ */}
      <div className="absolute inset-0">
        <div className="absolute right-[-10%] top-[-15%] h-[60vh] w-[60vh] rounded-full bg-primary/[0.06] blur-[120px] animate-[drift1_20s_ease-in-out_infinite]" />
        <div className="absolute left-[-5%] bottom-[-10%] h-[50vh] w-[50vh] rounded-full bg-teal-500/[0.05] blur-[100px] animate-[drift2_25s_ease-in-out_infinite]" />
        <div className="absolute left-[50%] top-[40%] h-[35vh] w-[35vh] rounded-full bg-amber-500/[0.03] blur-[80px] animate-[drift3_18s_ease-in-out_infinite]" />
        <div
          className="absolute inset-0 opacity-[0.035] dark:opacity-[0.04]"
          style={{
            backgroundImage: "radial-gradient(circle, currentColor 0.8px, transparent 0.8px)",
            backgroundSize: "28px 28px",
          }}
        />
      </div>

      {/* ════════ Main Content ════════ */}
      <div className="relative z-10 flex w-full max-w-5xl items-center gap-8 lg:gap-12">
        {/* ─── Left: Brand Story ─── */}
        <div className="hidden md:flex md:w-[340px] lg:w-[400px] shrink-0 flex-col gap-6 animate-in fade-in slide-in-from-left-6 duration-700">
          <BrandMark variant="hero" showSubtitle />

          <div>
            <h2 className="text-2xl lg:text-[28px] font-bold tracking-tight leading-[1.3]">
              开始你的
              <br />
              <span className="text-primary">智能学习之旅</span>
            </h2>
            <p className="mt-3 text-sm text-muted-foreground leading-relaxed">
              注册一个免费账号，上传你的第一份学习材料，让 AI 帮你从多个维度理解和掌握知识。
            </p>
          </div>

          {/* Value props — compact cards */}
          <div className="space-y-2.5">
            {VALUE_PROPS.map((v) => (
              <div key={v.title} className="group flex items-start gap-3 rounded-xl border border-border/30 bg-card/40 backdrop-blur-sm p-3.5 hover:bg-card/60 hover:border-border/50 transition-all duration-300">
                <div className={cn("flex h-8 w-8 shrink-0 items-center justify-center rounded-lg", v.bg)}>
                  <v.icon className={cn("h-4 w-4", v.color)} />
                </div>
                <div>
                  <h3 className="text-[13px] font-semibold mb-0.5">{v.title}</h3>
                  <p className="text-xs text-muted-foreground leading-relaxed">{v.desc}</p>
                </div>
              </div>
            ))}
          </div>

          <p className="text-[11px] text-muted-foreground/50 flex items-center gap-1.5">
            <Shield className="h-3 w-3" />
            你的文档仅用于生成学习资料，不会被分享给第三方
          </p>
        </div>

        {/* ─── Right: Register Form ─── */}
        <div className="flex-1 max-w-[420px] ml-auto animate-in fade-in slide-in-from-right-6 duration-700">
          {/* Mobile logo */}
          <div className="mb-7 flex flex-col items-center gap-3 md:hidden">
            <BrandMark variant="hero" showSubtitle />
            <div className="text-center">
              <h1 className="text-lg font-semibold tracking-tight">创建账号</h1>
              <p className="mt-0.5 text-xs text-muted-foreground">开始你的零幻觉学习之旅</p>
            </div>
          </div>

          {/* Desktop header */}
          <div className="hidden md:block mb-6">
            <h1 className="text-xl font-bold tracking-tight">创建账号</h1>
            <p className="mt-1 text-sm text-muted-foreground">免费注册，开始你的智能学习之旅</p>
          </div>

          {/* Step indicator */}
          <div className="flex items-center gap-2 mb-5">
            <div className="flex items-center gap-2">
              <div className={cn("flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-bold transition-all", step >= 1 ? "bg-primary text-white" : "bg-muted text-muted-foreground")}>1</div>
              <span className={cn("text-[11px] font-medium transition-colors", step >= 1 ? "text-foreground" : "text-muted-foreground")}>基本信息</span>
            </div>
            <div className={cn("flex-1 h-px transition-all", step >= 2 ? "bg-primary" : "bg-border/50")} />
            <div className="flex items-center gap-2">
              <div className={cn("flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-bold transition-all", step >= 2 ? "bg-primary text-white" : "bg-muted text-muted-foreground")}>2</div>
              <span className={cn("text-[11px] font-medium transition-colors", step >= 2 ? "text-foreground" : "text-muted-foreground")}>设置密码</span>
            </div>
          </div>

          {/* Form card */}
          <div className="rounded-2xl border border-border/50 bg-card/70 backdrop-blur-xl p-6 shadow-2xl shadow-black/[0.04] dark:shadow-black/20">
            <form onSubmit={handleSubmit} className="space-y-4">
              {step === 1 ? (
                <>
                  <div className="space-y-1.5">
                    <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">用户名</label>
                    <div className="relative">
                      <User className={cn("absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200", focused === "username" ? "text-primary" : "text-muted-foreground/40")} />
                      <input type="text" value={username} onChange={e => setUsername(e.target.value)} onFocus={() => setFocused("username")} onBlur={() => setFocused(null)} placeholder="用于登录" className={cn(inputCls, "pl-10 pr-4")} autoFocus />
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">邮箱</label>
                    <div className="relative">
                      <AtSign className={cn("absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200", focused === "email" ? "text-primary" : "text-muted-foreground/40")} />
                      <input type="email" value={email} onChange={e => setEmail(e.target.value)} onFocus={() => setFocused("email")} onBlur={() => setFocused(null)} placeholder="your@email.com" className={cn(inputCls, "pl-10 pr-4")} />
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">显示名称</label>
                    <div className="relative">
                      <Sparkles className={cn("absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200", focused === "displayName" ? "text-primary" : "text-muted-foreground/40")} />
                      <input type="text" value={displayName} onChange={e => setDisplayName(e.target.value)} onFocus={() => setFocused("displayName")} onBlur={() => setFocused(null)} placeholder="你的昵称" className={cn(inputCls, "pl-10 pr-4")} />
                    </div>
                  </div>
                </>
              ) : (
                <>
                  {/* Summary badge */}
                  <div className="rounded-lg bg-primary/5 border border-primary/15 px-3 py-2 flex items-center gap-2">
                    <div className="flex h-7 w-7 items-center justify-center rounded-full bg-primary/10 text-primary dark:text-primary text-[11px] font-bold">{displayName?.[0]?.toUpperCase()}</div>
                    <div className="min-w-0">
                      <p className="text-[13px] font-medium truncate">{displayName}</p>
                      <p className="text-[11px] text-muted-foreground truncate">{email}</p>
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">密码</label>
                    <div className="relative">
                      <Lock className={cn("absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200", focused === "password" ? "text-primary" : "text-muted-foreground/40")} />
                      <input type={showPassword ? "text" : "password"} value={password} onChange={e => setPassword(e.target.value)} onFocus={() => setFocused("password")} onBlur={() => setFocused(null)} placeholder="至少 6 个字符" className={cn(inputCls, "pl-10 pr-10")} autoFocus />
                      <button type="button" onClick={() => setShowPassword(v => !v)} aria-label={showPassword ? "隐藏密码" : "显示密码"} className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground/30 hover:text-foreground transition-colors">
                        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                    {password.length > 0 && (
                      <div className="flex gap-1 pt-1">
                        {[3, 6, 9, 12].map((threshold, i) => (
                          <div key={i} className={cn("h-1 flex-1 rounded-full transition-all", password.length >= threshold ? (password.length >= 12 ? "bg-primary" : password.length >= 8 ? "bg-amber-500" : "bg-rose-500") : "bg-muted")} />
                        ))}
                      </div>
                    )}
                  </div>

                  <div className="space-y-1.5">
                    <label className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">确认密码</label>
                    <div className="relative">
                      <Lock className={cn("absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors duration-200", focused === "confirm" ? "text-primary" : "text-muted-foreground/40")} />
                      <input
                        type={showPassword ? "text" : "password"}
                        value={confirmPassword}
                        onChange={e => setConfirmPassword(e.target.value)}
                        onFocus={() => setFocused("confirm")}
                        onBlur={() => setFocused(null)}
                        placeholder="再次输入密码"
                        className={cn(
                          inputCls, "pl-10 pr-10",
                          confirmPassword && !passwordsMatch && "border-rose-500/40 focus:border-rose-500/40 focus:ring-rose-500/[0.08]",
                          confirmPassword && passwordsMatch && confirmPassword.length > 0 && "border-primary/40",
                        )}
                      />
                      {confirmPassword && passwordsMatch && confirmPassword.length > 0 && (
                        <div className="absolute right-3 top-1/2 -translate-y-1/2 text-primary">
                          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}><path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" /></svg>
                        </div>
                      )}
                    </div>
                    {confirmPassword && !passwordsMatch && <p className="text-[11px] text-rose-500">两次输入的密码不一致</p>}
                  </div>
                </>
              )}

              {/* Actions */}
              <div className="flex gap-2 pt-1">
                {step === 2 && (
                  <button type="button" onClick={() => setStep(1)} className="flex h-11 items-center justify-center gap-1 rounded-xl border border-border/50 px-4 text-sm text-muted-foreground hover:text-foreground hover:bg-muted transition-all">
                    返回
                  </button>
                )}
                <button
                  type="submit"
                  disabled={loading || (step === 2 && (!password || !confirmPassword || !passwordsMatch))}
                  className={cn(
                    "relative flex h-11 flex-1 items-center justify-center gap-2 rounded-xl text-sm font-semibold transition-all duration-200",
                    "bg-primary text-white shadow-lg shadow-primary/20 hover:shadow-xl hover:shadow-primary/30",
                    "hover:brightness-110 active:scale-[0.98]",
                    "disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100",
                    "overflow-hidden",
                  )}
                >
                  <div className="absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/15 to-transparent animate-[shimmer_2.5s_ease-in-out_infinite]" />
                  {loading ? <Sparkles className="h-4 w-4 animate-spin" /> : step === 1 ? (
                    <>下一步<ArrowRight className="h-4 w-4" /></>
                  ) : (
                    <><UserPlus className="h-4 w-4" />创建账号<ArrowRight className="h-4 w-4" /></>
                  )}
                </button>
              </div>
            </form>
          </div>

          {/* Footer */}
          <div className="mt-5 flex flex-col items-center gap-3">
            <p className="text-[13px] text-muted-foreground">
              已有账号？{" "}
              <Link href="/login" className="text-primary hover:underline font-semibold underline-offset-2">立即登录</Link>
            </p>
            <Link href="/demo" className="group inline-flex items-center gap-1.5 text-[12px] text-muted-foreground/60 hover:text-primary transition-colors">
              <Play className="h-3 w-3" />
              先体验演示
              <ChevronRight className="h-3 w-3 opacity-0 -translate-x-1 group-hover:opacity-100 group-hover:translate-x-0 transition-all" />
            </Link>
          </div>
        </div>
      </div>

      <style jsx global>{`
        @keyframes shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
        @keyframes drift1 { 0%, 100% { transform: translate(0, 0); } 33% { transform: translate(25px, -15px); } 66% { transform: translate(-10px, 20px); } }
        @keyframes drift2 { 0%, 100% { transform: translate(0, 0); } 33% { transform: translate(-20px, 10px); } 66% { transform: translate(15px, -20px); } }
        @keyframes drift3 { 0%, 100% { transform: translate(0, 0); } 50% { transform: translate(10px, -25px); } }
      `}</style>
    </div>
  );
}
