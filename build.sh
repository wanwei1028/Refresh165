#!/usr/bin/env bash
# Refresh165 — 从源码构建可安装 APK
# 依赖：JDK 17+、Android SDK（build-tools 34+、platform android-34+）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MOD="$ROOT/module"
VERSION_FILE="$ROOT/version.properties"

VER_NAME="$(grep '^versionName=' "$VERSION_FILE" | cut -d= -f2)"
VER_CODE="$(grep '^versionCode=' "$VERSION_FILE" | cut -d= -f2)"
echo "Refresh165 v$VER_NAME ($VER_CODE)"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
PLATFORM="${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar}"
LIBXPOSED="$ROOT/libs/libxposed-api-102.jar"

JAVAC="${JAVAC:-javac}"
D8="$BT/d8"
AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

KEYSTORE="${KEYSTORE:-$ROOT/keystore.jks}"
KS_PASS="${KS_PASS:-modpass}"
KS_ALIAS="${KS_ALIAS:-modkey}"
OUT="${OUT:-$ROOT/out}"
APK_NAME="${APK_NAME:-Refresh165-v$VER_NAME.apk}"

OUT="$(python3 - "$ROOT" "$OUT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).expanduser().resolve()
home = Path.home().resolve()
if out == Path(out.anchor) or out == root or out in root.parents or out == home or out in home.parents:
    raise SystemExit(f"拒绝危险输出目录: {out}")
print(out)
PY
)"
case "$APK_NAME" in
  ""|"."|".."|*/*) echo "APK_NAME 必须是单一文件名" >&2; exit 1 ;;
esac

for f in "$D8" "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$PLATFORM" "$LIBXPOSED"; do
  [ -e "$f" ] || { echo "缺少依赖: $f" >&2; exit 1; }
done

mkdir -p "$OUT"
WORK="$(mktemp -d "$OUT/.refresh165-build.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

echo "[1/6] 准备隔离构建目录"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/apk"

echo "[2/6] 编译 Java"
find "$MOD/src" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" --release 11 -encoding UTF-8 \
  -cp "$PLATFORM:$LIBXPOSED" \
  -d "$WORK/classes" @"$WORK/sources.txt"

echo "[3/6] d8 生成 dex"
find "$WORK/classes/com" -name '*.class' > "$WORK/classes.txt"
"$D8" --release --min-api 29 --lib "$PLATFORM" --output "$WORK/dex" @"$WORK/classes.txt"

echo "[4/6] 资源编译与链接"
"$AAPT2" compile --dir "$MOD/res" -o "$WORK/res.zip"
sed -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$VER_CODE\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"v$VER_NAME\"/" \
  "$MOD/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"
"$AAPT2" link -o "$WORK/apk/base.apk" -I "$PLATFORM" \
  --manifest "$WORK/AndroidManifest.xml" "$WORK/res.zip"

echo "[5/6] 打包（含 assets 与根目录 META-INF/xposed）"
python3 - "$MOD" "$WORK" <<'PY'
from pathlib import Path
import sys, zipfile
mod, out = map(Path, sys.argv[1:])
with zipfile.ZipFile(out/"apk"/"base.apk") as src, \
     zipfile.ZipFile(out/"apk"/"unsigned.apk", "w", zipfile.ZIP_STORED) as dst:
    for name in src.namelist():
        dst.writestr(name, src.read(name))
    dst.write(out/"dex"/"classes.dex", "classes.dex")
    assets = mod/"assets"
    for f in assets.rglob("*"):
        if not f.is_file():
            continue
        rel = str(f.relative_to(assets))
        dst.write(f, "assets/" + rel)
        # 现代 Xposed API 的入口/scope/module.prop 必须在 APK 根 META-INF/xposed/
        if rel.startswith("META-INF/"):
            dst.write(f, rel)
PY

echo "[6/6] 对齐与签名"
"$ZIPALIGN" -f -p 4 "$WORK/apk/unsigned.apk" "$WORK/apk/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  echo "未找到 keystore，生成一个自签名调试密钥：$KEYSTORE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Refresh165, OU=Dev, O=Refresh165, L=NA, S=NA, C=CN"
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$WORK/$APK_NAME" "$WORK/apk/aligned.apk"
"$APKSIGNER" verify --verbose "$WORK/$APK_NAME" | head -5

mv -f "$WORK/$APK_NAME" "$OUT/$APK_NAME"
echo "完成: $OUT/$APK_NAME"
shasum -a 256 "$OUT/$APK_NAME"
