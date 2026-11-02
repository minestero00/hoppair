#!/bin/bash
# Publish the static site from a clean checkout without changing the application branch.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -n "$(git status --porcelain)" ]]; then echo 'Commit or stash changes before publishing the site.' >&2; exit 1; fi
REPOSITORY="${MACDROID_GITHUB_REPO:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
REMOTE="$(git remote get-url origin)"
SOURCE_REVISION="$(git rev-parse HEAD)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/macdroid-pages.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
git init --quiet "$WORK_DIR"
git -C "$WORK_DIR" remote add origin "$REMOTE"
if git -c credential.helper='!gh auth git-credential' ls-remote --exit-code origin refs/heads/gh-pages > /dev/null; then
    git -C "$WORK_DIR" -c credential.helper='!gh auth git-credential' fetch --quiet origin gh-pages
    git -C "$WORK_DIR" checkout --quiet -B gh-pages FETCH_HEAD
else
    git -C "$WORK_DIR" checkout --quiet --orphan gh-pages
fi
rsync -a --delete --exclude=.git "$ROOT/site/" "$WORK_DIR/"
git -C "$WORK_DIR" add --all
if ! git -C "$WORK_DIR" diff --cached --quiet; then
    git -C "$WORK_DIR" -c user.name="MacDroid Publisher" -c user.email="macdroid-publisher@users.noreply.github.com" commit --quiet -m "Publish site from $SOURCE_REVISION"
    git -C "$WORK_DIR" -c credential.helper='!gh auth git-credential' push origin gh-pages
fi
validate_pages() {
    python3 - "$WORK_DIR/pages.json" <<'PYCODE'
import json,sys
site=json.load(open(sys.argv[1]))
if site.get('source') != {'branch':'gh-pages','path':'/'} or site.get('build_type') != 'legacy':
    raise SystemExit('Pages already uses another publishing source. Review its configuration before changing it.')
print(site['html_url'])
PYCODE
}
if gh api "repos/$REPOSITORY/pages" > "$WORK_DIR/pages.json" 2> "$WORK_DIR/pages-error.log"; then
    validate_pages
elif rg -q 'HTTP 404' "$WORK_DIR/pages-error.log"; then
    echo '{"build_type":"legacy","source":{"branch":"gh-pages","path":"/"}}' > "$WORK_DIR/pages-settings.json"
    if gh api --method POST "repos/$REPOSITORY/pages" --input "$WORK_DIR/pages-settings.json" > "$WORK_DIR/pages.json" 2> "$WORK_DIR/pages-create-error.log"; then
        validate_pages
    elif rg -q 'HTTP 409' "$WORK_DIR/pages-create-error.log"; then
        # Pushing gh-pages can enable Pages before the explicit create request arrives.
        gh api "repos/$REPOSITORY/pages" > "$WORK_DIR/pages.json"
        validate_pages
    else
        cat "$WORK_DIR/pages-create-error.log" >&2
        exit 1
    fi
else
    cat "$WORK_DIR/pages-error.log" >&2
    exit 1
fi
