#!/usr/bin/env bash
# 更新 PixelTelo 离线骚扰号码库（mast.db）和 PixelText 垃圾短信模型 / 短信卡片规则（rules.json）。
# 流程：查版本 → 下载到临时目录 → sha256 与内容校验 → 原子替换 → 必要时重启 phonebox → /health 检查。
# 用法：update-data.sh [--mast] [--model] [--no-restart]   （不带 --mast/--model 时两个都更新）
# 依赖：bash、curl、python3、sha256sum。cron 示例见 README（每周一次）。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . ./.env; set +a; }
DATA_DIR=${DATA_DIR:-./data}
MAST_DIR="$DATA_DIR/mast"; MODEL_DIR="$DATA_DIR/model"
MAST_API=${MAST_API:-https://pixeltelo.api.mystery0.vip/api/v1/sync/check?current_version=}
MODEL_API=${MODEL_API:-https://pixeltext.api.mystery0.vip/api/v1/resources/manifest}
DO_MAST=0; DO_MODEL=0; RESTART=1
for a in "$@"; do
  case "$a" in
    --mast) DO_MAST=1 ;; --model) DO_MODEL=1 ;; --no-restart) RESTART=0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done
[ $DO_MAST = 0 ] && [ $DO_MODEL = 0 ] && { DO_MAST=1; DO_MODEL=1; }
mkdir -p "$MAST_DIR" "$MODEL_DIR"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
log() { echo "$(date '+%F %T') $*"; }
json() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); 
for k in sys.argv[2].split("."): d=d[k]
print(d)' "$@"; }
NEED_RESTART=0

if [ $DO_MAST = 1 ]; then
  curl -fsS -m 30 "$MAST_API" -o "$TMP/mast.json"
  LATEST=$(json "$TMP/mast.json" latest_version)
  CUR=$(cat "$MAST_DIR/mast.version" 2>/dev/null || echo none)
  if [ "$LATEST" = "$CUR" ]; then
    log "号码库已是最新 $CUR"
  else
    curl -fsSL -m 600 "$(json "$TMP/mast.json" download_url)" -o "$TMP/mast.zip"
    # 接口给出的 checksum 是 zip 压缩包的 sha256（不是解压后的 db）
    echo "$(json "$TMP/mast.json" checksum)  $TMP/mast.zip" | sha256sum -c --quiet
    python3 - "$TMP/mast.zip" "$TMP/mast.db" <<'PY'
import shutil, sqlite3, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
name = next(n for n in z.namelist() if n.split('/')[-1].startswith('mast_') and n.endswith('.db'))
with z.open(name) as src, open(sys.argv[2], 'wb') as dst:
    shutil.copyfileobj(src, dst)
c = sqlite3.connect(f'file:{sys.argv[2]}?mode=ro', uri=True)
n = c.execute('select count(*) from spam_numbers').fetchone()[0]
assert n > 100000, f'号码库条目太少：{n}'
PY
    # 同一文件系统内 mv 是原子的；phonebox 按修改时间自动重开，不用重启
    mv "$TMP/mast.db" "$MAST_DIR/mast.db.new" && mv "$MAST_DIR/mast.db.new" "$MAST_DIR/mast.db"
    printf '%s\n' "$LATEST" > "$MAST_DIR/mast.version.new" && mv "$MAST_DIR/mast.version.new" "$MAST_DIR/mast.version"
    log "号码库 $CUR -> $LATEST"
  fi
fi

if [ $DO_MODEL = 1 ]; then
  curl -fsSL -m 20 "$MODEL_API" -o "$TMP/manifest.json"
  # 短信卡片规则：独立于模型版本，每次分析时现读，无需重启
  RV=$(json "$TMP/manifest.json" rules.version)
  if [ "$RV" != "$(cat "$MODEL_DIR/rules.version" 2>/dev/null)" ]; then
    curl -fsSL -m 30 "$(json "$TMP/manifest.json" rules.downloadUrl)" -o "$TMP/rules.json"
    echo "$(json "$TMP/manifest.json" rules.sha256)  $TMP/rules.json" | sha256sum -c --quiet
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$TMP/rules.json"
    mv "$TMP/rules.json" "$MODEL_DIR/rules.json.new" && mv "$MODEL_DIR/rules.json.new" "$MODEL_DIR/rules.json"
    printf '%s\n' "$RV" > "$MODEL_DIR/rules.version"
    log "短信规则更新到 $RV"
  else
    log "短信规则已是最新 $RV"
  fi
  VER=$(json "$TMP/manifest.json" spamModel.version)
  CUR=$(cat "$MODEL_DIR/model.version" 2>/dev/null || echo none)
  if [ "$VER" = "$CUR" ]; then
    log "垃圾短信模型已是最新 $CUR"
  else
    curl -fsSL -m 120 "$(json "$TMP/manifest.json" spamModel.model.downloadUrl)" -o "$TMP/spam_classifier.tflite"
    curl -fsSL -m 30 "$(json "$TMP/manifest.json" spamModel.vocab.downloadUrl)" -o "$TMP/vocab.txt"
    echo "$(json "$TMP/manifest.json" spamModel.model.sha256)  $TMP/spam_classifier.tflite" | sha256sum -c --quiet
    echo "$(json "$TMP/manifest.json" spamModel.vocab.sha256)  $TMP/vocab.txt" | sha256sum -c --quiet
    mv "$TMP/spam_classifier.tflite" "$MODEL_DIR/spam_classifier.tflite.new"
    mv "$TMP/vocab.txt" "$MODEL_DIR/vocab.txt.new"
    mv "$MODEL_DIR/spam_classifier.tflite.new" "$MODEL_DIR/spam_classifier.tflite"
    mv "$MODEL_DIR/vocab.txt.new" "$MODEL_DIR/vocab.txt"
    printf '%s\n' "$VER" > "$MODEL_DIR/model.version"
    log "垃圾短信模型 $CUR -> $VER"
    NEED_RESTART=1   # 模型在进程里只加载一次
  fi
fi

if [ $NEED_RESTART = 1 ] && [ $RESTART = 1 ]; then
  if [ -n "${COMPOSE:-}" ]; then read -ra DC <<<"$COMPOSE"
  elif docker compose version >/dev/null 2>&1; then DC=(docker compose)
  else DC=(podman compose); fi
  "${DC[@]}" restart phonebox >/dev/null
  H=${PHONEBOX_BIND:-127.0.0.1}; [ "$H" = 0.0.0.0 ] && H=127.0.0.1
  for _ in $(seq 20); do
    curl -fsS -m 3 "http://$H:${PHONEBOX_PORT:-18989}/health" 2>/dev/null | grep -qx ok && { log "phonebox 已重启，/health ok"; exit 0; }
    sleep 1
  done
  log "phonebox 重启后 /health 不正常，请检查 logs"; exit 1
fi
