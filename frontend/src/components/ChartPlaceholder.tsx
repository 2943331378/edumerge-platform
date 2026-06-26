"use client";

import { Clock, Network, GitCompare, GitBranch, AlertTriangle } from "lucide-react";

interface ChartPlaceholderProps {
  type: string;
  description: string;
}

const CHART_META: Record<string, { label: string; icon: typeof Clock; gradient: string }> = {
  "chart-timeline": { label: "时间线", icon: Clock, gradient: "from-blue-500/10 to-indigo-500/10" },
  "chart-concept": { label: "概念关系图", icon: Network, gradient: "from-emerald-500/10 to-teal-500/10" },
  "chart-compare": { label: "对比矩阵", icon: GitCompare, gradient: "from-amber-500/10 to-orange-500/10" },
  "chart-tree": { label: "层次树", icon: GitBranch, gradient: "from-violet-500/10 to-purple-500/10" },
};

function parseDescription(raw: string): string[] {
  return raw
    .split("\n")
    .map((l) => l.replace(/^描述[：:]\s*/, "").trim())
    .filter(Boolean);
}

function TimelineView({ lines }: { lines: string[] }) {
  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-1 bottom-1 w-px bg-blue-400/30" />
      {lines.map((line, i) => {
        const parts = line.split(/→|->|—|：|:/).map((s) => s.trim());
        const [era, ...rest] = parts;
        const event = rest.join("：");
        return (
          <div key={i} className="relative mb-4 last:mb-0">
            <div className="absolute -left-4 top-1.5 h-2.5 w-2.5 rounded-full border-2 border-blue-400 bg-background" />
            {era && <p className="text-xs font-semibold text-blue-600 dark:text-blue-400">{era}</p>}
            {event && <p className="mt-0.5 text-sm text-foreground/75">{event}</p>}
          </div>
        );
      })}
    </div>
  );
}

function ConceptView({ lines }: { lines: string[] }) {
  return (
    <div className="flex flex-wrap gap-2">
      {lines.map((line, i) => (
        <div
          key={i}
          className="rounded-lg border border-emerald-400/30 bg-emerald-500/5 px-3 py-1.5 text-sm text-foreground/80"
        >
          {line}
        </div>
      ))}
      {lines.length > 1 && (
        <div className="flex items-center gap-1 text-xs text-muted-foreground/50">
          <Network className="h-3 w-3" />
          节点间存在关联关系
        </div>
      )}
    </div>
  );
}

function CompareView({ lines }: { lines: string[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border/40">
            {lines.map((col, i) => (
              <th key={i} className="px-3 py-2 text-left text-xs font-semibold text-foreground/80 whitespace-nowrap">
                {col}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr className="border-b border-border/20">
            {lines.map((_, i) => (
              <td key={i} className="px-3 py-2 text-foreground/60">
                <div className="h-3 w-16 rounded bg-muted/40 animate-pulse" />
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function TreeView({ lines }: { lines: string[] }) {
  return (
    <div className="space-y-1 font-mono text-sm">
      {lines.map((node, i) => {
        const indent = (node.match(/^\s*/)?.[0].length ?? 0);
        const depth = Math.floor(indent / 2);
        const text = node.trim();
        return (
          <div key={i} className="flex items-center gap-2" style={{ paddingLeft: `${depth * 20}px` }}>
            {depth > 0 && <span className="text-violet-400/50">├─</span>}
            <span className="rounded-md bg-violet-500/10 px-2 py-0.5 text-foreground/80">{text}</span>
          </div>
        );
      })}
    </div>
  );
}

const VIEW_MAP: Record<string, React.ComponentType<{ lines: string[] }>> = {
  "chart-timeline": TimelineView,
  "chart-concept": ConceptView,
  "chart-compare": CompareView,
  "chart-tree": TreeView,
};

export function ChartPlaceholder({ type, description }: ChartPlaceholderProps) {
  const meta = CHART_META[type] ?? {
    label: "图表",
    icon: AlertTriangle,
    gradient: "from-gray-500/10 to-gray-500/10",
  };
  const Icon = meta.icon;
  const lines = parseDescription(description);
  const View = VIEW_MAP[type];

  return (
    <div className={`my-4 rounded-xl border border-border/40 bg-gradient-to-br ${meta.gradient} p-5`}>
      <div className="mb-3 flex items-center gap-2">
        <Icon className="h-4 w-4 text-foreground/60" />
        <span className="text-xs font-semibold text-foreground/70">{meta.label}</span>
        <span className="rounded-full bg-muted/40 px-2 py-0.5 text-[10px] text-muted-foreground/50">可视化占位</span>
      </div>
      {View && lines.length > 0 ? (
        <View lines={lines} />
      ) : (
        <pre className="whitespace-pre-wrap text-sm leading-6 text-foreground/70">{description}</pre>
      )}
    </div>
  );
}
