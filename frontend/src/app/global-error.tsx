"use client";

import { Button } from "@/components/ui/button";
import { AlertCircle, RefreshCw } from "lucide-react";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="zh-CN">
      <body className="h-full">
        <div className="flex h-full flex-col items-center justify-center gap-6 p-8 text-center">
          <div className="inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-destructive/10">
            <AlertCircle className="h-8 w-8 text-destructive" />
          </div>
          <div className="space-y-2 max-w-md">
            <h1 className="text-xl font-semibold">应用出错</h1>
            <p className="text-sm text-gray-500">
              {error.message || "发生了严重错误，请刷新页面。"}
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={reset} className="gap-1.5">
            <RefreshCw className="h-3.5 w-3.5" />
            重试
          </Button>
        </div>
      </body>
    </html>
  );
}
