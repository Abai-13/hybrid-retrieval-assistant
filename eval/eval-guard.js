/**
 * 评测脚本的公共前置：目标地址 + 「别误连主库」保护。
 *
 * <p>为什么需要这个保护：主库 `lost_found` 里 128064 条物品中只有 2000 条是评测种子，
 * 另外 126063 条是性能压测数据（与种子共用物品模板）。在主库上跑评测，
 * **每一条标准答案都能找到字段完全相同的克隆体** —— 指标会失去意义，
 * 而且表面上一切正常，不会报任何错。
 *
 * <p>判据用的是 id 布局这条结构性事实（不是标题重复那种弱判据）：
 * - 压测数据的 id 块从 393212 起，探针 id 400000 只在主库存在
 * - 评测种子的 id 是 1000~2999，探针 id 1703 只在评测库存在
 * 两个条件同时成立才算「连对了目标」。
 */

/** 评测后端地址。默认指向主环境端口，评测时用 AI_API 指到 8081。 */
export const DEFAULT_API = process.env.AI_API || 'http://localhost:8080';

/** 只在主库存在的探针（压测数据 id 块 ≥ 393212） */
const MAIN_DB_PROBE_ID = 400000;
/** 只在评测库存在的探针（seed id 块 1000~2999） */
const EVAL_DB_PROBE_ID = 1703;

async function probe(api, id) {
  const res = await fetch(`${api}/api/item/${id}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()).code;
}

export async function assertEvalBackend(api) {
  if (process.env.AI_EVAL_ALLOW_MAIN === '1') {
    console.warn('⚠️  AI_EVAL_ALLOW_MAIN=1 —— 已跳过「是否误连主库」检查。');
    console.warn('    主库混有 12.6 万条压测数据，结果不能当作干净的评测基线。');
    return;
  }

  let mainCode, evalCode;
  try {
    mainCode = await probe(api, MAIN_DB_PROBE_ID);
    evalCode = await probe(api, EVAL_DB_PROBE_ID);
  } catch (e) {
    throw new Error(`连不上 ${api}（${e.message}）—— 请先启动后端服务。`);
  }

  if (mainCode === 200) {
    throw new Error(
      `目标后端 ${api} 上存在物品 #${MAIN_DB_PROBE_ID} —— 这是**主库**的特征，不是评测库。\n` +
      '  主库里混有 12.6 万条压测数据，标准答案会被大量字段完全相同的克隆体淹没，指标没有意义。\n' +
      '  正确做法：\n' +
      '    1) bash eval/setup-eval-db.sh           # 准备干净评测库\n' +
      '    2) mvn spring-boot:run -Dspring-boot.run.profiles=eval    # 评测后端 → 8081\n' +
      "    3) AI_API=http://localhost:8081 node eval/<脚本>.js\n" +
      '  确实要在主库上跑（不推荐）就显式设置 AI_EVAL_ALLOW_MAIN=1。'
    );
  }
  if (evalCode !== 200) {
    throw new Error(
      `目标后端 ${api} 上找不到物品 #${EVAL_DB_PROBE_ID} —— 它既不是主库，也没有评测种子数据。\n` +
      '  请确认评测库已经用 eval/setup-eval-db.sh 准备好。'
    );
  }

  console.log(`✅ 目标检查通过：${api} 上不存在 #${MAIN_DB_PROBE_ID}、存在种子 #${EVAL_DB_PROBE_ID} —— 连的是干净的评测库。`);
}
