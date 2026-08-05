#!/bin/bash
# ============================================================
# NcmDownloader 构建脚本
# 依赖: JDK8+, android.jar, dx, Python3, apksigner(zipalign)
# 用法: ./build.sh [android_jar] [dx_jar]
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${1:-/root/ncm/buildlib/extracted/android-13/android.jar}"
DX_JAR="${2:-/root/ncm/buildlib/build-tools/android-8.0.0/lib/dx.jar}"
OUT="$ROOT/out"
APKPROJ="$ROOT/module/apkproj"

echo "[1/5] 编译 Xposed API stub (仅编译期)..."
rm -rf "$OUT/stub_classes" && mkdir -p "$OUT/stub_classes"
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/stub_classes" $(find "$ROOT/module/stub" -name '*.java')

echo "[2/5] 编译模块源码 (Xposed API 不打包进 dex)..."
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" \
    -classpath "$OUT/stub_classes" -d "$OUT/classes" \
    $(find "$ROOT/module/src" -name '*.java')

echo "[3/5] dx 生成 classes.dex..."
rm -f "$OUT/classes.dex"
java -jar "$DX_JAR" --dex --output="$OUT/classes.dex" "$OUT/classes"

echo "[4/5] 重新打包 APK (resources.arsc store + 4字节对齐)..."
rm -rf "$OUT/apkfix" && mkdir -p "$OUT/apkfix"
unzip -q -o "$ROOT/release/base-unsigned.apk" -d "$OUT/apkfix" 2>/dev/null \
    || unzip -q -o "$ROOT/release/ncm-downloader-v1.6.apk" -d "$OUT/apkfix"
cp "$OUT/classes.dex" "$OUT/apkfix/classes.dex"
rm -rf "$OUT/apkfix/META-INF"
python3 - "$OUT/apkfix" "$OUT/ncm-aligned.apk" << 'EOF'
import zipfile, os, sys
d, out = sys.argv[1], sys.argv[2]
zf = zipfile.ZipFile(out, 'w')
manifest_len = os.path.getsize(os.path.join(d, 'AndroidManifest.xml'))
pad = (-(30 + 19 + manifest_len + 30 + 14)) % 4
zi = zipfile.ZipInfo('AndroidManifest.xml')
zi.compress_type = zipfile.ZIP_STORED
zi.extra = b'\x00' * pad
zf.writestr(zi, open(os.path.join(d, 'AndroidManifest.xml'), 'rb').read())
zf.write(os.path.join(d, 'resources.arsc'), 'resources.arsc', compress_type=zipfile.ZIP_STORED)
zf.write(os.path.join(d, 'classes.dex'), 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
for name in os.listdir(os.path.join(d, 'assets')):
    zf.write(os.path.join(d, 'assets', name), 'assets/' + name, compress_type=zipfile.ZIP_DEFLATED)
zf.close()
EOF

echo "[5/5] 签名..."
cp "$OUT/ncm-aligned.apk" "$ROOT/release/ncm-unsigned.apk"
echo "完成: 使用 apk_reverse_sign 或 apksigner 对 release/ncm-unsigned.apk 签名"
echo "   apksigner sign --ks <keystore> --out release/ncm-downloader.apk release/ncm-unsigned.apk"
