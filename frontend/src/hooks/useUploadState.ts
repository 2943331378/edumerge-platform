import { useState, useCallback } from "react";
import { toast } from "sonner";
import type { SessionRecord } from "@/lib/api";
import { uploadDocument, listSessions } from "@/lib/api";

export function useUploadState(
  loadSessions: () => Promise<void>,
  setActiveSession: (s: SessionRecord | null) => void,
  setCurrentStep: (s: number) => void,
) {
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  const handleUpload = useCallback(async (file: File | FileList) => {
    if (uploading) return;
    const files = file instanceof FileList ? Array.from(file) : [file];
    if (files.length === 0) return;
    setUploading(true);
    setUploadProgress(0);
    try {
      for (let i = 0; i < files.length; i++) {
        const result = await uploadDocument(files[i], (p) => {
          const overall = ((i * 100 + p) / files.length);
          setUploadProgress(Math.round(overall));
        });
        toast.success(`${result.fileName} 上传成功，正在后台处理`);
      }
      setUploadProgress(100);
      await loadSessions();
      const list = await listSessions();
      if (list.length > 0) {
        setActiveSession(list[0]);
        setCurrentStep(1);
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "上传失败");
    }
    setUploading(false);
  }, [loadSessions, setActiveSession, setCurrentStep, uploading]);

  return { uploading, uploadProgress, handleUpload };
}
