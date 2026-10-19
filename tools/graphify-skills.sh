#!/usr/bin/env bash
# Regenerates the vendored graphify skill for Claude Code (.claude/skills/graphify), OpenCode
# (.opencode/skills/graphify) and Codex (.agents/skills/graphify) from the installed graphify CLI.
# Run after `uv tool upgrade graphifyy`, then commit the three directories.
# `graphify install --project` also rewrites CLAUDE.md, AGENTS.md, .claude/settings.json and
# .opencode/plugins/ (and `--platform codex` writes AGENTS.md and .codex/hooks.json), so it runs in a
# throwaway repo and only the skill directories are copied. Codex installs to .codex/skills, which
# Codex reads only in trusted projects; .agents/skills is read always.
set -euo pipefail

cd "$(dirname "$0")/.."
root=$PWD
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for pair in claude:.claude opencode:.opencode codex:.agents; do
  platform=${pair%%:*} dest=${pair#*:}
  dir="$tmp/$platform"
  mkdir -p "$dir"
  (cd "$dir" && git init -q && graphify install --project --platform "$platform" >/dev/null) || true
  [[ -f "$dir/.$platform/skills/graphify/SKILL.md" ]] || { echo "graphify install --platform $platform wrote no skill" >&2; exit 1; }
  rm -rf "$root/$dest/skills/graphify"
  mkdir -p "$root/$dest/skills"
  cp -R "$dir/.$platform/skills/graphify" "$root/$dest/skills/graphify"
done

echo "graphify skill $(cat .claude/skills/graphify/.graphify_version) written to .claude/skills, .opencode/skills and .agents/skills"
