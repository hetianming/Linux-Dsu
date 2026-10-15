#!/system/bin/sh
# 本地 vbmeta.img 禁用 AVB / dm-verity（不刷回，输出新镜像）：
#   复制输入 → magiskboot hexpatch 修改 magic → 输出新镜像
# 由 DNA 页面注入环境变量后 source 执行：
#   VBMETA_LOCAL_IN=/path/to/vbmeta.img     输入镜像（root 可读）
#   VBMETA_LOCAL_OUT=/path/to/output.img    输出镜像（root 可写）
#   PATH 已由 DnaTools.run 注入 magiskboot 所在目录

IN="${VBMETA_LOCAL_IN}"
OUT="${VBMETA_LOCAL_OUT}"

if [ -z "$IN" ] || [ -z "$OUT" ]; then
  echo "[错误] VBMETA_LOCAL_IN / VBMETA_LOCAL_OUT 未设置"
  return 30
fi
if [ ! -s "$IN" ]; then
  echo "[错误] 输入文件不存在或为空: $IN"
  return 31
fi
echo "[1/4] 本地文件: $IN"

OUT_DIR=$(dirname "$OUT")
mkdir -p "$OUT_DIR" || { echo "[错误] 无法创建输出目录 $OUT_DIR"; return 32; }

WORK_DIR=/data/magiskbootvbmeta_local
IMG="$WORK_DIR/input.img"
NEW_IMG="$WORK_DIR/output.img"

echo "[2/4] 准备临时工作目录 $WORK_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" || return 33

echo "[3/4] 复制并 hexpatch 禁用 AVB / dm-verity ..."
cp "$IN" "$IMG" || return 34
[ -s "$IMG" ] || { echo "[错误] 复制失败或镜像为空"; return 35; }
# 单次 hexpatch：old = 全 0 前 8 字节 + "avbtool ", new = 0x00000002 标志 + "avbtool "
magiskboot hexpatch "$IMG" \
  0000000000000000617662746F6F6C20 \
  0000000200000000617662746F6F6C20 || return 36

echo "[4/4] 输出结果 ..."
cp "$IMG" "$OUT" || return 37
sync
echo "[成功] 本地 vbmeta 已禁用 AVB / dm-verity → $OUT"
echo "文件可用于刷入 vbmeta 分区（dd if=\"$OUT\" of=/dev/block/by-name/vbmeta）"
