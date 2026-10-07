/*
 * Web UI (Sprint 2 T1): câu hỏi + nhiều file log → POST /api/analyze → hiển thị report.
 *
 * Hai quy tắc:
 * - Chữ đến từ server (có cả chữ do AI viết) chỉ gán bằng textContent, không bao giờ innerHTML: AI có trả
 *   về thẻ HTML hay script thì cũng chỉ hiện ra như chữ.
 * - Nhãn đầu report và thứ tự mục lấy từ GET /api/report-layout (chính ReportRenderer phía server), để bản
 *   web không lệch khỏi mẫu MVP 4.5 như bản text.
 */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const form = $("ask");
  const question = $("question");
  const dropzone = $("dropzone");
  const fileInput = $("file-input");
  const fileList = $("file-list");
  const submit = $("submit");
  const submitHint = $("submit-hint");
  let busy = false;

  /** Cùng điều kiện với server (ChatAnalysisService): cần ít nhất một file, hoặc Call-ID trong câu hỏi. */
  const CALL_ID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

  function canSubmit() {
    return files.size > 0 || CALL_ID.test(question.value);
  }

  /** Nút chỉ bật khi có thứ để phân tích; dòng gợi ý nói trước điều sẽ xảy ra khi bấm. */
  function updateSubmit() {
    submit.disabled = busy || !canSubmit();
    if (busy) {
      submitHint.textContent = "Đang phân tích, chờ kết quả bên phải.";
    } else if (!canSubmit()) {
      submitHint.textContent = "Đính kèm ít nhất một file log, hoặc ghi Call-ID vào câu hỏi.";
    } else if (!question.value.trim()) {
      submitHint.textContent = "Chưa có câu hỏi: hệ thống sẽ phân tích chung cuộc gọi.";
    } else {
      submitHint.textContent = "Ctrl + Enter để gửi.";
    }
  }
  const result = $("result");

  /** Tên file → File. Chọn lại file trùng tên thì thay bản cũ. */
  const files = new Map();
  let layout = null;

  const VERDICT_ICON = { FAIL: "✕", SUCCESS: "✓", UNKNOWN: "?" };
  const CONFIDENCE_BARS = { HIGH: 3, MEDIUM: 2, LOW: 1 };
  const SOURCE_NAMES = { SIGNALING: "Signaling", ENDCALL: "End Call", WEBRTC: "WebRTC" };
  const FALLBACK_TEXT = {
    NOT_CONFIGURED: "chưa cấu hình khoá AI",
    TIMEOUT: "AI phản hồi quá thời gian chờ",
    PROVIDER_ERROR: "không gọi được dịch vụ AI",
    INVALID_RESPONSE: "AI trả lời sai định dạng",
    REFUSED: "AI từ chối trả lời",
    GUARDRAIL_REJECTED: "câu trả lời của AI không qua bước kiểm tra",
    UNEXPECTED_ERROR: "lỗi không lường trước khi gọi AI",
    REPORT_SCHEMA_INVALID: "report có phần AI không đúng cấu trúc",
  };
  const INTENT_TEXT = {
    ANALYZE_CALL: "Phân tích chung cuộc gọi",
    ANALYZE_WITH_FOCUS: "Phân tích, ưu tiên",
    OUT_OF_SCOPE: "Ngoài phạm vi phân tích cuộc gọi",
  };

  // ---------- dựng DOM an toàn ----------

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (value === null || value === undefined || value === false) continue;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? "" : value);
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function banner(kind, title, text) {
    const node = $("banner-template").content.firstElementChild.cloneNode(true);
    node.classList.add(kind);
    node.querySelector(".banner-icon").textContent = kind === "info" ? "i" : "!";
    node.querySelector(".banner-title").textContent = title;
    node.querySelector(".banner-text").textContent = text || "";
    if (!text) node.querySelector(".banner-text").remove();
    return node;
  }

  function show(...nodes) {
    result.replaceChildren(...nodes);
  }

  // ---------- bố cục từ server ----------

  async function loadLayout() {
    try {
      const response = await fetch("api/report-layout");
      layout = await response.json();
      $("empty-sections").replaceChildren(
          ...[layout.header[1], ...layout.sections].map((s) => el("li", { text: s })));
    } catch {
      show(banner("critical", "Không kết nối được máy chủ phân tích",
          "Kiểm tra server đã chạy với profile web, rồi tải lại trang."));
    }
  }

  // ---------- file đính kèm ----------

  function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function addFiles(list) {
    for (const file of list) files.set(file.name, file);
    renderFiles();
  }

  function renderFiles() {
    const max = layout ? layout.maxBytesPerFile : Infinity;
    fileList.replaceChildren(...[...files.values()].map((file) => {
      const tooBig = file.size > max;
      // Quá cỡ vẫn gửi lên: server phải loại và nêu tên file ở "Giới hạn dữ liệu" (MVP mục 6.4, F04).
      return el("li", { class: tooBig ? "file-chip too-big" : "file-chip" },
          el("span", { class: "file-icon", "aria-hidden": "true" }),
          el("span", { class: "file-name", title: file.name, text: file.name }),
          el("span", { class: "file-size", text: formatSize(file.size) }),
          el("button", {
            type: "button", class: "remove", "aria-label": `Bỏ file ${file.name}`, text: "×",
            onclick: () => { files.delete(file.name); renderFiles(); },
          }),
          tooBig && el("span", { class: "file-warning",
            text: `Vượt giới hạn ${formatSize(max)}: sẽ bị loại khỏi phân tích và nêu trong report.` }));
    }));
    updateSubmit();
  }

  fileInput.addEventListener("change", () => { addFiles(fileInput.files); fileInput.value = ""; });

  // Đếm độ sâu để viền không nhấp nháy khi kéo qua phần tử con
  let dragDepth = 0;
  dropzone.addEventListener("dragenter", (e) => { e.preventDefault(); dragDepth++; dropzone.classList.add("dragging"); });
  dropzone.addEventListener("dragover", (e) => e.preventDefault());
  dropzone.addEventListener("dragleave", () => { if (--dragDepth === 0) dropzone.classList.remove("dragging"); });
  dropzone.addEventListener("drop", (e) => {
    e.preventDefault();
    dragDepth = 0;
    dropzone.classList.remove("dragging");
    addFiles(e.dataTransfer.files);
  });

  for (const button of document.querySelectorAll(".suggestion")) {
    button.addEventListener("click", () => { question.value = button.textContent; question.focus(); updateSubmit(); });
  }

  question.addEventListener("input", updateSubmit);
  question.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) form.requestSubmit();
  });

  // ---------- gửi ----------

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    // requestSubmit() (Ctrl + Enter) vẫn gửi được khi nút bị tắt — chặn ở đây cho cả hai đường
    if (busy || !canSubmit()) return;
    const body = new FormData();
    body.append("message", question.value);
    for (const file of files.values()) body.append("files", file, file.name);

    busy = true;
    submit.classList.add("busy");
    updateSubmit();
    const started = performance.now();
    const counter = el("p", { class: "waiting", text: "Đang phân tích…" });
    const timer = setInterval(() => {
      counter.textContent = `Đang phân tích… ${Math.round((performance.now() - started) / 1000)} giây`;
    }, 1000);
    show(el("div", { class: "report" }, counter,
        el("div", { class: "skeleton", "aria-hidden": "true" }, el("span"), el("span"), el("span"))));

    try {
      const response = await fetch("api/analyze", { method: "POST", body });
      const data = await response.json().catch(() => ({}));
      showResponse(response.status, data);
    } catch {
      show(banner("critical", "Không gửi được yêu cầu",
          "Không kết nối được máy chủ phân tích. Kiểm tra server còn chạy rồi thử lại."));
    } finally {
      clearInterval(timer);
      busy = false;
      submit.classList.remove("busy");
      updateSubmit();
    }
  });

  function showResponse(status, data) {
    showUnderstood(data);
    switch (data.type) {
      case "REPORT":
        show(...reportBanners(data.report), renderReport(data.report, data.rendered));
        break;
      case "OUT_OF_SCOPE":
        show(banner("info", "Câu hỏi nằm ngoài phạm vi", data.message));
        break;
      case "INVALID_REQUEST":
        show(banner("critical", "Chưa có gì để phân tích", data.message));
        break;
      default:
        show(banner("critical", status === 413 ? "File đính kèm quá lớn" : "Phân tích không thành công",
            data.message || `Máy chủ trả mã ${status}. Thử lại sau ít phút.`));
    }
  }

  function showUnderstood(data) {
    const box = $("understood");
    if (!data.intent) { box.hidden = true; return; }
    const text = INTENT_TEXT[data.intent] || data.intent;
    $("understood-text").textContent = data.focus ? `${text}: ${data.focus}` : text;
    box.hidden = false;
  }

  // ---------- report ----------

  /** Banner đặt TRÊN khung report — không chen vào giữa các dòng đầu của mẫu 4.5. */
  function reportBanners(r) {
    const banners = [];
    if (r.degraded) {
      banners.push(banner("warning", "Report dựng từ rule vì AI không dùng được",
          `Lý do: ${FALLBACK_TEXT[r.fallbackReason] || r.fallbackReason}. Độ tin cậy tối đa MEDIUM.`));
    }
    if (r.needsReview) {
      banners.push(banner("critical", "Cần kiểm tra: AI và rule không thống nhất",
          "Kết luận và độ tin cậy đã được hạ theo quy tắc đối chiếu. Lý do cụ thể ở mục Giới hạn dữ liệu."));
    }
    return banners;
  }

  function renderReport(r, markdown) {
    const cited = new Set(r.citedEvidenceIds);
    const sourceText = r.analysisSource === "AI" ? "Phân tích bởi AI, đối chiếu với rule"
        : r.degraded ? "Dựng từ rule, AI không dùng được" : "Dựng từ rule";

    const markdownBox = el("pre", { class: "markdown", hidden: true, text: markdown || "" });
    const toggle = el("button", { type: "button", class: "tool", text: "Xem bản Markdown" });
    toggle.addEventListener("click", () => {
      markdownBox.hidden = !markdownBox.hidden;
      toggle.textContent = markdownBox.hidden ? "Xem bản Markdown" : "Ẩn bản Markdown";
    });

    const sections = layout.sections.map((title) => {
      const build = SECTION_BUILDERS[title];
      const body = build ? build(r, cited) : { count: null,
        node: el("p", { class: "hint", text: "Giao diện chưa biết cách hiển thị mục này; xem bản Markdown." }) };
      return el("details", { class: "section", open: true },
          el("summary", {}, title, body.count === null ? null : el("span", { class: "count", text: `(${body.count})` })),
          body.node);
    });

    return el("article", { class: "report", "aria-label": layout.title },
        el("div", { class: "report-top" },
            el("h1", { text: layout.title }),
            el("div", { class: "tools" },
                el("span", { class: "source-tag", text: sourceText }),
                toggle,
                copyButton("Sao chép Markdown", () => markdown || ""))),
        el("p", { class: `verdict ${r.verdict}`, "aria-hidden": "true" },
            el("span", { class: "verdict-icon", text: VERDICT_ICON[r.verdict] || "?" }), r.verdict),
        headerFields(r),
        ...sections,
        markdownBox);
  }

  /** Năm dòng đầu của mẫu 4.5, đúng nhãn, đúng thứ tự. */
  function headerFields(r) {
    const [callIdLabel, verdictLabel, flagLabel, confidenceLabel, summaryLabel] = layout.header;
    const bars = CONFIDENCE_BARS[r.confidenceLevel] || 0;
    return el("dl", { class: "header-fields" },
        el("dt", { text: callIdLabel }),
        el("dd", {}, el("span", { class: "mono", text: r.callId }), copyButton("Sao chép", () => r.callId, "tool copy")),
        el("dt", { text: verdictLabel }),
        el("dd", { text: r.verdict }),
        el("dt", { text: flagLabel }),
        el("dd", r.qualityFlag ? { class: "flag-on", text: `Có - ${r.issueCategory}` } : { text: "Không" }),
        el("dt", { text: confidenceLabel }),
        el("dd", {},
            el("span", { class: "meter", "aria-hidden": "true" },
                [1, 2, 3].map((i) => el("i", { class: i <= bars ? "on" : null }))),
            r.confidenceLevel),
        el("dt", { text: summaryLabel }),
        el("dd", { class: "summary", text: r.summary }));
  }

  function copyButton(label, value, cls = "tool") {
    const button = el("button", { type: "button", class: cls, text: label });
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(value());
        button.textContent = "Đã sao chép";
      } catch {
        button.textContent = "Không sao chép được";
      }
      setTimeout(() => { button.textContent = label; }, 1500);
    });
    return button;
  }

  /** Mốc tuyệt đối ISO-8601 → HH:mm:ss.SSSZ (UTC, như bản text); mốc tương đối giữ nguyên. */
  function clock(ts) {
    const m = /T(\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(ts || "");
    if (!m) return ts || "-";
    return `${m[1]}.${(m[2] || "0").padEnd(3, "0").slice(0, 3)}Z`;
  }

  function flashEvidence(id) {
    const item = document.getElementById(`ev-${id}`);
    if (!item) return;
    item.scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
      block: "center" });
    item.classList.add("flash");
    setTimeout(() => item.classList.remove("flash"), 1600);
  }

  function list(items, cls = "plain-list") {
    if (!items.length) return el("p", { class: "hint", text: "Không có." });
    return el("ul", { class: cls }, items.map((text) => el("li", { text })));
  }

  const SECTION_BUILDERS = {
    "Evidence chính": (r, cited) => {
      if (!r.evidence.length) return { count: 0, node: el("p", { class: "hint", text: "Không có." }) };
      // Mốc tương đối (WebRTC log) không cùng gốc thời gian với mốc tuyệt đối: dây đứt, không nối thứ tự giả.
      const item = (e) => el("li", { id: `ev-${e.id}`, class: cited.has(e.id) ? "cited" : null },
          el("span", { class: "ev-id", text: e.id }),
          el("span", { class: "ev-meta" },
              // Trích dẫn signaling ("signaling#12") đã tự nêu nguồn; file log thì cần nhãn nguồn đứng trước
              e.source === "SIGNALING" ? null : `${SOURCE_NAMES[e.source] || e.source} `,
              el("span", { class: "mono", text: e.sourceRef }),
              ` ${clock(e.timestamp)}`,
              cited.has(e.id) && el("span", { class: "ev-cited-mark", text: "AI trích làm căn cứ" })),
          el("span", { class: "ev-desc", text: e.description }));
      const absolute = r.evidence.filter((e) => !String(e.timestamp).startsWith("+"));
      const relative = r.evidence.filter((e) => String(e.timestamp).startsWith("+"));
      return { count: r.evidence.length, node: el("div", {},
          absolute.length > 0 && el("ol", { class: "wire" }, absolute.map(item)),
          absolute.length > 0 && relative.length > 0 && el("p", { class: "wire-break",
            text: "Mốc dưới đây tính từ lúc WebRTC log bắt đầu, không xếp chung thứ tự với các mốc ở trên." }),
          relative.length > 0 && el("ol", { class: "wire" }, relative.map(item))) };
    },

    "Chỉ số cuộc gọi": (r) => ({ count: r.metrics.length, node: el("div", { class: "table-wrap" },
        el("table", {},
            el("thead", {}, el("tr", {}, el("th", { text: "Chỉ số" }), el("th", { text: "Giá trị" }),
                el("th", { text: "Nguồn" }))),
            el("tbody", {}, r.metrics.map((m) => {
              const proxy = (m.unit || "").includes("[proxy]") || (m.value || "").includes("[proxy]");
              const value = m.value === null
                  ? el("td", { class: "na", text: `N/A (${m.naReason})` })
                  : el("td", {}, `${m.value.replace(" [proxy]", "")}${m.unit ? " " + m.unit.replace(" [proxy]", "") : ""}`,
                      proxy && el("span", { class: "proxy", title: "Chỉ số gián tiếp, không đo trực tiếp", text: "proxy" }));
              return el("tr", {}, el("td", { text: m.name }), value,
                  el("td", { text: SOURCE_NAMES[m.source] || m.source }));
            })))) }),

    "Vấn đề chất lượng / nguyên nhân khả dĩ": (r) => ({ count: null, node: el("ul", { class: "causes" },
        el("li", {}, el("span", { class: "cause-label", text: "Chính: " }),
            r.possibleCauses?.primary || "không có vấn đề nào được phát hiện"),
        r.analysis && el("li", {}, el("span", { class: "cause-label", text: "Phân tích: " }), r.analysis),
        r.citedEvidenceIds.length > 0 && el("li", {}, el("span", { class: "cause-label", text: "Căn cứ: " }),
            el("span", { class: "cite-chips" }, r.citedEvidenceIds.map((id) =>
                el("button", { type: "button", class: "cite", text: id, "aria-label": `Xem evidence ${id}`,
                  onclick: () => flashEvidence(id) })))),
        (r.possibleCauses?.alternatives || []).map((a) =>
            el("li", {}, el("span", { class: "cause-label", text: "Khả dĩ khác: " }), a))) }),

    "Đề xuất": (r) => ({ count: null, node: list(r.suggestions) }),

    "Giới hạn dữ liệu": (r) => ({ count: r.dataLimitations.length, node: list(r.dataLimitations) }),
  };

  loadLayout().then(renderFiles);
})();
