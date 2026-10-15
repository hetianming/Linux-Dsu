#!/system/bin/sh
# 内核 SELinux 宽容/严格（参考脚本 SElinux.sh 参数化）：
# dd 提取本机 boot/vendor_boot 分区镜像 → magiskboot unpack -h 解出 header →
# sed 改 header cmdline 的 androidboot.selinux= → repack → dd 刷回分区，重启生效。
# 由 DNA 页面注入环境变量后 source 执行：
#   SELINUX_PART=vendor_boot_a|vendor_boot_b|boot_a|boot_b
#   SELINUX_MODE=permissive|enforcing
#   PATH 已由 DnaTools.run 注入 magiskboot 所在目录

PART="${SELINUX_PART:-vendor_boot_a}"
MODE="${SELINUX_MODE:-permissive}"
if [ "$MODE" = "permissive" ]; then FLAG=permissive; else FLAG=enforcing; fi

# 分区块设备定位：优先 /dev/block/by-name，兼容高通 /dev/block/bootdevice/by-name
DEV=""
for __base in /dev/block/by-name /dev/block/bootdevice/by-name; do
  if [ -e "$__base/$PART" ]; then DEV="$__base/$PART"; break; fi
done
if [ -z "$DEV" ]; then
  echo "[错误] 找不到分区块设备 $PART（by-name 下不存在）"
  return 20
fi
echo "[1/6] 分区设备: $DEV（目标 androidboot.selinux=$FLAG）"

WORK_DIR=/data/magiskbootselinux
IMG="$WORK_DIR/$PART.img"
NEW_IMG="$WORK_DIR/new-boot.img"

# 清理重建工作目录：绝不能复用旧解包产物（stale header 会导致刷入错误镜像）
echo "[2/6] 清理并重建工作目录 $WORK_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" || return 21

echo "[3/6] dd 提取 $PART 分区镜像 ..."
dd if="$DEV" of="$IMG" bs=4M || return 22
sync
[ -s "$IMG" ] || { echo "[错误] 提取的镜像为空"; return 23; }

echo "[4/6] magiskboot 解包（-h 输出 header）..."
cd "$WORK_DIR" || return 24
magiskboot unpack -h "$IMG" || return 25
if [ ! -f header ]; then
  echo "[错误] 解包未生成 header 文件，该镜像可能不是标准 boot/vendor_boot 格式"
  return 26
fi

echo "[5/6] 修改 header cmdline → androidboot.selinux=$FLAG"
if [ "$FLAG" = "permissive" ]; then
  sed -i "s/androidboot.selinux=enforcing/androidboot.selinux=permissive/" header
else
  sed -i "s/androidboot.selinux=permissive/androidboot.selinux=enforcing/" header
fi
# 先清掉所有现有 selinux 标记，再在 cmdline 行尾追加唯一一个目标标记
sed -i "s/androidboot.selinux=permissive//g" header
sed -i "s/androidboot.selinux=enforcing//g" header
sed -i "/^cmdline=/{s/\$/& androidboot.selinux=$FLAG/}" header
echo "修改后 cmdline:"
grep "^cmdline=" header

echo "[6/6] magiskboot 重打包并刷回 ..."
magiskboot repack "$IMG" "$NEW_IMG" || return 27
[ -s "$NEW_IMG" ] || { echo "[错误] 重打包产物为空"; return 28; }
sync
dd if="$NEW_IMG" of="$DEV" bs=4M || return 29
sync
echo "[成功] $PART 已写入 androidboot.selinux=$FLAG，重启系统生效"
