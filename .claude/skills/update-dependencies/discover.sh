#!/usr/bin/env bash
# Report every update the repo's version pins can take: Mill, Scala, JDK, library deps, meta-build plugins,
# version strings showUpdates cannot see, GitHub Actions, and any open Scala Steward PR. Read-only.
# Run from the repo root: bash .claude/skills/update-dependencies/discover.sh
set -uo pipefail
scratch=/var/tmp/update-deps
mkdir -p "$scratch/jtmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$scratch/jtmp"
mvn=https://repo1.maven.org/maven2

# Newest stable version of a Maven Central artifact path, optionally only versions starting with prefix $2.
# <release>/<latest> in maven-metadata.xml point at milestones and RCs (mill-dist says 2.0.0-M2), and the
# <version> list is in publish order, so filter and sort.
latest() {
  curl -fsS "$mvn/$1/maven-metadata.xml" | grep -oE '<version>[^<]+' | sed 's/<version>//' |
    grep -vE -- '-(M|RC|rc|alpha|beta)[0-9.]*|-[0-9]+-[0-9a-f]{6,}$|SNAPSHOT' | grep -F "${2:-}" | sort -V | tail -1
}

# Fetch a jar into the scratch dir; a failed download never leaves a stale copy behind.
fetch() { rm -f "$scratch/$1" && curl -fsSL -o "$scratch/$1" "$2"; }

# Scala, default JVM and bundled libraries of Mill release $1, read from its published jars and POMs.
mill_scala() {
  fetch mlu.jar "$mvn/com/lihaoyi/mill-libs-util_3/$1/mill-libs-util_3-$1.jar" &&
    unzip -p "$scratch/mlu.jar" mill/util/BuildInfo.buildinfo.properties | sed -n 's/^scalaVersion=//p'
}
mill_jvm() {
  fetch mcc.jar "$mvn/com/lihaoyi/mill-core-constants/$1/mill-core-constants-$1.jar" &&
    unzip -p "$scratch/mcc.jar" mill/constants/BuildInfo.buildinfo.properties | sed -n 's/^defaultJvmVersion=//p' | tr -d '\\'
}
mill_info() {
  echo "  Mill $1: scala ${2:-?}, default JVM ${3:-? (download failed)}"
  echo "  bundled: $(curl -fsS "$mvn/com/lihaoyi/mill-core-api_3/$1/mill-core-api_3-$1.pom" \
    "$mvn/com/lihaoyi/mill-libs-util_3/$1/mill-libs-util_3-$1.pom" |
    grep -A1 -E '<artifactId>(os-lib|upickle|mainargs|requests)_3<' | grep -oE '(artifactId|version)>[^<]+' |
    sed 's/.*>//' | paste - - | sort -u | tr '\t\n' ' ,' | sed 's/,$//')"
}

# showUpdates with the coursier metadata cache bypassed (it is cached 24 h, which hides releases from the last day).
show_updates() {
  local log="$scratch/showUpdates$1.log"
  shift
  if COURSIER_TTL=0s timeout 600 ./mill --no-daemon --ticker false "$@" mill.javalib.Dependency/showUpdates >"$log" 2>&1; then
    grep -E '^ +[^ ]+ : ' "$log" | sort -u | grep . || echo "  (no updates)"
  else
    echo "  (showUpdates FAILED, see $log)"
  fi
}

pin() { grep -ohE "$2" "$1" | head -1 | grep -oE '[0-9][0-9A-Za-z.-]*' | tail -1; }
row() { printf '  %-40s %-14s latest %s\n' "$1" "$2" "$3"; }

echo "== Mill"
cur=$(tr -d '[:space:]' <.mill-version)
new=$(latest com/lihaoyi/mill-dist)
echo "  .mill-version $cur, latest stable ${new:-? (lookup failed)}"
[ -n "$new" ] || new=$cur
cur_jvm=$(mill_jvm "$cur")
mill_info "$cur" "$(mill_scala "$cur")" "$cur_jvm"
target_jvm=$cur_jvm
target_scala=$(mill_scala "$new")
if [ "$new" != "$cur" ]; then
  target_jvm=$(mill_jvm "$new")
  mill_info "$new" "$target_scala" "$target_jvm"
fi
echo "  ./mill launcher fallback: $(grep -oE 'DEFAULT_MILL_VERSION=[^ ]+' mill | head -1)"

