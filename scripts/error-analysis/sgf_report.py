"""Bảng S01-S08 / G01-G05 / F01-F04 (MVP 6.4) và ba ca bổ sung của MVP 7.1 từ báo cáo Surefire.

Ca được nhận theo quy ước của dự án: @DisplayName bắt đầu bằng mã ca ("F03 — ..."). Ba ca 7.1 (enum lạ, thiếu
trường, input quá lớn) liệt kê tên test tường minh ở EXTRA. Chạy SAU `mvn test`:

    python -I scripts/error-analysis/sgf_report.py src/test/java target/surefire-reports > <file.md>
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

EXTRA = {
    "7.1 enum lạ": ["GuardrailsTest#g03OutOfTaxonomyIsRejected", "GuardrailsTest#g03LowercaseIsNotAccepted",
                    "RequestModelTest#unknownIntentIsRejected", "RequestParserServiceTest#invalidAiAnswerFallsBack"],
    "7.1 thiếu trường": ["OpenAiAnalyzerTest#missingFieldsAreInvalid", "OpenAiAnalyzerTest#wrongShapeIsInvalid",
                         "GuardrailsTest#g02MalformedAnswerIsRejected", "GuardrailsTest#g02BlankAnalysisIsRejected",
                         "GuardrailsTest#g02NullAnswerIsRejected"],
    "7.1 input quá lớn": ["AnalyzeControllerTest#tooLongQuestionIsRejectedBeforeAnalysis",
                          "OpenAiAnalyzerTest#truncatedAnswerIsInvalid", "OpenAiChatClientTest#httpErrorsAreClassified",
                          "AnalyzeControllerTest#oversizedFileIsRejectedAlone", "CallFolderReaderTest#oversizedFileIsNotLoaded"],
}
CASE = re.compile(r'@DisplayName\("([SGF]0\d)\b')
METHOD = re.compile(r'void\s+(\w+)\s*\(')


def cases_from_sources(root):
    found = {}
    for dp, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith(".java"):
                continue
            lines = open(os.path.join(dp, f), encoding="utf-8").read().splitlines()
            for i, line in enumerate(lines):
                m = CASE.search(line)
                if not m:
                    continue
                for nxt in lines[i + 1:i + 4]:
                    mm = METHOD.search(nxt)
                    if mm:
                        found.setdefault(m.group(1), []).append(f[:-5] + "#" + mm.group(1))
                        break
    return found


def results(report_dir):
    status = {}
    for f in os.listdir(report_dir):
        if not (f.startswith("TEST-") and f.endswith(".xml")):
            continue
        for tc in ET.parse(os.path.join(report_dir, f)).getroot().iter("testcase"):
            key = tc.get("classname").rsplit(".", 1)[-1] + "#" + tc.get("name")
            bad = tc.find("failure") is not None or tc.find("error") is not None
            skipped = tc.find("skipped") is not None
            status[key] = "LỖI" if bad else ("BỎ QUA" if skipped else "qua")
    return status


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    cases = cases_from_sources(sys.argv[1])
    cases.update(EXTRA)
    status = results(sys.argv[2])
    print("| Ca | Số test | Kết quả | Test |")
    print("|---|---|---|---|")
    total = ok = 0
    for case in sorted(cases, key=lambda c: (c.startswith("7.1"), c)):
        tests = cases[case]
        st = [status.get(t, "KHÔNG THẤY") for t in tests]
        total += len(tests)
        ok += st.count("qua")
        verdict = "qua" if all(s == "qua" for s in st) else ", ".join(sorted(set(st)))
        print("| %s | %d | %s | %s |" % (case, len(tests), verdict, ", ".join("`%s`" % t for t in tests)))
    print()
    print("Tổng: %d/%d test qua." % (ok, total))


if __name__ == "__main__":
    main()
