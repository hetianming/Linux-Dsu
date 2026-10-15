#!/system/bin/sh
# 本地 vendor_boot 文件 SELinux 宽容/严格处理（不刷回，输出新镜像）：
#   magiskboot unpack -h 解出 header → sed 改 cmdline 的 androidboot.selinux= → repack 新镜像
# 由 DNA 页面注入环境变量后 source 执行：
#   SELINUX_LOCAL_IN=/path/to/vendor_boot.img     输入镜像（root 可读）
#   SELINUX_LOCAL_OUT=/path/to/output.img         输出镜像（root 可写）
#   SELINUX_MODE=permissive|enforcing
#   PATH 已由 DnaTools.run 注入 magiskboot 所在目录

IN="${SELINUX_LOCAL_IN}"
OUT="${SELINUX_LOCAL_OUT}"
MODE="${SELINUX_MODE:-permissive}"
if [ "$MODE" = "permissive" ]; then FLAG=permissive; else FLAG=enforcing; fi

if [ -z "$IN" ] || [ -z "$OUT" ]; then
  echo "[错误] SELINUX_LOCAL_IN / SELINUX_LOCAL_OUT 未设置"
  return 30
fi
if [ ! -s "$IN" ]; then
  echo "[错误] 输入文件不存在或为空: $IN"
  return 31
fi
echo "[1/5] 本地文件: $IN（目标 androidboot.selinux=$FLAG）"

# 输出目录确保存在
OUT_DIR=$(dirname "$OUT")
mkdir -p "$OUT_DIR" || { echo "[错误] 无法创建输出目录 $OUT_DIR"; return 32; }

WORK_DIR=/data/magiskbootselinux_local
IMG="$WORK_DIR/input.img"
NEW_IMG="$WORK_DIR/output.img"

echo "[2/5] 准备临时工作目录 $WORK_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" || return 33

echo "[3/5] 复制输入镜像到 root 可写目录 ..."
cp "$IN" "$IMG" || return 34
[ -s "$IMG" ] || { echo "[错误] 复制失败或镜像为空"; return 35; }

echo "[4/5] magiskboot 解包 + 修改 header ..."
cd "$WORK_DIR" || return 36
magiskboot unpack -h "$IMG" || return 37
if [ ! -f header ]; then
  echo "[错误] 解包未生成 header 文件，该镜像可能不是标准 boot/vendor_boot 格式"
  return 38
fi
# 先清掉所有现有 selinux 标记，再在 cmdline 行尾追加唯一一个目标标记
sed -i "s/androidboot.selinux=permissive//g" header
sed -i "s/androidboot.selinux=enforcing//g" header
sed -i "/^cmdline=/{s/\$/& androidboot.selinux=$FLAG/}" header
echo "修改后 cmdline:"
grep "^cmdline=" header
magiskboot repack "$IMG" "$NEW_IMG" || return 39
[ -s "$NEW_IMG" ] || { echo "[错误] 重打包产物为空"; return 40; }

echo "[5/5] 输出结果 ..."
cp "$NEW_IMG" "$OUT" || { echo "[错误] 写入输出文件失败"; return 41; }
sync
echo "[成功] 本地文件已处理为 $FLAG 模式 → $OUT"
echo "文件可用于刷入 vendor_boot 分区（dd if=\"$OUT\" of=/dev/block/by-name/vendor_boot）"
