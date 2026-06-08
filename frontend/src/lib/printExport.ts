/**
 * Print-to-PDF export utilities using browser's built-in print.
 * No heavy PDF libraries — creates a hidden iframe, injects print-optimized HTML, calls window.print().
 */

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/\n/g, "<br>");
}

const PRINT_STYLES = `
  <style>
    @page { margin: 1.5cm; size: A4; }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
      color: #111; background: #fff;
      -webkit-print-color-adjust: exact; print-color-adjust: exact;
    }
    .print-header {
      text-align: center; padding-bottom: 16px; margin-bottom: 24px;
      border-bottom: 2px solid #111;
    }
    .print-header h1 { font-size: 22px; font-weight: 700; margin-bottom: 4px; }
    .print-header .meta { font-size: 11px; color: #666; }
    /* === Side-by-side layout === */
    .cards-side { display: flex; flex-wrap: wrap; gap: 16px; }
    .card-pair {
      width: calc(50% - 8px); border: 1px solid #ddd; border-radius: 8px;
      overflow: hidden; page-break-inside: avoid;
    }
    .card-pair .front, .card-pair .back { padding: 16px; }
    .card-pair .front {
      border-bottom: 1px dashed #ccc; background: #f9f9fb;
    }
    .card-pair .back { background: #fff; }
    .card-pair .label {
      font-size: 10px; font-weight: 700; text-transform: uppercase;
      letter-spacing: 0.08em; color: #888; margin-bottom: 8px;
    }
    .card-pair .front .label { color: #6366f1; }
    .card-pair .back .label { color: #10b981; }
    .card-pair .text { font-size: 14px; line-height: 1.6; }
    .card-pair .explanation {
      margin-top: 8px; font-size: 11px; color: #888; font-style: italic;
      border-top: 1px solid #eee; padding-top: 6px;
    }
    .card-num {
      display: inline-block; width: 20px; height: 20px; line-height: 20px;
      text-align: center; border-radius: 4px; background: #eef2ff;
      color: #6366f1; font-size: 11px; font-weight: 700; margin-right: 6px;
    }
    /* === Anki-style: all fronts then all backs === */
    .section-title {
      font-size: 16px; font-weight: 700; margin: 24px 0 12px;
      padding-bottom: 6px; border-bottom: 1px solid #ddd;
    }
    .card-list .card-item {
      border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px 16px;
      margin-bottom: 10px; page-break-inside: avoid;
    }
    .card-list .card-item .text { font-size: 14px; line-height: 1.6; }
    .card-list .card-item .explanation {
      margin-top: 6px; font-size: 11px; color: #888; font-style: italic;
    }
    @media print {
      body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    }
  </style>
`;

type FlashcardData = { question: string; answer: string; explanation?: string };

/**
 * Print flashcards in side-by-side or Anki-style layout.
 */
