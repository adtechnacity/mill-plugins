#!/usr/bin/env python3
"""Quality report for pull requests: coverage, mutation score and CPD, posted as one sticky PR comment.

Python 3 standard library only (runs on ubuntu-latest without pip).

    quality-report.py coverage [--artifact-url URL]
    quality-report.py mutation [MODULE ...] [--scope MODULE=FILE,... ...] [--artifact-url URL]
    quality-report.py cpd [--report-dir DIR] [--languages LANG ...] [--warn N] [--error N]
    quality-report.py post --section NAME --pr NUMBER     (section markdown on stdin)
    quality-report.py selftest

Every report subcommand prints a markdown section to stdout and appends it to $GITHUB_STEP_SUMMARY when set. A section
spells out its empty state (nothing scanned, nothing changed, report missing) instead of rendering an empty table.
`post` upserts one section into the single PR comment that starts with the marker below. Jobs post their sections
concurrently, so `post` merges onto the comment as it is right before writing, then re-reads it and retries when a
concurrent write dropped the section (see `post_section`).
"""

import argparse
import csv
import io
import json
import os
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

MARKER = "<!-- quality-report -->"
SECTION_ORDER = ("coverage", "mutation", "cpd")
SECTION_RE = re.compile(r"<!-- section:([\w-]+) -->\n(.*?)\n?<!-- /section:\1 -->", re.S)
COVERAGE_XML = Path("out/scoverage/xmlReportAll.dest/scoverage.xml")
CPD_DIR = Path("out/duplication/cpdCheckAll.dest")
CPD_LANGUAGES = ("scala", "java")  # CpdSupport's default cpdLanguages; cpdCheckAll writes one cpd-<language>.csv each
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


def plural(count, noun):
    return f"{count} {noun}" + ("" if count == 1 else "s")


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


def parse_scopes(entries):
    """`MODULE=FILE,...` entries (the workflow's per-module STRYKER_INCLUDED_FILES) as {module: [files]}.

    `MODULE=` or a bare `MODULE` maps to an empty list: the module was mutated whole. Entries are split and trimmed the
    way build.mill's strykerIncludedFiles override reads the variable.
    """
    scopes = {}
    for entry in entries:
        module, _, files = entry.partition("=")
        scopes[module] = [f.strip() for f in files.split(",") if f.strip()]
    return scopes


def mutation_markdown(module_counts, artifact_url=None, scopes=None):
    """`module_counts` maps each module to its status Counter, or to None when it has no report.

    `scopes` maps a module to the changed production sources its run was restricted to; a module without an entry, or
    with an empty list, was mutated whole (what strykerIncludedFiles does when STRYKER_INCLUDED_FILES is empty).
    """
    scopes = scopes or {}
    if not module_counts:
        return (
            "### Mutation testing\n\n"
            "No mutation-tested module changed in this PR: none of its changed files lies under the `src`, `test` or"
            " `resources` tree of a module with a `strykerMutate` task, so nothing was mutated."
        )
    rows = []
    errors = []
    unreported = []
    scoped = []
    for module, counts in module_counts.items():
        files = scopes.get(module) or []
        scope = plural(len(files), "file") if files else "whole module"
        if files:
            scoped.append(f"`{module}`: " + ", ".join(f"`{f}`" for f in files))
        if counts is None:
            rows.append((module, scope, "no report", "–", "–", "–", "–", "–"))
            unreported.append(f"`{module}`")
            continue
        killed = str(counts["Killed"]) + (f" (+{counts['Timeout']} timeout)" if counts["Timeout"] else "")
        rows.append(
            (
                module,
                scope,
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
    header = ("Module", "Scope", "Score", "Killed", "Survived", "No coverage", "Ignored", "Report")
    lines = [
        "### Mutation testing",
        "",
        "Mutation score of every module whose sources, tests or resources changed in this PR:"
        " (killed + timeout) / (killed + timeout + survived + no coverage)."
        " Ignored mutants are excluded by configuration and do not count."
        " A module is mutated only in its changed `.scala` files under `<module>/src`; one whose changes lie elsewhere"
        " (tests, resources) is mutated whole.",
        "",
        table(header, rows, ["---", "---", "---:", "---:", "---:", "---:", "---:", "---"]),
    ]
    if scoped:
        lines += ["", "Mutated files: " + "; ".join(scoped) + "."]
    if errors:
        lines += ["", "Mutants that did not compile or run (not counted): " + "; ".join(errors) + "."]
    if unreported:
        lines += [
            "",
            f"No report for {', '.join(unreported)}: the module had no changes in this PR, or its stryker4s run"
            " was skipped or failed before writing `report.json` (see the mutation job log).",
        ]
    return "\n".join(lines)


def cmd_mutation(args):
    module_counts = {}
    for module in args.modules:
        report = newest_report(module)
        module_counts[module] = (
            mutation_counts(json.loads(report.read_text(encoding="utf-8"))) if report else None
        )
    emit(mutation_markdown(module_counts, args.artifact_url, parse_scopes(args.scope)))
    return 0


# -------------------------------------------------------------------- cpd


def parse_cpd(csv_text):
    """Rows of a PMD CPD csv report: `lines,tokens,occurrences` then `(line,file)` pairs per occurrence.

    `line`/`file` are the first occurrence, `files` every occurrence's file.
    """
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
                "files": tuple(record[4::2]),
            }
        )
    return rows


