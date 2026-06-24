"use client";

import { useEffect, useRef, useState } from "react";
import mermaid from "mermaid";
import { AlertTriangle } from "lucide-react";

let currentTheme: string | null = null;

const CLOSE: Record<string, string> = { '[': ']', '{': '}', '(': ')' };

/** 修复 LLM 生成的 mermaid 中常见的语法错误 */
function sanitizeMermaid(raw: string): string {
  // ── 0. 全角括号 → 半角 ──
  let code = raw.replace(/（/g, '(').replace(/）/g, ')');

  // ── 1. 节点文本: 逐字符匹配，正确处理嵌套括号 ──
  const chars = [...code];
  const parts: string[] = [];
  let i = 0;
  while (i < chars.length) {
    if (/\w/.test(chars[i])) {
      let id = '';
      while (i < chars.length && /\w/.test(chars[i])) { id += chars[i]; i++; }
      if (i < chars.length && (chars[i] === '[' || chars[i] === '{' || chars[i] === '(')) {
        const open = chars[i];
        const expectedClose = CLOSE[open];
        i++;
        let inner = '';
        let depth = 1;
        while (i < chars.length && depth > 0) {
          if (chars[i] === expectedClose) { depth--; if (depth === 0) break; }
          if (chars[i] === open) depth++;
          inner += chars[i]; i++;
        }
        if (i < chars.length) i++;
        const trimmed = inner.trim();
        if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
          parts.push(id + open + inner + expectedClose);
        } else if (/[{},:;()|]/.test(trimmed)) {
          parts.push(id + open + '"' + trimmed.replace(/"/g, '#quot;').replace(/\|/g, '#124;') + '"' + expectedClose);
        } else {
          parts.push(id + open + inner + expectedClose);
        }
      } else {
        parts.push(id);
      }
    } else {
      parts.push(chars[i]);
      i++;
    }
  }
  code = parts.join('');

  // ── 2. subgraph 标题: 去除括号等特殊字符 ──
  code = code.replace(/^(\s*subgraph\s+)(.+)$/gim, (_m, prefix: string, title: string) => {
    return prefix + title.replace(/[(){}[\]]/g, '');
  });

  // ── 3. 边标签: 剥离引号，转义方括号、尖括号、括号 ──
  code = code.replace(/\|([^|]*)\|/g, (_m, inner: string) => {
    let t = inner.replace(/"/g, '');
    t = t.replace(/[[\]]/g, (c) => (c === '[' ? '#91;' : '#93;'));
    t = t.replace(/[<>]/g, (c) => (c === '<' ? '#lt;' : '#gt;'));
    t = t.replace(/[()]/g, (c) => (c === '(' ? '&lpar;' : '&rpar;'));
    return '|' + t + '|';
  });

  // ── 4. 注释行清理尖括号 ──
  code = code.replace(/^(\s*%%.*)$/gm, (line) => line.replace(/[<>]/g, ''));
  return code;
}

function syncMermaidTheme() {
  const isDark = document.documentElement.classList.contains("dark");
  const theme = isDark ? "dark" : "default";
  if (theme === currentTheme) return;
  currentTheme = theme;
  mermaid.initialize({
    startOnLoad: false,
    theme,
    securityLevel: "strict",
    fontFamily: "inherit",
    flowchart: { curve: "basis", padding: 15 },
  });
}

interface Props {
  code: string;
}

export function MermaidDiagram({ code }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current || !code.trim()) return;
    syncMermaidTheme();

    let cancelled = false;
    const render = async () => {
      try {
        const id = `mermaid-${Math.random().toString(36).slice(2, 10)}`;
        const cleaned = sanitizeMermaid(code.trim());
        const { svg } = await mermaid.render(id, cleaned);
        document.getElementById(id)?.remove();
        if (!cancelled && containerRef.current) {
          containerRef.current.innerHTML = svg;
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "图表渲染失败");
        }
      }
    };
    void render();
    return () => { cancelled = true; };
  }, [code]);

  if (error) {
    return (
      <div className="my-4 flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-400">
        <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
        <span>图表渲染失败</span>
        <details className="ml-auto cursor-pointer text-[11px] opacity-60">
          <summary>详情</summary>
          <pre className="mt-1 max-h-24 overflow-auto whitespace-pre-wrap">{error}</pre>
        </details>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="my-4 flex justify-center overflow-x-auto rounded-lg border border-border/30 bg-background/50 px-4 py-3 [&>svg]:max-w-full [&>svg]:h-auto"
    />
  );
}