export function printFlashcards(
  deckTitle: string,
  cards: FlashcardData[],
  layout: "side-by-side" | "anki" = "side-by-side",
) {
  const dateStr = new Date().toLocaleDateString("zh-CN");
  let body = "";

  if (layout === "side-by-side") {
    const pairs = cards
      .map(
        (c, i) => `
      <div class="card-pair">
        <div class="front">
          <div class="label"><span class="card-num">${i + 1}</span>Question</div>
          <div class="text">${escapeHtml(c.question)}</div>
        </div>
        <div class="back">
          <div class="label">Answer</div>
          <div class="text">${escapeHtml(c.answer)}</div>
          ${c.explanation ? `<div class="explanation">${escapeHtml(c.explanation)}</div>` : ""}
        </div>
      </div>`,
      )
      .join("");
    body = `<div class="cards-side">${pairs}</div>`;
  } else {
    // Anki-style
    const fronts = cards
      .map(
        (c, i) => `
      <div class="card-item">
        <div class="text"><span class="card-num">${i + 1}</span>${escapeHtml(c.question)}</div>
      </div>`,
      )
      .join("");
    const backs = cards
      .map(
        (c, i) => `
      <div class="card-item">
        <div class="text"><span class="card-num">${i + 1}</span>${escapeHtml(c.answer)}</div>
        ${c.explanation ? `<div class="explanation">${escapeHtml(c.explanation)}</div>` : ""}
      </div>`,
      )
      .join("");
    body = `
      <div class="section-title">Questions</div>
      <div class="card-list">${fronts}</div>
      <div class="section-title" style="page-break-before: always;">Answers</div>
      <div class="card-list">${backs}</div>`;
  }

  const html = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>${escapeHtml(deckTitle)}</title>
${PRINT_STYLES}</head><body>
<div class="print-header">
  <h1>${escapeHtml(deckTitle)}</h1>
  <div class="meta">${cards.length} cards &middot; ${dateStr}</div>
</div>
${body}
</body></html>`;

  openPrintIframe(html);
}

/**
 * Print a study note as a clean document.
 */
export function printNote(title: string, markdownContent: string) {
  const dateStr = new Date().toLocaleDateString("zh-CN");
  // Simple markdown → HTML conversion for print (covers common cases)
  let html = escapeHtml(markdownContent);
  // headings
  html = html.replace(/^#### (.+)$/gm, '<h4 style="font-size:14px;font-weight:700;margin:16px 0 8px;">$1</h4>');
  html = html.replace(/^### (.+)$/gm, '<h3 style="font-size:15px;font-weight:700;margin:18px 0 8px;">$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2 style="font-size:17px;font-weight:700;margin:20px 0 10px;padding-bottom:4px;border-bottom:1px solid #ddd;">$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1 style="font-size:20px;font-weight:700;margin:24px 0 12px;">$1</h1>');
  // bold
  html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  // lists
  html = html.replace(/^- (.+)$/gm, '<li style="margin-left:20px;list-style:disc;">$1</li>');
  html = html.replace(/^(\d+)\. (.+)$/gm, '<li style="margin-left:20px;list-style:decimal;">$2</li>');
  // paragraphs
  html = html.replace(/\n\n/g, '</p><p style="margin:8px 0;line-height:1.7;font-size:14px;">');
  html = `<p style="margin:8px 0;line-height:1.7;font-size:14px;">${html}</p>`;

  const fullHtml = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>${escapeHtml(title)}</title>
${PRINT_STYLES}
<style>
  .note-body { font-size: 14px; line-height: 1.7; }
  .note-body h1 { font-size: 20px; font-weight: 700; margin: 24px 0 12px; }
  .note-body h2 { font-size: 17px; font-weight: 700; margin: 20px 0 10px; padding-bottom: 4px; border-bottom: 1px solid #ddd; }
  .note-body h3 { font-size: 15px; font-weight: 700; margin: 18px 0 8px; }
  .note-body p { margin: 8px 0; }
  .note-body li { margin-left: 20px; margin-bottom: 4px; }
  .note-body strong { font-weight: 600; }
  .note-body code {
    background: #f3f4f6; padding: 1px 4px; border-radius: 3px;
    font-size: 12px; font-family: "Fira Code", monospace;
  }
  .note-body blockquote {
    border-left: 3px solid #ddd; padding-left: 12px; color: #666;
    margin: 12px 0; font-style: italic;
  }
  .note-body table { border-collapse: collapse; width: 100%; margin: 12px 0; }
  .note-body th, .note-body td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; font-size: 13px; }
  .note-body th { background: #f9fafb; font-weight: 600; }
</style>
</head><body>
<div class="print-header">
  <h1>${escapeHtml(title)}</h1>
  <div class="meta">Study Note &middot; ${dateStr}</div>
</div>
<div class="note-body">${html}</div>
</body></html>`;

  openPrintIframe(fullHtml);
}

/**
 * Open a hidden iframe, inject HTML, and trigger print.
 * The iframe is removed after printing.
 */
function openPrintIframe(html: string) {
  const iframe = document.createElement("iframe");
  iframe.style.position = "fixed";
  iframe.style.right = "0";
  iframe.style.bottom = "0";
  iframe.style.width = "0";
  iframe.style.height = "0";
  iframe.style.border = "0";
  iframe.style.visibility = "hidden";
  document.body.appendChild(iframe);

  const doc = iframe.contentDocument || iframe.contentWindow?.document;
  if (!doc) {
    document.body.removeChild(iframe);
    return;
  }
  doc.open();
  doc.write(html);
  doc.close();

  // Wait for content to render, then print
  iframe.contentWindow?.focus();
  setTimeout(() => {
    iframe.contentWindow?.print();
    // Clean up after a delay (print dialog may be open)
    setTimeout(() => {
      if (iframe.parentNode) document.body.removeChild(iframe);
    }, 1000);
  }, 300);
}