def display_path(path):
    """A path relative to the working directory when it lies below it (PMD reports absolute paths)."""
    try:
        return str(Path(path).relative_to(Path.cwd()))
    except ValueError:
        return path


def cpd_markdown(reports, warn, error, expected=CPD_LANGUAGES, report_dir=CPD_DIR):
    """`reports` maps each scanned language to its parsed rows; `expected` lists the languages that must have a report.

    The section always opens with a sentence naming what was scanned, the thresholds and the totals, so a clean run
    reads as "no duplications found" rather than as a table of zeros; the tables only follow when there is a row.
    """
    lines = ["### Copy-paste detection", ""]
    if not reports:
        expected_csvs = ", ".join(f"`cpd-{language}.csv`" for language in expected) or "`cpd-<language>.csv`"
        lines.append(
            f"No CPD report found in `{report_dir}` (expected {expected_csvs}): the CPD step did not run, or failed"
            " before writing its reports."
        )
        return "\n".join(lines)
    languages = [language for language in expected if language in reports]
    languages += sorted(language for language in reports if language not in expected)
    found = [(language, r) for language in languages for r in reports[language]]
    scanned = (
        f"Scanned {plural(len(languages), 'language')} ({', '.join(languages)})"
        f" at ≥ {warn} tokens (warning) / ≥ {error} tokens (error)"
    )
    if found:
        files = {path for _, r in found for path in r["files"]}
        at_error = sum(r["tokens"] >= error for _, r in found)
        lines.append(
            f"{scanned}: **{plural(len(found), 'duplication')}** in {plural(len(files), 'file')},"
            f" {at_error or 'none'} at the error tier."
        )
    else:
        lines.append(f"{scanned}: **no duplications found**.")
    missing = [language for language in expected if language not in reports]
    if missing:
        csvs = ", ".join(f"`cpd-{language}.csv`" for language in missing)
        lines += [
            "",
            f"Missing report for {', '.join(missing)} ({csvs} not in `{report_dir}`): CPD did not run to completion for"
            f" {'it' if len(missing) == 1 else 'them'}.",
        ]
    if not found:
        return "\n".join(lines)
    summary = [
        (language, sum(r["tokens"] >= warn for r in reports[language]), sum(r["tokens"] >= error for r in reports[language]))
        for language in languages
    ]
    top = [
        (
            language,
            r["tokens"],
            r["lines"],
            r["occurrences"],
            f"`{display_path(r['file'])}:{r['line']}`" if r["file"] else "",
        )
        for language, r in sorted(found, key=lambda x: -x[1]["tokens"])[:5]
    ]
    lines += [
        "",
        table(("Language", f"≥ {warn} tokens (warn)", f"≥ {error} tokens (error)"), summary, ["---", "---:", "---:"]),
        "",
        "Largest duplications:",
        "",
        table(("Language", "Tokens", "Lines", "Occurrences", "First location"), top, ["---", "---:", "---:", "---:", "---"]),
    ]
    return "\n".join(lines)


def cmd_cpd(args):
    report_dir = Path(args.report_dir)
    paths = sorted(report_dir.glob("cpd-*.csv")) if report_dir.is_dir() else []
    reports = {path.stem[len("cpd-"):]: parse_cpd(path.read_text(encoding="utf-8")) for path in paths}
    emit(cpd_markdown(reports, args.warn, args.error, args.languages, args.report_dir))
    return 0


# ------------------------------------------------------------------- post


def parse_sections(body):
    return {name: content.strip() for name, content in SECTION_RE.findall((body or "").replace("\r\n", "\n"))}


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


