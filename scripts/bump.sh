#!/usr/bin/env bash
# 版本号自增：./scripts/bump.sh patch|minor|major
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
F="$ROOT/version.properties"
PART="${1:-patch}"

name=$(grep '^versionName=' "$F" | cut -d= -f2)
code=$(grep '^versionCode=' "$F" | cut -d= -f2)
IFS=. read -r major minor patch <<< "$name"

case "$PART" in
  major) major=$((major+1)); minor=0; patch=0 ;;
  minor) minor=$((minor+1)); patch=0 ;;
  patch) patch=$((patch+1)) ;;
  *) echo "用法: $0 patch|minor|major" >&2; exit 1 ;;
esac

newname="$major.$minor.$patch"
newcode=$((code+1))
sed -i.bak "s/^versionName=.*/versionName=$newname/; s/^versionCode=.*/versionCode=$newcode/" "$F"
rm -f "$F.bak"

echo "版本: $name ($code) -> $newname ($newcode)"
echo "构建: ./build.sh"
echo "发布: git commit -am \"v$newname\" && git tag v$newname && git push --tags"
