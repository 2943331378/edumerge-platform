"use client";

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";
import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/theme-toggle";
import { StatsDashboard } from "@/components/StatsDashboard";
import { useAuth } from "@/lib/auth-context";
import { ArrowLeft, LogOut, User, Loader2 } from "lucide-react";

export default function DashboardPage() {
  const auth = useAuth();
  const router = useRouter();
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!auth.loading && !auth.token) {
      router.replace("/login");
    }
  }, [auth.loading, auth.token, router]);

  if (auth.loading || !auth.token) {
    return (
      <div className="flex h-full items-center justify-center">
        {auth.loading && <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />}
      </div>
    );
  }

  const handleAction = (action: "upload" | "flashcard" | "quiz" | "knowledge-graph" | { type: "flashcard-doc"; docId: number }) => {
    if (action === "upload") {
      // Navigate to main page and trigger upload
      sessionStorage.setItem("edumerge_pending_action", "upload");
      router.push("/");
    } else if (action === "flashcard") {
      sessionStorage.setItem("edumerge_pending_action", JSON.stringify({ step: 4 }));
      router.push("/");
    } else if (action === "quiz") {
      sessionStorage.setItem("edumerge_pending_action", JSON.stringify({ step: 5 }));
      router.push("/");
    } else if (action === "knowledge-graph") {
      sessionStorage.setItem("edumerge_pending_action", JSON.stringify({ knowledgeGraph: true }));
      router.push("/");
    } else if (typeof action === "object" && action.type === "flashcard-doc") {
      sessionStorage.setItem("edumerge_pending_action", JSON.stringify({ step: 4, docId: action.docId }));
      router.push("/");
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <header className="flex items-center justify-between px-4 py-2 border-b border-border/50 bg-background/50 backdrop-blur shrink-0">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 rounded-lg"
            onClick={() => router.push("/")}
            title="返回学习"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <BrandMark variant="header" />
          <span className="text-sm font-medium text-foreground/70">个人中心</span>
        </div>
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <button
            type="button"
            className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
          >
            <User className="h-3.5 w-3.5" />
            <span className="hidden sm:inline max-w-[80px] truncate">
              {auth.user?.displayName ?? auth.user?.username ?? "用户"}
            </span>
          </button>
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 rounded-lg text-muted-foreground hover:text-destructive"
            onClick={() => { auth.logout(); }}
            title="退出登录"
          >
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </header>

      {/* Dashboard content */}
      <StatsDashboard onAction={handleAction} />

      {/* Hidden file input for upload action */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"
        className="hidden"
      />
    </div>
  );
}