class CommentApi:
    """The sticky-comment calls on one pull request, through `gh api`; selftest substitutes an in-memory fake."""

    def __init__(self, repo, pr):
        self.repo, self.pr = repo, pr

    def fetch(self):
        """The oldest comment starting with MARKER (the canonical sticky comment), or None."""
        comments = parse_json_stream(gh("api", f"repos/{self.repo}/issues/{self.pr}/comments", "--paginate"))
        sticky = [c for c in comments if str(c.get("body", "")).startswith(MARKER)]
        return min(sticky, key=lambda c: c["id"], default=None)

    def create(self, body):
        payload = json.dumps({"body": body})
        return json.loads(gh("api", "-X", "POST", f"repos/{self.repo}/issues/{self.pr}/comments", "--input", "-", stdin=payload))

    def update(self, comment_id, body):
        payload = json.dumps({"body": body})
        gh("api", "-X", "PATCH", f"repos/{self.repo}/issues/comments/{comment_id}", "--input", "-", stdin=payload)

    def delete(self, comment_id):
        gh("api", "-X", "DELETE", f"repos/{self.repo}/issues/comments/{comment_id}")


def random_sleep():
    time.sleep(random.uniform(0.5, 2.0))


def post_section(api, section, content, sha, attempts=5, sleep=random_sleep, log=lambda message: None):
    """Merge one section into the sticky comment and make sure it lands despite concurrent posts from other jobs.

    The build and mutation jobs post different sections at the same time, each with a read-modify-write of the same
    comment. Every attempt therefore fetches the comment and merges onto that body right before writing, then fetches
    again and checks that the section is present with the content it wrote. A job that fetched before our write and
    wrote after it drops our section (a lost update); the check catches that and the next attempt merges onto the
    other job's body, which keeps its section. Two jobs creating the comment at once leave two comments: the oldest is
    canonical, so a duplicate of ours is deleted and the section merged into the older one on the next attempt.
    Returns True once verified, False after `attempts` failed rounds.
    """
    want = content.strip()
    for attempt in range(1, attempts + 1):
        existing = api.fetch()
        body = upsert_section(existing["body"] if existing else None, section, want, sha)
        created = None
        if existing:
            api.update(existing["id"], body)
        else:
            created = api.create(body)
        after = api.fetch()
        if created and after and after["id"] != created["id"]:
            api.delete(created["id"])
            log(f"attempt {attempt}: another job created the comment first; merging the {section} section into it")
        elif after and parse_sections(after["body"]).get(section) == want:
            return True
        else:
            log(f"attempt {attempt}: the {section} section was overwritten by a concurrent update; retrying")
        sleep()
    return False


def cmd_post(args):
    content = sys.stdin.read()
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("PR_HEAD_SHA") or os.environ.get("GITHUB_SHA") or "unknown"
    if not repo:
        print("::warning::GITHUB_REPOSITORY is not set; nothing posted", file=sys.stderr)
        return 0
    try:
        api = CommentApi(repo, args.pr)
        verified = post_section(api, args.section, content, sha, log=lambda message: print(message, file=sys.stderr))
    except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as exc:
        detail = (getattr(exc, "stderr", None) or str(exc)).strip()
        print(f"::warning::could not post the {args.section} section to PR #{args.pr}: {detail}", file=sys.stderr)
        return 0
    if not verified:
        print(f"::warning::the {args.section} section did not land on PR #{args.pr}; giving up", file=sys.stderr)
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


