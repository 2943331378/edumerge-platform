import type { Metadata } from "next";
import { AppShell } from "@/components/app-shell";
import "./globals.css";

export const metadata: Metadata = {
  title: "智融 EduMerge - AI 学习伴侣",
  description: "基于 RAG 的智能学习辅助平台",
  icons: { icon: "/logo_converted.svg" },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN"
      className="h-full antialiased"
      suppressHydrationWarning
    >
      <body className="h-full">
        <AppShell>
          {children}
        </AppShell>
      </body>
    </html>
  );
}
