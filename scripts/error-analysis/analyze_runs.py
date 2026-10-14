"""Phân tích lỗi trên các lượt benchmark (MVP mục 7.1 T1): sai verdict, sai category, không nhất quán, unsupported claim.

Đọc file .json của --evaluate / --rescore và file case YAML (đáp án HIỆN TẠI, như --rescore), in bảng Markdown.
Không gọi AI, không cần ES. Chỉ đọc mã, kết luận và câu hỏi đã làm sạch có sẵn trong file kết quả.

    python scripts/error-analysis/analyze_runs.py benchmark/dev-cases.yaml <lượt1.json> [<lượt2.json> ...]
"""
import collections
import json
import os
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8")


def load_cases(path):
    cases = {}
    with open(path, encoding="utf-8") as f:
        for doc in yaml.safe_load_all(f):
            if not doc or "case_id" not in doc:
                continue
            questions = []
            for q in doc.get("questions") or []:
                if isinstance(q, dict):
                    questions.append((q.get("text"), q.get("expected_intent")))
                else:
                    questions.append((q, None))
            cases[doc["case_id"]] = {
                "verdict": doc.get("expected_verdict"),
                "category": doc.get("expected_issue_category"),
                "questions": questions,
            }
    return cases


def analysis_question(cases, run):
    """Câu hỏi phân tích (không đánh dấu OUT_OF_SCOPE) — câu duy nhất có verdict để chấm."""
    qs = cases.get(run["caseId"], {}).get("questions", [])
    i = run["questionIndex"] - 1
    return not (0 <= i < len(qs) and str(qs[i][1]).upper() == "OUT_OF_SCOPE")