class FakeComments:
    """In-memory stand-in for CommentApi.

    `before_write` and `after_write` are queues of callables; each write pops and runs one of each, which is how a
    test slips another job's request in exactly where a real race would put it.
    """

    def __init__(self):
        self.comments, self.next_id, self.writes = {}, 1, 0
        self.before_write, self.after_write = [], []

    def _around(self, write):
        if self.before_write:
            self.before_write.pop(0)()
        write()
        self.writes += 1
        if self.after_write:
            self.after_write.pop(0)()

    def fetch(self):
        sticky = [dict(c) for c in self.comments.values() if c["body"].startswith(MARKER)]
        return min(sticky, key=lambda c: c["id"], default=None)

    def create(self, body):
        created = {}

        def write():
            created.update(id=self.next_id, body=body)
            self.next_id += 1
            self.comments[created["id"]] = dict(created)

        self._around(write)
        return dict(created)

    def update(self, comment_id, body):
        self._around(lambda: self.comments[comment_id].update(body=body))

    def delete(self, comment_id):
        del self.comments[comment_id]


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
    check("| devx | whole module | 70.0% | 6 (+1 timeout) | 2 | 1 | 1 | [mutation-html](https://example.test/artifact) |" in md, "row")
    check("devx: 1 compile error(s), 1 runtime error(s)" in md, "error footnote")
    check("| cpd | whole module | no report |" in md, "missing report row")
    check("No report for `cpd`: the module had no changes in this PR, or" in md, "missing report explained")
    check("Mutated files:" not in md, "no file list when every module was mutated whole")
    md = mutation_markdown({"m": Counter(Killed=3, Survived=1)})
    check("| m | whole module | 75.0% | 3 | 1 | 0 | 0 |" in md, "plain killed count")

    scopes = parse_scopes(["devx=devx/src/atn/mill/A.scala, devx/src/atn/mill/B.scala,", "cpd=", "docs"])
    check(scopes == {"devx": ["devx/src/atn/mill/A.scala", "devx/src/atn/mill/B.scala"], "cpd": [], "docs": []}, f"scopes {scopes}")
    md = mutation_markdown({"devx": counts, "cpd": None, "m": Counter(Killed=1)}, scopes=scopes)
    check("| devx | 2 files | 70.0% | 6 (+1 timeout) |" in md, "file-scoped row")
    check("| cpd | whole module | no report |" in md, "whole-module row for a test-only change")
    check("| m | whole module | 100.0% |" in md, "module absent from --scope defaults to whole module")
    check("Mutated files: `devx`: `devx/src/atn/mill/A.scala`, `devx/src/atn/mill/B.scala`." in md, "file list")
    md = mutation_markdown({})
    check("No mutation-tested module changed in this PR: none of its changed files lies under" in md, "empty module list")
    check("|" not in md and "No report for" not in md, "empty module list renders neither a table nor a missing-report note")

    rows = parse_cpd(CPD_FIXTURE)
    check([r["tokens"] for r in rows] == [80, 30, 20], "cpd rows")
    check(rows[0]["file"] == "/ws/a/src/A.scala" and rows[0]["line"] == "10", "cpd first location")
    check(rows[1]["files"] == ("/ws/a/src/C.scala", "/ws/a/src/D.scala", "/ws/a/src/E.scala"), "cpd all occurrence files")
    md = cpd_markdown({"java": [], "scala": rows}, warn=25, error=75)
    check(
        "Scanned 2 languages (scala, java) at ≥ 25 tokens (warning) / ≥ 75 tokens (error):"
        " **3 duplications** in 7 files, 1 at the error tier." in md,
        "cpd summary sentence",
    )
    check("| scala | 2 | 1 |" in md and "| java | 0 | 0 |" in md, "cpd threshold counts")
    check(md.index("`/ws/a/src/A.scala:10`") < md.index("`/ws/a/src/C.scala:1`"), "top rows sorted by tokens")
    check("none at the error tier" in cpd_markdown({"scala": rows[1:]}, warn=25, error=75), "no error-tier duplication")
    clean = cpd_markdown({"scala": [], "java": []}, warn=25, error=75)
    check(
        clean == "### Copy-paste detection\n\nScanned 2 languages (scala, java) at ≥ 25 tokens (warning)"
        " / ≥ 75 tokens (error): **no duplications found**.",
        f"clean cpd state is one sentence and no table:\n{clean}",
    )
    md = cpd_markdown({"scala": []}, warn=25, error=75, report_dir="out/x")
    check("Scanned 1 language (scala) at" in md, "singular language")
    check("Missing report for java (`cpd-java.csv` not in `out/x`): CPD did not run to completion for it." in md, "missing csv")
    md = cpd_markdown({}, warn=25, error=75, report_dir="out/x")
    check("No CPD report found in `out/x` (expected `cpd-scala.csv`, `cpd-java.csv`)" in md and "|" not in md, "no cpd reports")

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
    check(parse_sections("<!-- section:a -->\r\nx\r\n<!-- /section:a -->") == {"a": "x"}, "crlf body")

    # The merge is pure: the build job's cpd section and the mutation job's section, merged one after the other
    # in either order, both survive.
    cpd_md, mut_md, cov_md = "### CPD\n\nclean", "### Mutation\n\nnone", "### Coverage\n\nc"
    both = {"cpd": cpd_md, "mutation": mut_md}
    check(parse_sections(upsert_section(upsert_section(None, "cpd", cpd_md, "s1"), "mutation", mut_md, "s2")) == both, "merge b,m")
    check(parse_sections(upsert_section(upsert_section(None, "mutation", mut_md, "s1"), "cpd", cpd_md, "s2")) == both, "merge m,b")

    # No contention: one write, verified on the first attempt.
    api, log = FakeComments(), []
    check(post_section(api, "cpd", cpd_md, "sha-c", sleep=lambda: None, log=log.append), "clean post verified")
    check(api.writes == 1 and not log and parse_sections(api.fetch()["body"]) == {"cpd": cpd_md}, "clean post: one write")

    # Lost update: the mutation job fetched the comment before our PATCH, and its PATCH (merged onto that stale body)
    # lands right after ours, dropping the cpd section. The verify step notices and the retry merges onto the
    # mutation job's body, so both sections end up present.
    api, log = FakeComments(), []
    api.create(render_body({"coverage": cov_md}, "sha0"))
    stale = api.fetch()
    api.writes = 0  # the setup create above is not part of the race
    api.after_write.append(lambda: api.update(stale["id"], upsert_section(stale["body"], "mutation", mut_md, "sha-m")))
    check(post_section(api, "cpd", cpd_md, "sha-c", sleep=lambda: None, log=log.append), "post verified after a lost update")
    final = api.fetch()["body"]
    check(parse_sections(final) == {"coverage": cov_md, "mutation": mut_md, "cpd": cpd_md}, f"lost update repaired:\n{final}")
    check(api.writes == 3 and len(log) == 1 and "overwritten" in log[0], f"exactly one retry: writes={api.writes} log={log}")
    check("Commit: sha-c" in final and len(api.comments) == 1, "newest post's commit line wins, still one comment")

    # Create race: both jobs saw no comment and POSTed; the older comment is canonical, so our duplicate is deleted
    # and our section merged into the older one.
    api, log = FakeComments(), []
    api.before_write.append(lambda: api.create(render_body({"mutation": mut_md}, "sha-m")))
    check(post_section(api, "cpd", cpd_md, "sha-c", sleep=lambda: None, log=log.append), "post verified after a create race")
    check(len(api.comments) == 1 and parse_sections(api.fetch()["body"]) == both, "duplicate deleted, both sections kept")
    check(len(log) == 1 and "created the comment first" in log[0], f"create race logged once: {log}")

    # Every write clobbered: gives up after `attempts` rounds and reports it, leaving the comment intact.
    api, log = FakeComments(), []
    api.create(render_body({"coverage": cov_md}, "sha0"))

    def clobber():  # another job overwrites the comment behind our back, without our section
        api.comments[api.fetch()["id"]]["body"] = render_body({"coverage": cov_md}, "sha-x")

    api.after_write.extend([clobber] * 3)
    check(not post_section(api, "cpd", cpd_md, "sha-c", attempts=3, sleep=lambda: None, log=log.append), "gives up")
    check(len(log) == 3 and parse_sections(api.fetch()["body"]) == {"coverage": cov_md}, "bounded attempts")
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
    p.add_argument(
        "--scope",
        nargs="*",
        default=[],
        metavar="MODULE=FILE,...",
        help="the changed production sources a module's run was restricted to (its STRYKER_INCLUDED_FILES);"
        " a module without an entry, or with an empty list, was mutated whole",
    )
    p.add_argument("--artifact-url", help="URL of the uploaded mutation-html artifact")
    p.set_defaults(func=cmd_mutation)

    p = sub.add_parser("cpd", help="CPD summary from <report-dir>/cpd-*.csv")
    p.add_argument("--report-dir", default=str(CPD_DIR), help=f"cpdCheckAll dest dir (default {CPD_DIR})")
    p.add_argument(
        "--languages",
        nargs="*",
        default=list(CPD_LANGUAGES),
        metavar="LANG",
        help="languages cpdCheckAll scans, one cpd-<lang>.csv each (default: CpdSupport's cpdLanguages, scala java)",
    )
    p.add_argument("--warn", type=int, default=25, help="warning token threshold (default 25)")
    p.add_argument("--error", type=int, default=75, help="error token threshold (default 75)")
    p.set_defaults(func=cmd_cpd)

    p = sub.add_parser("post", help="upsert the section read from stdin into the sticky PR comment (verified, retried)")
    p.add_argument("--section", required=True, help="section name, e.g. coverage")
    p.add_argument("--pr", required=True, type=int, help="pull request number")
    p.set_defaults(func=cmd_post)

    p = sub.add_parser("selftest", help="check the parsers and the comment upsert on inline fixtures")
    p.set_defaults(func=cmd_selftest)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
