"use client";

import { Button } from "@/components/ui/button";
import { AlertCircle, RefreshCw, Home } from "lucide-react";

export default function ErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-destructive/10">
        <AlertCircle className="h-8 w-8 text-destructive" />
      </div>
      <div className="space-y-2 max-w-md">
        <h1 className="text-xl font-semibold text-foreground">页面出错了</h1>
        <p className="text-sm text-muted-foreground">
          {error.message || "页面渲染时发生错误，请尝试刷新。"}
        </p>
      </div>
      <div className="flex items-center gap-3">
        <Button variant="outline" size="sm" onClick={reset} className="gap-1.5">
          <RefreshCw className="h-3.5 w-3.5" />
          重试
        </Button>
        <Button variant="ghost" size="sm" onClick={() => window.location.href = "/"} className="gap-1.5">
          <Home className="h-3.5 w-3.5" />
          返回首页
        </Button>
      </div>
    </div>
  );
}
