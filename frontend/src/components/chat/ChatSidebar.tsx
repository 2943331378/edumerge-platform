"use client";

import { useState, useRef, DragEvent, ChangeEvent } from "react";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { Upload, FileText, Trash2, X } from "lucide-react";

export interface UploadedDoc {
  id: string;
  name: string;
  size: number;
  status: "uploading" | "done" | "error";
  chunks?: number;
}

interface ChatSidebarProps {
  documents: UploadedDoc[];
  onUpload: (file: File) => Promise<void>;
  onDelete: (id: string) => void;
  className?: string;
}

export function ChatSidebar({ documents, onUpload, onDelete, className }: ChatSidebarProps) {
  const [dragging, setDragging] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const handleFile = async (file: File | null) => {
    if (!file) return;
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (!extension || !["pdf", "doc", "docx", "ppt", "pptx", "txt"].includes(extension)) return;
    await onUpload(file);
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    handleFile(e.dataTransfer.files[0] ?? null);
  };

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    handleFile(e.target.files?.[0] ?? null);
    if (fileRef.current) fileRef.current.value = "";
  };

  return (
    <aside className={cn("flex h-full flex-col border-r bg-muted/20", className)}>
      {/* Header */}
      <div className="flex items-center gap-2 px-4 h-14 border-b">
        <FileText className="h-5 w-5 text-muted-foreground" />
        <span className="font-semibold text-sm">知识库</span>
      </div>

      {/* Upload zone */}
      <div className="p-3">
        <div
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={handleDrop}
          className={cn(
            "relative flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-4 text-center transition-colors cursor-pointer",
            dragging
              ? "border-primary bg-primary/5"
              : "border-muted-foreground/25 hover:border-muted-foreground/50"
          )}
          onClick={() => fileRef.current?.click()}
        >
          <Upload className={cn("h-5 w-5", dragging ? "text-primary" : "text-muted-foreground")} />
          <p className="text-xs text-muted-foreground">
            {dragging ? "释放以上传" : "拖拽 PDF 到此处"}
          </p>
          <Button variant="secondary" size="sm" className="text-xs h-7 px-3" type="button">
            选择文件
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"
            onChange={handleChange}
            className="hidden"
            aria-label="选择文件上传"
            title="选择文件上传"
          />
        </div>
      </div>

      <Separator />

      {/* Document list */}
      <ScrollArea className="flex-1">
        <div className="space-y-1 p-2">
          {documents.length === 0 && (
            <p className="px-2 py-4 text-center text-xs text-muted-foreground">
              尚未上传文档
            </p>
          )}
          {documents.map((doc) => (
            <div
              key={doc.id}
              className="group flex items-center gap-2 rounded-md px-2 py-1.5 text-xs hover:bg-accent"
            >
              <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              <span className="flex-1 truncate">{doc.name}</span>
              <Badge
                variant={doc.status === "done" ? "secondary" : doc.status === "error" ? "destructive" : "outline"}
                className="h-4 text-[10px] px-1"
              >
                {doc.status === "uploading" ? "处理中" : doc.status === "error" ? "失败" : doc.chunks ? `${doc.chunks}块` : "完成"}
              </Badge>
              <button
                onClick={(e) => { e.stopPropagation(); onDelete(doc.id); }}
                className="opacity-0 max-md:opacity-100 group-hover:opacity-100 p-0.5 max-md:p-1.5 rounded active:bg-destructive/10 transition-all"
                aria-label="删除文档"
                title="删除文档"
              >
                <X className="h-3 w-3 text-muted-foreground hover:text-destructive" />
              </button>
            </div>
          ))}
        </div>
      </ScrollArea>

      {/* Footer */}
      <div className="border-t px-3 py-2">
        <p className="text-[10px] text-muted-foreground text-center">
          支持 PDF 格式，上限 50MB
        </p>
      </div>
    </aside>
  );
}
