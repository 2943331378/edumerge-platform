"use client";

import { useEffect, useRef, useCallback, useState } from "react";
import { useTheme } from "next-themes";
import { Transformer } from "markmap-lib";
import { Markmap } from "markmap-view";
import type { INode, IPureNode } from "markmap-common";
import { Button } from "@/components/ui/button";
import { Maximize2, Loader2, Printer, UnfoldVertical, FoldVertical, Image } from "lucide-react";
import { toast } from "sonner";

interface MindMapViewerProps {
  markdown: string;
  className?: string;
  onContextChange?: (hint: string) => void;
}

/** markmap 思维导图渲染器: 支持 zoom/pan/折叠/导出 */
export function MindMapViewer({ markdown, className = "", onContextChange }: MindMapViewerProps) {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const mmRef = useRef<Markmap | null>(null);
  const rootRef = useRef<IPureNode | null>(null);
  const [ready, setReady] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [svgSize, setSvgSize] = useState({ w: 1200, h: 800 });

  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  useEffect(() => {
    onContextChange?.("用户正在查看思维导图");
  }, [onContextChange]);

  const transformer = useRef(new Transformer());

  /** 品牌色梯度: 深度1=蓝, 深度2=紫, 更深=灰。深色主题使用高亮度色值 */
  const colorByDepth = useCallback((node: INode): string => {
    const depth = node.state?.depth ?? 0;
    if (isDark) {
      if (depth <= 1) return "oklch(0.72 0.22 255)";
      if (depth === 2) return "oklch(0.7 0.22 285)";
      return "oklch(0.68 0.04 260)";
    }
    if (depth <= 1) return "oklch(0.48 0.22 255)";
    if (depth === 2) return "oklch(0.52 0.24 285)";
    return "oklch(0.55 0.03 260)";
  }, [isDark]);

  /** 线条粗细随深度递减 */
  const lineWidthByDepth = useCallback((node: INode): number => {
    const depth = node.state?.depth ?? 0;
    if (depth <= 1) return 2.5;
    if (depth === 2) return 2;
    if (depth === 3) return 1.5;
    return 1;
  }, []);

  const mmOptions = useCallback(() => ({
    autoFit: false, // 手动控制 fit, 避免 D3 读取百分比 SVG 尺寸报错
    duration: 400,
    initialExpandLevel: -1,
    maxInitialScale: 3,
    pan: true,
    zoom: true,
    scrollForPan: true,
    color: colorByDepth,
    lineWidth: lineWidthByDepth,
    paddingX: 16,
    spacingHorizontal: 80,
    spacingVertical: 20,
    maxWidth: 300,
    nodeMinHeight: 26,
  }), [colorByDepth, lineWidthByDepth]);

  // Ref 始终指向最新 mmOptions，供 effect 内读取而不触发重运行
  const mmOptionsRef = useRef(mmOptions);
  useEffect(() => { mmOptionsRef.current = mmOptions; }, [mmOptions]);

  // 跟踪容器像素尺寸, 避开 D3 读取百分比 SVGLength 的 bug
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => {
      const rect = entry.contentRect;
      if (rect.width > 0 && rect.height > 0) {
        setSvgSize({ w: rect.width, h: rect.height });
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // 初始化 / 更新 markmap
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;

    const { root } = transformer.current.transform(markdown);
    rootRef.current = root;

    if (mmRef.current) {
      mmRef.current.setData(root);
      setTimeout(() => mmRef.current?.fit(), 100);
    } else {
      mmRef.current = Markmap.create(svg, { ...mmOptionsRef.current(), autoFit: false }, root);
      setTimeout(() => mmRef.current?.fit(), 100);
    }
    setReady(true);
    setCollapsed(false);

    if (mmRef.current && mmRef.current.zoom) {
      mmRef.current.zoom.scaleExtent([0.1, 8]);
    }

    return () => {
      mmRef.current?.destroy();
      mmRef.current = null;
    };
  }, [markdown]);

  // 容器尺寸变化时更新 SVG 尺寸
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    svg.setAttribute("width", String(svgSize.w));
    svg.setAttribute("height", String(svgSize.h));
    svg.setAttribute("viewBox", `0 0 ${svgSize.w} ${svgSize.h}`);
  }, [svgSize]);

  // 主题切换时重新应用节点颜色
  useEffect(() => {
    if (!mmRef.current || !rootRef.current || !ready) return;
    mmRef.current.setData(rootRef.current, mmOptionsRef.current());
    setTimeout(() => mmRef.current?.fit(), 100);
  }, [isDark]);

  // 点击节点后自动自适应
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg || !ready) return;
    const onNodeClick = (e: MouseEvent) => {
      const target = e.target as Element;
      if (target.closest(".markmap-node")) {
        // markmap toggle 动画约 400ms, 等动画结束后 fit
        setTimeout(() => mmRef.current?.fit(), 500);
      }
    };
    svg.addEventListener("click", onNodeClick);
    return () => svg.removeEventListener("click", onNodeClick);
  }, [ready]);

  useEffect(() => {
    if (!ready) return;
    const styleId = "mindmap-brand-overrides";
    if (document.getElementById(styleId)) return;
    const style = document.createElement("style");
    style.id = styleId;
    style.textContent = `
      .markmap-link {
        stroke: oklch(0.48 0.22 255 / 0.5);
        transition: stroke 0.3s;
      }
      .markmap-node circle {
        transition: all 0.3s;
      }
      .markmap-node:hover > circle {
        filter: brightness(1.2);
      }
      .dark .markmap-foreign div {
        color: #f1f5f9 !important;
      }
      .dark .markmap-link {
        stroke: oklch(0.65 0.18 255 / 0.55) !important;
      }
    `;
    document.head.appendChild(style);
    return () => {
      const el = document.getElementById(styleId);
      if (el) el.remove();
    };
  }, [ready]);

  const handleReset = useCallback(() => {
    mmRef.current?.fit();
  }, []);

  const handleCollapse = useCallback(() => {
    if (!mmRef.current || !rootRef.current) return;
    mmRef.current.setData(rootRef.current, { ...mmOptions(), initialExpandLevel: 1, autoFit: false });
    setCollapsed(true);
    setTimeout(() => mmRef.current?.fit(), 100);
  }, [mmOptions]);

  const handleExpand = useCallback(() => {
    if (!mmRef.current || !rootRef.current) return;
    mmRef.current.setData(rootRef.current, { ...mmOptions(), initialExpandLevel: -1, autoFit: false });
    setCollapsed(false);
    setTimeout(() => mmRef.current?.fit(), 100);
  }, [mmOptions]);

  // ═══════ 导出 PNG ═══════
  const handleExportPNG = useCallback(async () => {
    const svg = svgRef.current;
    if (!svg) return;
    try {
      const clone = svg.cloneNode(true) as SVGSVGElement;
      const w = svgSize.w;
      const h = svgSize.h;
      clone.setAttribute("width", String(w));
      clone.setAttribute("height", String(h));
      clone.setAttribute("viewBox", `0 0 ${w} ${h}`);

      // Inject font-family fallback into the cloned SVG so exported PNG
      // renders correctly even when external web fonts are unavailable.
      const fontStyle = document.createElementNS("http://www.w3.org/2000/svg", "style");
      fontStyle.textContent = `* { font-family: "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif !important; }`;
      clone.insertBefore(fontStyle, clone.firstChild);

      const svgData = new XMLSerializer().serializeToString(clone);
      const svgBlob = new Blob([svgData], { type: "image/svg+xml;charset=utf-8" });
      const url = URL.createObjectURL(svgBlob);

      const img = new window.Image();
      img.onload = () => {
        const canvas = document.createElement("canvas");
        const scale = 2;
        canvas.width = w * scale;
        canvas.height = h * scale;
        const ctx = canvas.getContext("2d")!;
        ctx.fillStyle = isDark ? "#0f172a" : "#ffffff";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

        canvas.toBlob((blob) => {
          if (!blob) return;
          const pngUrl = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = pngUrl;
          a.download = `思维导图_${new Date().toISOString().slice(0, 10)}.png`;
          a.click();
          URL.revokeObjectURL(url);
          setTimeout(() => URL.revokeObjectURL(pngUrl), 100);
          toast.success("PNG 导出成功");
        }, "image/png");
      };
      img.onerror = () => { URL.revokeObjectURL(url); toast.error("PNG 导出失败"); };
      img.src = url;
    } catch {
      toast.error("PNG 导出失败");
    }
  }, [svgSize]);

  // ═══════ 打印 / 导出 PDF ═══════
  const handlePrint = useCallback(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const clone = svg.cloneNode(true) as SVGSVGElement;
    clone.setAttribute("width", String(svgSize.w));
    clone.setAttribute("height", String(svgSize.h));
    let svgData = new XMLSerializer().serializeToString(clone);
    // Sanitize SVG to prevent XSS: remove script tags, event handlers, and data: URIs in href/src
    svgData = svgData
      .replace(/<script[\s\S]*?<\/script>/gi, "")
      .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "")
      .replace(/(href|src)\s*=\s*("(?:data:[^"]*)?"|'(?:data:[^']*)?')/gi, "");
    const w = window.open("", "_blank", "width=1200,height=800");
    if (!w) { toast.error("请允许弹出窗口以导出 PDF"); return; }
    w.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>思维导图</title><style>body{margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh}svg{max-width:100%;max-height:100vh}@media print{body{margin:0}svg{max-width:100%;page-break-inside:avoid}}</style></head><body>${svgData}</body></html>`);
    w.document.close();
    w.focus();
    setTimeout(() => { w.print(); w.close(); }, 500);
  }, [svgSize]);

  const btnBase = "h-8 w-8 rounded-lg bg-white/60 dark:bg-slate-800/60 backdrop-blur-md border border-white/20 hover:bg-white/80 dark:hover:bg-slate-700/80 transition-all shadow-sm";

  return (
    <div ref={containerRef} className={`relative w-full h-full ${className}`}>
      {!ready && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-background/40 backdrop-blur-sm rounded-lg">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            加载思维导图...
          </div>
        </div>
      )}

      <div className="absolute top-3 right-3 z-10 flex gap-1.5">
        <Button variant="ghost" size="icon" className={btnBase} onClick={collapsed ? handleExpand : handleCollapse} title={collapsed ? "展开全部节点" : "折叠至一级"}>
          {collapsed ? <UnfoldVertical className="h-4 w-4 text-muted-foreground" /> : <FoldVertical className="h-4 w-4 text-muted-foreground" />}
        </Button>
        <Button variant="ghost" size="icon" className={btnBase} onClick={handleReset} title="自适应屏幕">
          <Maximize2 className="h-4 w-4 text-muted-foreground" />
        </Button>
        <Button variant="ghost" size="icon" className={btnBase} onClick={handleExportPNG} title="导出为 PNG 图片">
          <Image className="h-4 w-4 text-muted-foreground" />
        </Button>
        <Button variant="ghost" size="icon" className={btnBase} onClick={handlePrint} title="打印 / 导出为 PDF">
          <Printer className="h-4 w-4 text-muted-foreground" />
        </Button>
      </div>

      <svg
        ref={svgRef}
        width={svgSize.w}
        height={svgSize.h}
        style={{ display: "block" }}
      />
    </div>
  );
}
