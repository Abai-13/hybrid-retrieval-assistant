#!/usr/bin/env bash
#
# 准备评测专用数据库 lost_found_eval —— 只装「评测种子」，不装性能压测数据。
#
# 为什么需要它：主库 lost_found 里 128064 条物品中，只有 2000 条是评测种子
# （id 1000~2999，来源 eval/seed-items.sql），另外 126063 条是压测数据
# （id 393212~520726）。压测数据与种子共用物品模板，导致**每一条评测物品
# 在主库里都能找到字段完全相同的克隆体** —— 标准答案无法判定，评测做不了。
#
# 用法：
#   MYSQL_BIN=/path/to/mysql MYSQL_PWD=xxx bash eval/setup-eval-db.sh
#
# 可覆盖的环境变量：
#   MYSQL_BIN        mysql 客户端路径（默认从 PATH 找 mysql）
#   MAIN_DB          主库名（默认 lost_found）
#   EVAL_DB          评测库名（默认 lost_found_eval）
#   MYSQL_HOST/MYSQL_PORT/MYSQL_USERNAME/MYSQL_PWD  连接参数
#
# ⚠️ 本脚本只读主库、只写评测库。**主库一行数据都不会动。**
#    回滚：DROP DATABASE lost_found_eval; 即可，主库毫发无损。

set -euo pipefail

MYSQL_BIN="${MYSQL_BIN:-mysql}"
# 从 mysql 的路径推出 mysqldump，并保留同样的后缀（Windows 上是 .exe）
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-${MYSQL_BIN%mysql*}mysqldump${MYSQL_BIN##*mysql}}"
MAIN_DB="${MAIN_DB:-lost_found}"
EVAL_DB="${EVAL_DB:-lost_found_eval}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCHEMA_SQL="$ROOT_DIR/sql/schema.sql"
SEED_SQL="$ROOT_DIR/eval/seed-items.sql"

# ── 破坏性操作前的三道闸 ────────────────────────────────────────
# 本脚本会执行 DROP DATABASE。目标一旦指错，主库就没了。所以：
#   ① 目标名字必须以 _eval 结尾（手滑写成 lost_found 也会被拦）
#   ② 目标不能与主库同名
#   ③ 真正执行 DROP 前再断言一次，并把目标名打印出来留痕
# 对主库只有 mysqldump 读操作 —— 不写、不改、不删。
case "$EVAL_DB" in
  *_eval) : ;;
  *) echo "🔴 EVAL_DB 必须以 _eval 结尾（当前：'$EVAL_DB'）—— 拒绝执行，避免误删主库。" >&2; exit 1 ;;
esac
if [ "$EVAL_DB" = "$MAIN_DB" ]; then
  echo "🔴 EVAL_DB 与 MAIN_DB 同名（$EVAL_DB）—— 拒绝执行，这会把主库删掉。" >&2
  exit 1
fi

for f in "$SCHEMA_SQL" "$SEED_SQL"; do
  [ -f "$f" ] || { echo "🔴 找不到 $f" >&2; exit 1; }
done

echo "主库（只读，绝不修改）：$MAIN_DB"
echo "评测库（会被重建）    ：$EVAL_DB"
echo

run_main() { "$MYSQL_BIN" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USERNAME" --default-character-set=utf8mb4 "$@"; }

echo "① 重建评测库 $EVAL_DB（主库 $MAIN_DB 不受影响）"
# 破坏性语句执行前再断言一次目标 —— 这里出错的代价是不可逆的
case "$EVAL_DB" in
  *_eval) : ;;
  *) echo "🔴 断言失败：DROP 目标 '$EVAL_DB' 不以 _eval 结尾，中止。" >&2; exit 1 ;;
esac
echo "   → 即将执行：DROP DATABASE IF EXISTS \`$EVAL_DB\`;"
run_main -e "DROP DATABASE IF EXISTS \`$EVAL_DB\`; CREATE DATABASE \`$EVAL_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

echo "② 导入表结构（与主库同一份 schema.sql：user / item / claim_application / claim_log）"
run_main "$EVAL_DB" < "$SCHEMA_SQL"

echo "③ 导入评测种子（2000 条，id 1000~2999）"
run_main "$EVAL_DB" < "$SEED_SQL"

echo "④ 从主库复制账号行（1~5 是种子的发布者，evalbot 用于评测脚本登录）"
# 导入顺序是 user → item（虽然本库没有外键约束，顺序仍然按依赖来）。
#
# 关于 mysqldump 自带的 SET FOREIGN_KEY_CHECKS=0：
# 它是**无条件**输出的，跟有没有外键无关。这里不允许「靠关掉约束来掩盖数据问题」，
# 所以先把前提证明出来 —— **本 schema 里一个物理外键都没有**（刻意设计，见 CLAUDE.md：
# 不建物理外键，靠 idx_user_id 加速、靠 Service 层保证一致性）。
# 没有外键可检查，剥掉这句话就掩盖不了任何东西；一旦将来有人加了外键，下面这步会先失败。
FK_COUNT="$(run_main -N -B -e "
  SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA IN ('$EVAL_DB', '$MAIN_DB') AND REFERENCED_TABLE_NAME IS NOT NULL;")"
if [ "$FK_COUNT" != "0" ]; then
  echo "🔴 检测到 $FK_COUNT 个物理外键 —— 拒绝剥掉 FOREIGN_KEY_CHECKS。" >&2
  echo "   有外键时关掉约束检查可能掩盖引用完整性问题，需要人工确认导入顺序后再放行。" >&2
  exit 1
fi
echo "   （已确认两个库都没有物理外键，故剥掉 mysqldump 无条件附带的 FOREIGN_KEY_CHECKS）"

DUMP="$("$MYSQLDUMP_BIN" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USERNAME" \
        --no-create-info --complete-insert --skip-comments \
        --single-transaction --skip-lock-tables "$MAIN_DB" user \
        | grep -v 'FOREIGN_KEY_CHECKS')"
printf '%s\n' "$DUMP" | run_main "$EVAL_DB"

echo
echo "⑤ 自检"
run_main -N -B "$EVAL_DB" -e "
SELECT CONCAT('  物品总数          = ', COUNT(*)) FROM item;
SELECT CONCAT('  其中 FOUND+未认领  = ', COUNT(*)) FROM item WHERE type='FOUND' AND status='UNCLAIMED';
SELECT CONCAT('  用户数            = ', COUNT(*)) FROM user;
SELECT CONCAT('  ≥3000 的物品(应为0)= ', COUNT(*)) FROM item WHERE id >= 3000;
SELECT CONCAT('  认领相关表        = ', COUNT(*)) FROM information_schema.tables
  WHERE table_schema='$EVAL_DB' AND table_name IN ('claim_application','claim_log');
"
echo
echo "✅ 评测库已就绪。启动评测后端："
echo "   mvn spring-boot:run -Dspring-boot.run.profiles=eval"
