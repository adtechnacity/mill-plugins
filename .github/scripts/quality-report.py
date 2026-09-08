#!/usr/bin/env python3
"""Quality report for pull requests: coverage, mutation score and CPD, posted as one sticky PR comment.

Python 3 standard library only (runs on ubuntu-latest without pip).

    quality-report.py coverage [--artifact-url URL]
    quality-report.py mutation [MODULE ...] [--artifact-url URL]
    quality-report.py cpd [--warn N] [--error N]
    quality-report.py post --section NAME --pr NUMBER     (section markdown on stdin)
    quality-report.py selftest

Every report subcommand prints a markdown section to stdout and appends it to $GITHUB_STEP_SUMMARY when set.
`post` upserts one section into the single PR comment that starts with the marker below.
"""

import argparse
import csv
import io
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

MARKER = "<!-- quality-report -->"
SECTION_ORDER = ("coverage", "mutation", "cpd")
SECTION_RE = re.compile(r"<!-- section:([\w-]+) -->\n(.*?)\n?<!-- /section:\1 -->", re.S)
COVERAGE_XML = Path("out/scoverage/xmlReportAll.dest/scoverage.xml")
CPD_DIR = Path("out/cpd/cpdCheckAll.dest")
DETECTED = ("Killed", "Timeout")
UNDETECTED = ("Survived", "NoCoverage")
OTHER = ("CompileError", "RuntimeError", "Ignored")


# ---------------------------------------------------------------- helpers


def emit(markdown):
    """Print a section and mirror it into the job summary when running under GitHub Actions."""
    text = markdown.rstrip("\n") + "\n"
    sys.stdout.write(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text + "\n")


def rate(part, total):
    return None if total == 0 else 100.0 * part / total


def fmt_rate(value):
    return "n/a" if value is None else f"{value:.1f}%"


