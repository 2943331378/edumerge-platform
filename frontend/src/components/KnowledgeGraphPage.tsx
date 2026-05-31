"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import {
  getKnowledgeGraph, generateKnowledgeGraph,
  getConceptDetail, getConceptDocuments,
  type KnowledgeGraphData, type ConceptNode,
  type ConceptDetail, type ConceptDocSource,
} from "@/lib/api";
import {
  GitBranch, Sparkles, Loader2, RotateCw, Search,
  X, FileText, MessageSquare, ArrowUpRight,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import dynamic from "next/dynamic";

// Dynamic import to avoid SSR issues with canvas
const ForceGraph2D = dynamic(() => import("react-force-graph-2d"), { ssr: false });

interface Props {
  sessions: { id: number; fileName?: string | null; title: string; docId: number }[];
  onSelectSession: (sessionId: number) => void;
  onOpenChat: (contextHint: string) => void;
}

type GraphNode = {
  id: number;
  name: string;
  val: number;
  color: string;
  conceptId: number;
  importance: number;
  docCount: number;
  x?: number;
  y?: number;
};

type GraphLink = {
  source: number | GraphNode;
  target: number | GraphNode;
  color: string;
  label: string;
  strength: number;
};

function getNodeColor(docCount: number, isDark: boolean): string {
  if (docCount >= 4) return isDark ? "#fb923c" : "#ea580c";
  if (docCount >= 2) return isDark ? "#2dd4bf" : "#0d9488";
  return isDark ? "#60a5fa" : "#2563eb";
}

function getLinkColor(type: string): string {
  const colors: Record<string, string> = {
    IS_A: "#a78bfa", PART_OF: "#34d399", RELATES_TO: "#94a3b8",
    PREREQUISITE: "#f472b6", CONTRADICTS: "#f87171", APPLIES_TO: "#fbbf24",
  };
  return colors[type] ?? "#94a3b8";
}

const RELATION_LABELS: Record<string, string> = {
  IS_A: "是一种", PART_OF: "组成部分", RELATES_TO: "相关",
  PREREQUISITE: "前置知识", CONTRADICTS: "矛盾", APPLIES_TO: "应用",
};

export function KnowledgeGraphPage({ sessions, onSelectSession, onOpenChat }: Props) {
  const [graphData, setGraphData] = useState<KnowledgeGraphData | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);

  const [selectedNode, setSelectedNode] = useState<ConceptNode | null>(null);
  const [selectedDetail, setSelectedDetail] = useState<ConceptDetail | null>(null);
  const [selectedDocs, setSelectedDocs] = useState<ConceptDocSource[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  const [searchQuery, setSearchQuery] = useState("");
  const [filterDocId, setFilterDocId] = useState<number | null>(null);

  // Load existing graph
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const data = await getKnowledgeGraph();
        if (!cancelled) setGraphData(data);
      } catch { /* empty */ }
      if (!cancelled) setLoading(false);
    };
    load();
    return () => { cancelled = true; };
  }, []);

  // Doc filter options
  const docOptions = useMemo(() => {
    const seen = new Set<number>();
    return sessions.filter((s) => {
      if (seen.has(s.docId)) return false;
      seen.add(s.docId);
      return true;
    });
  }, [sessions]);

  // Transform data for force graph
  const fgData = useMemo(() => {
    if (!graphData) return { nodes: [] as GraphNode[], links: [] as GraphLink[] };

    // Doc filter: filter nodes by which documents they appear in
    const docFilteredNodeIds = useMemo(() => {
      if (filterDocId == null || !graphData) return null;
      return new Set(
        graphData.concepts
          .filter((c) => (c as unknown as { documentIds?: number[] }).documentIds?.includes(filterDocId))
          .map((c) => c.id)
      );
    }, [filterDocId, graphData]);

    let nodes = graphData.concepts.map((c) => ({
      id: c.id,
      name: c.name,
      val: Math.max(3, (c.importance ?? 5) * 1.5),
      color: getNodeColor(c.documentCount, false),
      conceptId: c.id,
      importance: c.importance,
      docCount: c.documentCount,
    }));

    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      nodes = nodes.filter((n) => n.name.toLowerCase().includes(q));
    }

    if (docFilteredNodeIds) {
      nodes = nodes.filter((n) => docFilteredNodeIds.has(n.id));
    }

    const links: GraphLink[] = graphData.relationships.map((r) => ({
      source: r.sourceConceptId,
      target: r.targetConceptId,
      color: getLinkColor(r.relationshipType),
      label: r.relationshipType,
      strength: r.strength,
    }));

    return { nodes, links };
  }, [graphData, searchQuery, filterDocId, selectedDocs]);

  const handleGenerate = async () => {
    setGenerating(true);
    try {
      const data = await generateKnowledgeGraph();
      setGraphData(data);
      setSelectedNode(null);
      toast.success(`知识图谱生成完成: ${data.concepts.length} 个概念, ${data.relationships.length} 条关系`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成失败");
    }
    setGenerating(false);
  };

  const handleNodeClick = useCallback(async (node: GraphNode) => {
    setDetailLoading(true);
    setSelectedNode({ id: node.conceptId, name: node.name, importance: node.importance, documentCount: node.docCount });
    try {
      const [detail, docs] = await Promise.all([
        getConceptDetail(node.conceptId),
        getConceptDocuments(node.conceptId),
      ]);
      setSelectedDetail(detail);
      setSelectedDocs(docs);
    } catch { toast.error("加载概念详情失败"); }
    setDetailLoading(false);
  }, []);

  const handleDocumentClick = (sessionId?: number) => {
    if (sessionId) {
      onSelectSession(sessionId);
    }
  };

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Toolbar */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border/50 bg-muted/10 shrink-0 flex-wrap">
        <h2 className="text-sm font-medium text-foreground/80 flex items-center gap-2 mr-2">
          <GitBranch className="h-4 w-4 text-primary" />
          知识图谱
        </h2>

        {!graphData ? (
          <Button size="sm" className="rounded-xl gap-1.5 h-8 text-xs" onClick={handleGenerate} disabled={generating}>
            {generating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            {generating ? "生成中..." : "一键生成"}
          </Button>
        ) : (
          <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 text-xs" onClick={handleGenerate} disabled={generating}>
            {generating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            重新生成
          </Button>
        )}

        <div className="h-4 w-px bg-border/50 mx-1" />

        {/* Search */}
        <div className="relative">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground/40" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜索概念..."
            className="h-7 w-36 text-xs pl-7 rounded-lg"
          />
        </div>

        {/* Doc filter */}
        <select
          value={filterDocId ?? ""}
          onChange={(e) => setFilterDocId(e.target.value ? Number(e.target.value) : null)}
          className="h-7 rounded-lg border border-border/50 bg-background px-2 text-[11px] text-muted-foreground"
        >
          <option value="">全部文档</option>
          {docOptions.map((d) => (
            <option key={d.docId} value={d.docId}>{d.fileName ?? d.title}</option>
          ))}
        </select>

        {/* Legend */}
        <div className="ml-auto flex items-center gap-3 text-[10px] text-muted-foreground/60">
          <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-blue-500" /> 1文档</span>
          <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-teal-500" /> 2-3文档</span>
          <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-orange-500" /> 4+文档</span>
        </div>
      </div>

      {/* Main content: Graph + optional detail panel */}
      <div className="flex-1 flex overflow-hidden">
        {/* Graph area */}
        <div className={cn("flex-1 relative", selectedNode && "md:w-[calc(100%-320px)]")}>
          {!graphData ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-5 text-muted-foreground">
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
                <GitBranch className="h-7 w-7 text-muted-foreground/40" />
              </div>
              <div className="text-center space-y-1.5">
                <p className="text-sm font-medium">暂无知识图谱</p>
                <p className="max-w-sm text-xs leading-6 text-muted-foreground/60">
                  AI 将自动分析所有已上传文档，提取跨文档的核心概念并构建知识网络
                </p>
              </div>
              <Button onClick={handleGenerate} disabled={generating} className="rounded-xl gap-2 h-10">
                {generating ? <RotateCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                一键生成知识图谱
              </Button>
            </div>
          ) : generating ? (
            <div className="absolute inset-0 flex items-center justify-center bg-background/50 backdrop-blur-sm z-10">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
                正在分析文档，提取知识概念...
              </div>
            </div>
          ) : (
            <ForceGraph2D
              graphData={fgData}
              nodeLabel="name"
              nodeVal="val"
              nodeColor="color"
              linkColor="color"
              linkWidth={(l: Record<string, unknown>) => (l.strength as number ?? 0.5) * 2}
              onNodeClick={(node: Record<string, unknown>) => handleNodeClick(node as unknown as GraphNode)}
              nodeCanvasObject={(node: Record<string, unknown>, ctx: CanvasRenderingContext2D, globalScale: number) => {
                const n = node as unknown as GraphNode;
                const label = n.name;
                const fontSize = 12 / globalScale;
                ctx.font = `${fontSize}px sans-serif`;
                ctx.fillStyle = n.color;
                ctx.beginPath();
                ctx.arc(n.x!, n.y!, n.val * 1.2, 0, 2 * Math.PI);
                ctx.fill();
                ctx.fillStyle = "#fff";
                ctx.textAlign = "center";
                ctx.textBaseline = "middle";
                ctx.fillText(label, n.x!, n.y!);
              }}
              cooldownTicks={100}
              enableZoomInteraction
              enablePanInteraction
            />
          )}
        </div>

        {/* Detail panel */}
        {selectedNode && (
          <div className="hidden md:flex w-[320px] shrink-0 border-l border-border flex-col bg-card/50">
            <div className="flex items-center justify-between px-4 py-3 border-b border-border/50 shrink-0">
              <h3 className="text-sm font-medium text-foreground truncate">{selectedNode.name}</h3>
              <button type="button" onClick={() => setSelectedNode(null)} className="p-1 rounded-md hover:bg-muted">
                <X className="h-3.5 w-3.5 text-muted-foreground" />
              </button>
            </div>

            <ScrollArea className="flex-1">
              {detailLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                </div>
              ) : (
                <div className="p-4 space-y-4">
                  {/* Importance */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[10px] text-muted-foreground/60 uppercase tracking-wider">重要度</span>
                      <span className="text-[10px] text-muted-foreground">{selectedNode.importance}/10</span>
                    </div>
                    <div className="h-1.5 rounded-full bg-muted overflow-hidden">
                      <div className="h-full rounded-full bg-primary transition-all"
                        style={{ width: `${(selectedNode.importance / 10) * 100}%` }} />
                    </div>
                  </div>

                  {/* Badges */}
                  <div className="flex gap-1.5 flex-wrap">
                    {selectedNode.documentCount >= 3 && (
                      <Badge className="text-[10px] bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300">高频考点</Badge>
                    )}
                    <Badge className="text-[10px] bg-muted text-muted-foreground">{selectedNode.documentCount} 个文档</Badge>
                  </div>

                  {/* Definition */}
                  {selectedDetail?.concept.definition && (
                    <div>
                      <span className="text-[10px] text-muted-foreground/60 uppercase tracking-wider">定义</span>
                      <div className="mt-1 text-xs text-muted-foreground leading-relaxed prose prose-sm dark:prose-invert max-w-none">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {selectedDetail.concept.definition}
                        </ReactMarkdown>
                      </div>
                    </div>
                  )}

                  {/* Source documents */}
                  {selectedDocs.length > 0 && (
                    <div>
                      <span className="text-[10px] text-muted-foreground/60 uppercase tracking-wider">
                        出现在 {selectedDocs.length} 个文档中
                      </span>
                      <div className="mt-2 space-y-1">
                        {selectedDocs.map((d) => (
                          <button
                            key={d.docId}
                            type="button"
                            onClick={() => handleDocumentClick(d.sessionId)}
                            className="flex items-center gap-2 w-full rounded-lg px-2 py-1.5 text-xs hover:bg-muted/50 transition-colors text-left"
                          >
                            <FileText className="h-3 w-3 shrink-0 text-muted-foreground/50" />
                            <span className="flex-1 truncate text-muted-foreground hover:text-foreground">
                              {d.fileName ?? d.docTitle ?? `文档 #${d.docId}`}
                            </span>
                            <ArrowUpRight className="h-3 w-3 shrink-0 text-muted-foreground/30" />
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Related concepts */}
                  {selectedDetail && selectedDetail.relatedConcepts.length > 0 && (
                    <div>
                      <span className="text-[10px] text-muted-foreground/60 uppercase tracking-wider">关联概念</span>
                      <div className="mt-2 space-y-1">
                        {selectedDetail.relationships.map((r) => {
                          const relatedId = r.sourceId === selectedNode.id ? r.targetId : r.sourceId;
                          const related = selectedDetail.relatedConcepts.find((c) => c.id === relatedId);
                          return (
                            <div key={r.id} className="flex items-center gap-2 text-xs">
                              <span className={cn("text-[10px] px-1 py-0.5 rounded font-medium",
                                r.type === "IS_A" ? "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300" :
                                r.type === "PREREQUISITE" ? "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300" :
                                "bg-muted text-muted-foreground")}>
                                {RELATION_LABELS[r.type] ?? r.type}
                              </span>
                              <span className="text-muted-foreground truncate">{related?.name ?? `#${relatedId}`}</span>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* Ask AI */}
                  <Button
                    variant="outline"
                    size="sm"
                    className="w-full rounded-xl gap-1.5 h-8 text-xs"
                    onClick={() => {
                      const def = selectedDetail?.concept.definition ?? "";
                      onOpenChat(`用户正在查看知识图谱中的概念「${selectedNode.name}」${def ? `: ${def.slice(0, 100)}` : ""}`);
                    }}
                  >
                    <MessageSquare className="h-3 w-3" />
                    Ask AI about this concept
                  </Button>
                </div>
              )}
            </ScrollArea>
          </div>
        )}
      </div>
    </div>
  );
}
