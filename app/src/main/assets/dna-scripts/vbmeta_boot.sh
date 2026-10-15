#!/system/bin/sh

MAGISKBOOT="magiskboot"

echo "[*] 开始 vbmeta 禁用脚本"

# root 检查
if [ "$(id -u)" -ne 0 ]; then
  echo "[!] 请使用 root 权限运行此脚本"
  exit 1
fi

# magiskboot 检查
if ! command -v "$MAGISKBOOT" >/dev/null 2>&1; then
  echo "[!] 未找到 magiskboot，请确保已安装 Magisk"
  exit 1
fi

# 处理 vbmeta_a / vbmeta_b
for slot in a b; do
  PART="/dev/block/bootdevice/by-name/vbmeta_$slot"

  echo ""
  echo "[*] 正在处理 vbmeta_$slot"

  # 分区存在检查
  if [ ! -e "$PART" ]; then
    echo "[!] 分区不存在：$PART，跳过"
    continue
  fi

  TMP="/data/adb/vbmeta_$slot.img"
  OUT="/data/adb/vbmeta_${slot}_disabled.img"

  echo "[+] 提取 vbmeta_$slot ..."
  dd if="$PART" of="$TMP" bs=4096 status=none

  echo "[+] 禁用 AVB / dm-verity ..."
  cp "$TMP" "$OUT"

$MAGISKBOOT hexpatch $OUT 0000000000000000617662746F6F6C20 0000000200000000617662746F6F6C20
  echo "[+] 刷回 vbmeta_$slot ..."
  dd if="$OUT" of="$PART" bs=4096 status=none

  rm -f "$TMP" "$OUT"

  echo "[✔] vbmeta_$slot 处理完成"
done

echo ""
echo "[✔] 所有 vbmeta 分区已成功禁用"
echo "[*] 建议执行重启设备"