def float_or_none(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def run_url(env=None):
    """URL of the current workflow run, from the GitHub Actions environment (`env` defaults to os.environ)."""
    env = os.environ if env is None else env
    if env.get("GITHUB_RUN_ID") and env.get("GITHUB_REPOSITORY"):
        server = env.get("GITHUB_SERVER_URL", "https://github.com")
        return f"{server}/{env['GITHUB_REPOSITORY']}/actions/runs/{env['GITHUB_RUN_ID']}"
    return None


def artifact_link(name, url, env=None):
    """A markdown link to an uploaded artifact, or to the run that holds it, or plain text outside CI."""
    target = url or run_url(env)
    return f"[{name}]({target})" if target else f"`{name}` artifact"


def table(header, rows, align=None):
    align = align or ["---"] * len(header)
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join(align) + "|"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


# --------------------------------------------------------------- coverage


def workspace_module_of(filename):
    """The build module owning a source file named in scoverage.xml.

    scoverage writes filenames relative to the matching source root (`atn/mill/Foo.scala`), so the module is found by
    probing `<module>/src/<filename>`; a filename that already starts with the module directory is accepted as-is.
    """
    path = Path(filename)
    if path.is_absolute():
        try:
            path = path.relative_to(Path.cwd())
        except ValueError:
            return None
    first = path.parts[0] if path.parts else None
    if first and (Path(first) / "src").is_dir() and Path(filename).exists():
        return first
    for candidate in sorted(p for p in Path(".").iterdir() if (p / "src").is_dir()):
        if (candidate / "src" / path).is_file():
            return candidate.name
    return None


def parse_coverage(xml_text, module_of=workspace_module_of):
    """Overall rates, per-package rates and per-module statement/branch counts from a scoverage.xml document."""
    root = ET.fromstring(xml_text)
    packages = []
    modules = {}
    for pkg in root.iter("package"):
        packages.append(
            {
                "name": pkg.get("name", ""),
                "statement_rate": float_or_none(pkg.get("statement-rate")),
                "branch_rate": float_or_none(pkg.get("branch-rate")),
            }
        )
        for cls in pkg.iter("class"):
            module = module_of(cls.get("filename", ""))
            if module is None:
                continue
            counts = modules.setdefault(module, Counter())
            counts["statements"] += int(cls.get("statement-count", 0))
            counts["statements_invoked"] += int(cls.get("statements-invoked", 0))
            for statement in cls.iter("statement"):
                if statement.get("branch") == "true":
                    counts["branches"] += 1
                    counts["branches_invoked"] += int(int(statement.get("invocation-count", 0)) > 0)
    return {
        "statement_rate": float_or_none(root.get("statement-rate")),
        "branch_rate": float_or_none(root.get("branch-rate")),
        "packages": packages,
        "modules": modules,
    }


def coverage_markdown(coverage, artifact_url=None):
    lines = [
        "### Coverage",
        "",
        f"**Statements: {fmt_rate(coverage['statement_rate'])}** · **Branches: {fmt_rate(coverage['branch_rate'])}**"
        f" · {artifact_link('coverage-html', artifact_url)}",
        "",
    ]
    modules = coverage["modules"]
    if modules:
        rows = [
            (
                name,
                f"{fmt_rate(rate(c['statements_invoked'], c['statements']))} ({c['statements_invoked']}/{c['statements']})",
                f"{fmt_rate(rate(c['branches_invoked'], c['branches']))} ({c['branches_invoked']}/{c['branches']})",
            )
            for name, c in sorted(modules.items())
        ]
        lines += [table(("Module", "Statements", "Branches"), rows, ["---", "---:", "---:"]), ""]
    rows = [(p["name"], fmt_rate(p["statement_rate"]), fmt_rate(p["branch_rate"])) for p in coverage["packages"]]
    lines += [
        "<details><summary>Per package</summary>",
        "",
        table(("Package", "Statements", "Branches"), rows, ["---", "---:", "---:"]),
        "",
        "</details>",
    ]
    return "\n".join(lines)


def cmd_coverage(args):
    if not COVERAGE_XML.is_file():
        emit(f"### Coverage\n\nCoverage report not found (`{COVERAGE_XML}`).")
        return 0
    emit(coverage_markdown(parse_coverage(COVERAGE_XML.read_text(encoding="utf-8")), args.artifact_url))
    return 0


# --------------------------------------------------------------- mutation


def mutation_counts(report):
    """Mutant status counts from a mutation-testing-elements `report.json`."""
    counts = Counter()
    for source in report.get("files", {}).values():
        for mutant in source.get("mutants", []):
            counts[mutant.get("status", "Unknown")] += 1
    return counts


def mutation_score(counts):
    """(Killed + Timeout) / (Killed + Timeout + Survived + NoCoverage), the standard mutation score."""
    detected = sum(counts[s] for s in DETECTED)
    return rate(detected, detected + sum(counts[s] for s in UNDETECTED))


def newest_report(module):
    reports = Path("out", module, "strykerMutate.dest", "target", "stryker4s-report").glob("*/report.json")
    reports = sorted(reports, key=lambda p: p.stat().st_mtime)
    return reports[-1] if reports else None


def mutation_markdown(module_counts, artifact_url=None):
    """`module_counts` maps each module to its status Counter, or to None when it has no report."""
    if not module_counts:
        return "### Mutation testing\n\nNo mutation-tested module changed in this PR."
    rows = []
    errors = []
    for module, counts in module_counts.items():
        if counts is None:
            rows.append((module, "no report", "–", "–", "–", "–", "–"))
            continue
        killed = str(counts["Killed"]) + (f" (+{counts['Timeout']} timeout)" if counts["Timeout"] else "")
        rows.append(
            (
                module,
                fmt_rate(mutation_score(counts)),
                killed,
                counts["Survived"],
                counts["NoCoverage"],
                counts["Ignored"],
                artifact_link("mutation-html", artifact_url),
            )
        )
        if counts["CompileError"] or counts["RuntimeError"]:
            errors.append(f"{module}: {counts['CompileError']} compile error(s), {counts['RuntimeError']} runtime error(s)")
    header = ("Module", "Score", "Killed", "Survived", "No coverage", "Ignored", "Report")
    lines = [
        "### Mutation testing",
        "",
        "Mutation score of every module with `.scala` changes in this PR:"
        " (killed + timeout) / (killed + timeout + survived + no coverage)."
        " Ignored mutants are excluded by configuration and do not count.",
        "",
        table(header, rows, ["---", "---:", "---:", "---:", "---:", "---:", "---"]),
    ]
    if errors:
        lines += ["", "Mutants that did not compile or run (not counted): " + "; ".join(errors) + "."]
    return "\n".join(lines)


def cmd_mutation(args):
    module_counts = {}
    for module in args.modules:
        report = newest_report(module)
        module_counts[module] = (
            mutation_counts(json.loads(report.read_text(encoding="utf-8"))) if report else None
        )
    emit(mutation_markdown(module_counts, args.artifact_url))
    return 0


# -------------------------------------------------------------------- cpd


def parse_cpd(csv_text):
    """Rows of a PMD CPD csv report: `lines,tokens,occurrences` then `(line,file)` pairs per occurrence."""
    rows = []
    for record in csv.reader(io.StringIO(csv_text)):
        if not record or not record[0].strip().isdigit():
            continue
        rows.append(
            {
                "lines": int(record[0]),
                "tokens": int(record[1]),
                "occurrences": int(record[2]),
                "line": record[3] if len(record) > 4 else "",
                "file": record[4] if len(record) > 4 else "",
            }
        )
    return rows


def cpd_markdown(reports, warn, error):
    """`reports` maps a language to its parsed rows."""
    summary = [
        (language, sum(r["tokens"] >= warn for r in rows), sum(r["tokens"] >= error for r in rows))
        for language, rows in sorted(reports.items())
    ]
    lines = [
        "### Copy-paste detection",
        "",
        table(("Language", f"≥ {warn} tokens (warn)", f"≥ {error} tokens (error)"), summary, ["---", "---:", "---:"]),
    ]
    top = sorted(
        ((language, r) for language, rows in reports.items() for r in rows), key=lambda x: -x[1]["tokens"]
    )[:5]
    if top:
        rows = [
            (language, r["tokens"], r["lines"], r["occurrences"], f"`{r['file']}:{r['line']}`" if r["file"] else "")
            for language, r in top
        ]
        lines += [
            "",
            "Largest duplications:",
            "",
            table(("Language", "Tokens", "Lines", "Occurrences", "First location"), rows, ["---", "---:", "---:", "---:", "---"]),
        ]
    return "\n".join(lines)


def cmd_cpd(args):
    report_dir = Path(args.report_dir)
    if not report_dir.is_dir():
        emit("### Copy-paste detection\n\nCPD not enabled in CI yet (arrives once `mill-cpd` is released and dogfooded).")
        return 0
    reports = {
        path.stem[len("cpd-"):]: parse_cpd(path.read_text(encoding="utf-8")) for path in sorted(report_dir.glob("cpd-*.csv"))
    }
    emit(cpd_markdown(reports, args.warn, args.error))
    return 0


# ------------------------------------------------------------------- post


def parse_sections(body):
    return {name: content.strip() for name, content in SECTION_RE.findall(body or "")}


def render_body(sections, sha):
    ordered = [n for n in SECTION_ORDER if n in sections] + [n for n in sections if n not in SECTION_ORDER]
    parts = [MARKER, "## Quality report", "", f"Commit: {sha}", ""]
    for name in ordered:
        parts += [f"<!-- section:{name} -->", sections[name], f"<!-- /section:{name} -->", ""]
    return "\n".join(parts).rstrip("\n") + "\n"


def upsert_section(body, name, content, sha):
    """Replace (or append) one section of the sticky comment; the header and commit line are regenerated."""
    sections = parse_sections(body)
    sections[name] = content.strip()
    return render_body(sections, sha)


def gh(*args, stdin=None):
    return subprocess.run(["gh", *args], input=stdin, capture_output=True, text=True, check=True).stdout


def parse_json_stream(text):
    """`gh api --paginate` concatenates one JSON document per page; flatten them into one list."""
    decoder = json.JSONDecoder()
    pos, items = 0, []
    while True:
        while pos < len(text) and text[pos].isspace():
            pos += 1
        if pos >= len(text):
            return items
        doc, pos = decoder.raw_decode(text, pos)
        items.extend(doc if isinstance(doc, list) else [doc])


def cmd_post(args):
    content = sys.stdin.read()
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("PR_HEAD_SHA") or os.environ.get("GITHUB_SHA") or "unknown"
    if not repo:
        print("::warning::GITHUB_REPOSITORY is not set; nothing posted", file=sys.stderr)
        return 0
    try:
        comments = parse_json_stream(gh("api", f"repos/{repo}/issues/{args.pr}/comments", "--paginate"))
        existing = next((c for c in comments if str(c.get("body", "")).startswith(MARKER)), None)
        body = upsert_section(existing["body"] if existing else None, args.section, content, sha)
        payload = json.dumps({"body": body})
        if existing:
            gh("api", "-X", "PATCH", f"repos/{repo}/issues/comments/{existing['id']}", "--input", "-", stdin=payload)
        else:
            gh("api", "-X", "POST", f"repos/{repo}/issues/{args.pr}/comments", "--input", "-", stdin=payload)
    except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as exc:
        detail = (getattr(exc, "stderr", None) or str(exc)).strip()
        print(f"::warning::could not post the {args.section} section to PR #{args.pr}: {detail}", file=sys.stderr)
        return 0
    print(f"posted the {args.section} section to PR #{args.pr}", file=sys.stderr)
    return 0


# --------------------------------------------------------------- selftest

COVERAGE_FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<scoverage statement-count="10" statements-invoked="7" statement-rate="70.00" branch-rate="50.00" version="1.0">
  <packages>
    <package name="atn.mill" statement-count="10" statements-invoked="7" statement-rate="70.00" branch-rate="50.00">
      <classes>
        <class name="atn.mill.A" filename="atn/mill/A.scala" statement-count="6" statements-invoked="6"
               statement-rate="100.00" branch-rate="100.00">
          <methods><method name="a"><statements>
            <statement branch="true" invocation-count="2"/>
            <statement branch="false" invocation-count="1"/>
          </statements></method></methods>
        </class>
        <class name="atn.mill.B" filename="atn/mill/B.scala" statement-count="4" statements-invoked="1"
               statement-rate="25.00" branch-rate="0.00">
          <methods><method name="b"><statements>
            <statement branch="true" invocation-count="0"/>
            <statement branch="true" invocation-count="0"/>
            <statement branch="false" invocation-count="1"/>
          </statements></method></methods>
        </class>
      </classes>
    </package>
  </packages>
</scoverage>
"""

MUTATION_FIXTURE = {
    "schemaVersion": "1",
    "files": {
        "a/src/A.scala": {
            "mutants": [{"status": s} for s in ["Killed"] * 6 + ["Timeout", "Survived", "Survived", "NoCoverage"]]
        },
        "a/src/B.scala": {"mutants": [{"status": "CompileError"}, {"status": "Ignored"}, {"status": "RuntimeError"}]},
    },
}

CPD_FIXTURE = """lines,tokens,occurrences
12,80,2,10,/ws/a/src/A.scala,40,/ws/b/src/B.scala
5,30,3,1,/ws/a/src/C.scala,7,/ws/a/src/D.scala,9,/ws/a/src/E.scala
3,20,2,3,/ws/a/src/F.scala,5,/ws/a/src/G.scala
"""


def check(condition, message):
    if not condition:
        raise SystemExit(f"selftest failed: {message}")


def cmd_selftest(_args):
    modules = {"atn/mill/A.scala": "alpha", "atn/mill/B.scala": "beta"}
    coverage = parse_coverage(COVERAGE_FIXTURE, module_of=modules.get)
    check(coverage["statement_rate"] == 70.0 and coverage["branch_rate"] == 50.0, "overall coverage rates")
    check([p["name"] for p in coverage["packages"]] == ["atn.mill"], "package names")
    check(coverage["packages"][0]["statement_rate"] == 70.0, "package statement rate")
    check(coverage["modules"]["alpha"] == Counter(statements=6, statements_invoked=6, branches=1, branches_invoked=1), "alpha")
    check(coverage["modules"]["beta"] == Counter(statements=4, statements_invoked=1, branches=2, branches_invoked=0), "beta")
    md = coverage_markdown(coverage, "https://example.test/coverage")
    check("| alpha | 100.0% (6/6) | 100.0% (1/1) |" in md, "module row")
    check("| beta | 25.0% (1/4) | 0.0% (0/2) |" in md, "module row with uncovered branches")
    check("| atn.mill | 70.0% | 50.0% |" in md, "package row")
    check("[coverage-html](https://example.test/coverage)" in md, "explicit artifact link")

    ci_env = {"GITHUB_RUN_ID": "7", "GITHUB_REPOSITORY": "x/y", "GITHUB_SERVER_URL": "https://gh.test"}
    check(artifact_link("coverage-html", None, env={}) == "`coverage-html` artifact", "artifact text without CI env")
    check(artifact_link("coverage-html", None, env=ci_env) == "[coverage-html](https://gh.test/x/y/actions/runs/7)", "run link")
    check(run_url({"GITHUB_RUN_ID": "7", "GITHUB_REPOSITORY": "x/y"}) == "https://github.com/x/y/actions/runs/7", "default host")
    check(artifact_link("n", "https://a/b", env=ci_env) == "[n](https://a/b)", "explicit artifact url wins over the run url")

    counts = mutation_counts(MUTATION_FIXTURE)
    check(counts["Killed"] == 6 and counts["Survived"] == 2 and counts["CompileError"] == 1, "mutant counts")
    check(mutation_score(counts) == 70.0, f"mutation score {mutation_score(counts)}")
    check(mutation_score(Counter()) is None, "score without mutants")
    md = mutation_markdown({"devx": counts, "cpd": None}, "https://example.test/artifact")
    check("| devx | 70.0% | 6 (+1 timeout) | 2 | 1 | 1 | [mutation-html](https://example.test/artifact) |" in md, "row")
    check("devx: 1 compile error(s), 1 runtime error(s)" in md, "error footnote")
    check("| cpd | no report |" in md, "missing report row")
    check("| m | 75.0% | 3 | 1 | 0 | 0 |" in mutation_markdown({"m": Counter(Killed=3, Survived=1)}), "plain killed count")
    check("No mutation-tested module changed in this PR." in mutation_markdown({}), "empty module list")

    rows = parse_cpd(CPD_FIXTURE)
    check([r["tokens"] for r in rows] == [80, 30, 20], "cpd rows")
    check(rows[0]["file"] == "/ws/a/src/A.scala" and rows[0]["line"] == "10", "cpd first location")
    md = cpd_markdown({"scala": rows}, warn=25, error=75)
    check("| scala | 2 | 1 |" in md, "cpd threshold counts")
    check(md.index("`/ws/a/src/A.scala:10`") < md.index("`/ws/a/src/C.scala:1`"), "top rows sorted by tokens")

    body = upsert_section(None, "coverage", "### Coverage\n\nstuff", "sha1")
    check(body.startswith(MARKER + "\n## Quality report\n\nCommit: sha1\n"), "new comment header")
    check(parse_sections(body) == {"coverage": "### Coverage\n\nstuff"}, "new comment section")
    body = upsert_section(body, "mutation", "### Mutation\n\nmore", "sha2")
    check(parse_sections(body) == {"coverage": "### Coverage\n\nstuff", "mutation": "### Mutation\n\nmore"}, "appended")
    check("Commit: sha2" in body and "sha1" not in body, "commit line replaced")
    body = upsert_section(body, "coverage", "### Coverage\n\nnew", "sha3")
    check(parse_sections(body) == {"coverage": "### Coverage\n\nnew", "mutation": "### Mutation\n\nmore"}, "replaced")
    check(body.index("section:coverage") < body.index("section:mutation"), "section order is stable")
    check(body.count(MARKER) == 1, "single marker")
    check(parse_json_stream('[{"id": 1}]\n[{"id": 2}]') == [{"id": 1}, {"id": 2}], "paginated json stream")
    print("selftest ok")
    return 0


# ------------------------------------------------------------------- main


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("coverage", help="scoverage summary from out/scoverage/xmlReportAll.dest/scoverage.xml")
    p.add_argument("--artifact-url", help="URL of the uploaded coverage-html artifact")
    p.set_defaults(func=cmd_coverage)

    p = sub.add_parser("mutation", help="mutation score per module from the newest stryker4s report.json")
    p.add_argument("modules", nargs="*", help="modules whose newest strykerMutate report to summarise")
    p.add_argument("--artifact-url", help="URL of the uploaded mutation-html artifact")
    p.set_defaults(func=cmd_mutation)

    p = sub.add_parser("cpd", help="CPD summary from <report-dir>/cpd-*.csv")
    p.add_argument("--report-dir", default=str(CPD_DIR), help=f"cpdCheckAll dest dir (default {CPD_DIR})")
    p.add_argument("--warn", type=int, default=25, help="warning token threshold (default 25)")
    p.add_argument("--error", type=int, default=75, help="error token threshold (default 75)")
    p.set_defaults(func=cmd_cpd)

    p = sub.add_parser("post", help="upsert the section read from stdin into the sticky PR comment")
    p.add_argument("--section", required=True, help="section name, e.g. coverage")
    p.add_argument("--pr", required=True, type=int, help="pull request number")
    p.set_defaults(func=cmd_post)

    p = sub.add_parser("selftest", help="check the parsers and the comment upsert on inline fixtures")
    p.set_defaults(func=cmd_selftest)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