echo "== Scala (same minor as the target Mill's Scala $target_scala; newest patch of it, never a newer minor)"
echo "  build.mill: $(grep -oE 'def scalaVersion *= *"[^"]+"' build.mill | sort -u | tr '\n' ' ')"
minor=${target_scala%.*}
echo "  newest stable $minor.x: $(latest org/scala-lang/scala3-compiler_3 "$minor.")," \
  "newest stable overall: $(latest org/scala-lang/scala3-compiler_3)"
sfx=$(latest ch/epfl/scala/scalafix-interfaces)
if fetch sfx.jar "$mvn/ch/epfl/scala/scalafix-interfaces/$sfx/scalafix-interfaces-$sfx.jar"; then
  echo "  scalafix-interfaces $sfx maps consumer Scala minors to CLI builds:" \
    "$(unzip -p "$scratch/sfx.jar" scalafix-interfaces.properties | grep -E '^scala3' | tr '\n' ' ')"
fi

echo "== Java"
grep -rnE 'java-version|distribution:' .github/workflows githooks/docs/workflows | sed 's/^/  /'
echo "  mill-jvm-version: $( (cat .mill-jvm-version 2>/dev/null; grep -hE '^//\| *mill-jvm-version' build.mill) | head -1)"
echo "  ./mill runs on: $(./mill --version 2>/dev/null | sed -n 's/^java\.\(version\|vendor\): //p' | paste -sd' ')"
echo "  local java: $(java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1)"
ci_java=$(pin .github/workflows/ci.yml 'java-version: *[0-9]+')
jvm_major=$(grep -oE '[0-9]+' <<<"${target_jvm:-}" | head -1)
[ -n "$jvm_major" ] && [ "$ci_java" != "$jvm_major" ] &&
  echo "  MISMATCH: CI setup-java $ci_java, Mill runs $target_jvm (propose aligning; ask first)"

echo "== Library deps, root build (os-lib is derived from Mill: ignore it)"
show_updates root

echo "== Meta-build, mill-build/build.mill"
show_updates meta --meta-level 1

echo "== Version strings showUpdates cannot see"
row scoverageVersion "$(pin build.mill 'scoverageVersion *= *"[^"]+"')" "$(latest org/scoverage/scalac-scoverage-reporter_3)"
row "scalafmt (.scalafmt.conf)" "$(pin .scalafmt.conf '^version *= *.*')" "$(latest org/scalameta/scalafmt-core_2.13)"
row "pmdVersion (cpd default)" "$(pin cpd/src/atn/mill/CpdSupport.scala 'pmdVersion: String = "[^"]+"')" \
  "$(latest net/sourceforge/pmd/pmd-cli)"
row "pulumiSdkVersion (pulumi default)" "$(pin pulumi/src/atn/mill/PulumiModule.scala 'pulumiSdkVersion: String = "[^"]+"')" \
  "$(latest com/pulumi/pulumi)"
row "stryker4s fallback (Stryker4sMillRunner)" \
  "$(pin stryker4s/src/atn/mill/Stryker4sMillRunner.scala 'getOrElse\("[0-9][^"]*"\)')" \
  "$(pin build.mill 'stryker4s-core:[0-9.]+') (= stryker4s-core pin)"
echo "  fs2: stryker4s-core $(pin build.mill 'stryker4s-core:[0-9.]+') brings" \
  "$(curl -fsS "$mvn/io/stryker-mutator/stryker4s-core_3/$(pin build.mill 'stryker4s-core:[0-9.]+')/stryker4s-core_3-$(pin build.mill 'stryker4s-core:[0-9.]+').pom" |
    grep -A1 -E '<artifactId>fs2-(core|io)_3<' | grep -oE '(artifactId|version)>[^<]+' | sed 's/.*>//' | paste - - | tr '\t\n' ' ,')" \
  "build.mill pins fs2-core $(pin build.mill 'fs2-core:[0-9.]+')"
echo "  other string defaults in plugin sources (check by hand):"
grep -rnE '^[^*]*def [A-Za-z]*[Vv]ersion[^=]*= *"[0-9]' --include=*.scala ./*/src |
  grep -vE 'pmdVersion|pulumiSdkVersion' | sed 's/^/    /' | grep . || echo "    (none)"

echo "== GitHub Actions"
grep -rhoE 'uses: *[^@ ]+@v[0-9]+' .github githooks/docs/workflows | sort -u | while read -r _ ref; do
  repo=${ref%@*}
  row "$repo" "${ref#*@}" "$(git ls-remote --tags --refs "https://github.com/$repo.git" |
    sed 's#.*refs/tags/##' | grep -E '^v[0-9]+$' | sort -V | tail -1)"
done

echo "== Open Scala Steward PR"
if prs=$(gh pr list --state open --search 'Update for group all' 2>&1); then
  echo "${prs:-(none)}" | sed 's/^/  /'
  [ -n "$prs" ] && gh api repos/{owner}/{repo}/compare/main...update/all \
    --jq '"  update/all vs main: \(.status), \(.ahead_by) ahead, \(.behind_by) behind"' 2>/dev/null
else
  echo "  (gh FAILED, check gh auth status): $prs"
fi