def describe(path, cases):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    runs = data["runs"]
    setup = data.get("setup", {})
    known = [r for r in runs if r["caseId"] in cases]
    analysis = [r for r in known if analysis_question(cases, r)]
    labelled = [r for r in analysis if cases[r["caseId"]]["verdict"]]

    out = [f"### `{os.path.relpath(path)}`", ""]
    out.append(f"- {len(runs)} lần chạy, lặp {data.get('repeat')} · AI: {setup.get('AI', '?')} · "
               f"Prompt AI: {setup.get('Prompt AI', 'không ghi (trước Sprint 3)')}")

    # 1. Sai verdict — tách theo nguyên nhân
    wrong = [r for r in labelled if r.get("type") != "REPORT" or r.get("verdict") != cases[r["caseId"]]["verdict"]]
    out.append(f"- **Sai verdict: {len(wrong)}/{len(labelled)}**")
    kinds = collections.Counter()
    for r in wrong:
        if r.get("error"):
            kinds["lỗi pipeline " + r["error"]] += 1
        elif r.get("type") != "REPORT":
            kinds[f"câu phân tích bị trả {r.get('type')} (intent {r.get('intent')})"] += 1
        else:
            kinds[f"report {r.get('verdict')} thay vì {cases[r['caseId']]['verdict']} "
                  f"(nguồn {r.get('analysisSource')}, AI thô {r.get('aiVerdict')})"] += 1
    for k, n in kinds.most_common():
        out.append(f"  - {n} × {k}")
    for (case, qi, q), n in collections.Counter(
            (r["caseId"], r["questionIndex"], r.get("question")) for r in wrong).most_common(8):
        out.append(f"    - {case} câu {qi} ({n} lần): \"{q}\"")

    # 2. Sai category: chỉ chấm được khi có nhãn; luôn báo AI lệch rule (G04)
    scored = [r for r in analysis if cases[r["caseId"]]["category"]]
    if scored:
        bad = [r for r in scored if r.get("issueCategory") != cases[r["caseId"]]["category"]]
        out.append(f"- **Sai category: {len(bad)}/{len(scored)}**")
    else:
        out.append("- **Sai category: N/A** — chưa case nào có expected_issue_category")
    answered = [r for r in analysis if r.get("aiVerdict")]
    off_verdict = [r for r in answered if r.get("aiVerdict") != r.get("ruleVerdict")]
    off_category = [r for r in answered if r.get("aiVerdict") == r.get("ruleVerdict")
                    and (r.get("aiIssueCategory") or "NONE") != (r.get("ruleIssueCategory") or "NONE")]
    out.append(f"  - AI lệch rule (trước Guardrails): verdict {len(off_verdict)}/{len(answered)}, "
               f"cùng verdict khác category {len(off_category)}/{len(answered)}")
    for r in (off_verdict + off_category)[:6]:
        out.append(f"    - {r['caseId']} câu {r['questionIndex']} lần {r['repeat']}: AI {r.get('aiVerdict')}/"
                   f"{r.get('aiIssueCategory')}, rule {r.get('ruleVerdict')}/{r.get('ruleIssueCategory')}")

    # 3. Không nhất quán: theo case (mọi câu, mọi lần) và theo câu (các lần lặp)
    def key(r):
        if r.get("error"):
            return "ERROR"
        return f"{r.get('verdict')}/{r.get('issueCategory') or '-'}" if r.get("type") == "REPORT" else r.get("type")
    by_case, by_question = collections.defaultdict(list), collections.defaultdict(list)
    for r in analysis:
        by_case[r["caseId"]].append(key(r))
        by_question[(r["caseId"], r["questionIndex"])].append(key(r))
    unstable_cases = {c: collections.Counter(v) for c, v in by_case.items() if len(set(v)) > 1}
    unstable_q = {q: collections.Counter(v) for q, v in by_question.items() if len(v) > 1 and len(set(v)) > 1}
    out.append(f"- **Không nhất quán:** {len(unstable_cases)}/{len(by_case)} case khác kết quả giữa các câu / lần; "
               f"{len(unstable_q)}/{sum(1 for v in by_question.values() if len(v) > 1)} câu khác kết quả giữa các "
               f"lần lặp")
    for c, cnt in list(unstable_cases.items())[:6]:
        out.append(f"  - {c}: " + ", ".join(f"{k} ×{n}" for k, n in cnt.most_common()))

    # 4. Unsupported claim (G01 / G05) và vi phạm khác
    violations = collections.Counter(v for r in runs for v in r.get("violations") or [])
    claims = {v: n for v, n in violations.items() if v.startswith(("G01", "G05"))}
    out.append(f"- **Unsupported claim (G01/G05) trước Guardrails:** "
               f"{sum(1 for r in answered if any(v.startswith(('G01', 'G05')) for v in r.get('violations') or []))}"
               f"/{len(answered)} lần AI trả lời")
    for v, n in sorted(claims.items(), key=lambda x: -x[1]):
        out.append(f"  - {n} × {v}")

    # 5. Intent (không có trong 4 loại của 7.1 nhưng là nguồn duy nhất của sai verdict ở Sprint 2)
    def expected_intent(r):
        qs = cases[r["caseId"]]["questions"]
        i = r["questionIndex"] - 1
        return qs[i][1] if 0 <= i < len(qs) else None
    for r in known:
        r["expectedIntent"] = expected_intent(r)        # nhãn của file case hiện tại, như --rescore
    intent_labelled = [r for r in known if r.get("expectedIntent")]
    intent_wrong = [r for r in intent_labelled if r.get("intent") != r.get("expectedIntent")]
    out.append(f"- **Sai intent:** {len(intent_wrong)}/{len(intent_labelled)}")
    for (case, qi, q, exp, got), n in collections.Counter(
            (r["caseId"], r["questionIndex"], r.get("question"), r.get("expectedIntent"), r.get("intent"))
            for r in intent_wrong).most_common(6):
        out.append(f"  - {case} câu {qi} ({n} lần): \"{q}\" — nhãn {exp}, ra {got}")

    fallback = collections.Counter(r.get("fallbackReason") for r in runs if r.get("degraded"))
    out.append(f"- **Fallback:** {sum(fallback.values())} lần" +
               ("" if not fallback else " — " + ", ".join(f"{k} ×{n}" for k, n in fallback.most_common())))
    out.append("")
    return "\n".join(out)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cases = load_cases(sys.argv[1])
    for path in sys.argv[2:]:
        print(describe(path, cases))


if __name__ == "__main__":
    main()
