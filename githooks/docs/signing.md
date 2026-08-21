# Conditional commit signing

Most commits stay friction-free. A small set of *signing conditions* — quality-tool
suppression markers, edits or deletions of existing test cases, and changes to protected
paths — require a GPG-signed commit from a trusted key. Nothing else is affected: a repo
with no trusted keys configured behaves exactly as it did before this feature existed.

## Enablement

Trusted signers' armored public keys live in `trustedKeysDir` (default
`.mill-signing/trusted-keys`, overridable as a `def` on your `GitHooksModule`). There is no
separate on/off switch — **enforcement activates the moment that directory holds at least
one key file**, and `./mill git.install` only emits the signing lines into your hooks once
that's true. A default install with an empty or missing keys dir is byte-identical to a
repo that never adopted this feature.

Export a trusted signer's public key and drop it into the trust store:

```bash
gpg --export --armor <key-id> > .mill-signing/trusted-keys/<name>.asc
```

Trust-store changes are themselves a protected-path change (see below), so once signing is
active, adding or removing a key requires the same signed-commit ceremony as any other
protected change.

### Bootstrap ordering (hard prerequisite)

The **first** trusted key must land on the trust root *before* any server-side pre-receive
hook is installed — or via a direct admin server-side ref update. The remote gate reads keys
from the trust root's state, so a repo that installs enforcement before it has a first
trusted key locks itself out. An empty keys dir is a configuration error only for pushes that
actually contain protected commits; unprotected pushes to a non-adopting repo are unaffected.

### Key rotation ordering

A rotated or newly added key must land in **its own push first**. Remote gates always load
keys from the trust root's *pre-push* state — a commit that relies on a brand-new key added
in the *same* push that introduces it will not verify, because the gate reads the trust root
as it stood before that push landed.

## What triggers signing

Three built-in conditions, all overridable:

- **Exception/suppression markers** (`exceptionCommentMarkers`) — fires when an *added* line
  matches a suppression pattern. Defaults: scalafmt `format: off`, scalafix
  `scalafix:off`/`scalafix:ok`, scalastyle `scalastyle:off`, `NOSONAR`, CodeScene disable
  directives, `@nowarn`, `@SuppressWarnings`. Removing a marker, or working near an existing
  one, never fires.

- **Test-case protection** (`testCasePatterns`) — fires when an existing test case is removed
  or modified, resolved case-by-case against the file's *old* path and *old* content. **Pure
  insertions never fire — this is a known v1 evasion vector, not a safety guarantee.** An
  inserted `assume(false)` or an early return can neuter an existing test without removing a
  single line, and this feature will not catch it. Treat it as a prompt for human review, not
  a substitute for one.

- **Protected paths** (`protectedPathGlobs`) — fires on *any* change (add, modify, delete,
  rename in or out) to the trust store directory or common tool-config files. A change to an
  otherwise-inspectable source path that can't be text-diffed (binary-classified) is
  **fail-closed** — treated as a trigger, since the line-based conditions can't inspect it.

Override `signingConditions` directly to supply a fully custom condition list; override the
narrower defs above to adjust the built-ins without hand-building conditions.

## Layered enforcement

Signing is checked at three points, with different strictness:

1. **Pre-commit** (`git.checkSigning`) — an *intent* check only: if a condition fires, the
   commit must have `commit.gpgsign=true` set locally. Git signs a commit *after* pre-commit
   runs, so this can only confirm the commit is about to be signed — not verify the signature
   itself.
2. **Pre-push** (`git.verifyRange --lenient`) — full cryptographic verification of every
   commit in the push range, but *lenient*: if the trust store isn't configured at all
   (config drift since the hook was installed), the push passes with a notice instead of
   failing. Local hooks are feedback, not security — `--no-verify` bypasses them entirely.
3. **CI / manual `verifyRange`** (strict, the default) — the same verification, but a missing
   or misconfigured trust store is a hard failure. This — or eventually a server-side
   pre-receive hook — is the actual enforcement layer.

## Remedies

The rejection message names which of these applies:

| Message | Fix |
|---|---|
| Signing not configured (pre-commit, no `commit.gpgsign`) | `git config commit.gpgsign true`, or commit with `-S` |
| Unsigned (pushed a protected commit with no signature) | Sign it (`git commit --amend -S` / interactive rebase with `-S`) and re-push |
| Key not in trust root | See **untrusted-contributor workflow** below |
| Revoked / expired key | Re-sign with a current trusted key |
| Unverifiable format (SSH-signed) | v1 supports OpenPGP only — see **GPG-only scope** below |

### Untrusted-contributor workflow

A contributor without a trusted key *will* legitimately need to touch a protected pattern.
"Just add your key to the trust root" is not a self-service fix — it's a protected-path
change, requiring its own trusted signature from a maintainer. The actual remedy is one of:

- A trusted maintainer checks out the branch and re-signs it (amend or rebase with `-S`
  using their own trusted key), then pushes.
- A trusted maintainer countersigns the change before merge.

Trust changes (adding a contributor's key) are a maintainer decision, made separately and
signed by an already-trusted key.

## History rewriting

`git rebase`, `git commit --amend`, and `pull.rebase` all destroy existing trusted
signatures — the rewritten commits need re-signing. Where signed history must be preserved,
update branches by merge rather than rebase.

## Web-flow key warning

Never add a platform's shared web-flow signing key (e.g. GitHub's) to the trust root. Doing
so collapses attribution to "anyone with push access to the platform," which defeats the
entire point of per-key trust. `TrustedKeys` refuses known shared platform keys by default;
loading one requires an explicit `allowKnownPlatformKeys` override.

## Revocation caveat

Revocation is honored only when it's embedded in the armored key material itself, or
effected by removing the key file from the trust root. There is no keyserver interaction and
no retroactive history check. A compromised key remains trusted for signatures made until its
file is actually removed from the trust root — this window is an accepted v1 limitation, not
something silently glossed over. Rotate and remove compromised keys promptly.

## GPG-only v1 scope

Only OpenPGP (GPG) signatures are verified in v1. SSH-signed commits (`gpg.format=ssh`) pass
the local pre-commit intent check — a hook can't know the future signature's format — but are
rejected at verification time with a message naming the limitation. JGit has an SSH
verification path that isn't wired up here; this is a deliberate deferral, not an oversight,
and may be revisited if the adopter population proves SSH-dominant.
