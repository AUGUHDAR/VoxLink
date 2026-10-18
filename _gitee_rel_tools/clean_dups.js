// 清理 Gitee Release 中的重复附件（同一文件名多份）
// 默认 dry-run；加 --apply 才真正删除
// 安全约束：
//   1) 仅当同一文件名出现 >=2 次时才处理
//   2) 要求该组所有副本 size 完全相同，否则跳过并报警（避免删掉内容不同的文件）
//   3) 保留 id 最小（最早上传）的那一份，删除其余
const API = 'https://gitee.com/api/v5';
const GT = 'AUGUHDAR/VoxLink';
const TOKEN = process.env.GT_TOKEN;
const APPLY = process.argv.includes('--apply');
const fs = require('fs');

if (!TOKEN) { console.error('missing GT_TOKEN'); process.exit(1); }

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function listAll(releaseId) {
  let all = [], page = 1;
  while (true) {
    const b = await (await fetch(`${API}/repos/${GT}/releases/${releaseId}/attach_files?access_token=${TOKEN}&per_page=100&page=${page}`)).json();
    if (!Array.isArray(b) || b.length === 0) break;
    all = all.concat(b);
    if (b.length < 100) break;
    page++;
  }
  return all;
}

async function main() {
  const rels = await (await fetch(`${API}/repos/${GT}/releases?access_token=${TOKEN}&per_page=100`)).json();
  const report = [];
  let plannedDel = 0, doneDel = 0, skipped = 0;

  for (const r of rels) {
    const all = await listAll(r.id);
    const byName = new Map();
    for (const f of all) {
      if (!byName.has(f.name)) byName.set(f.name, []);
      byName.get(f.name).push(f);
    }
    for (const [name, copies] of byName) {
      if (copies.length < 2) continue;

      const sizes = new Set(copies.map(f => Number(f.size)));
      if (sizes.size !== 1) {
        skipped++;
        report.push(`SKIP ${r.tag_name}/${name}: 副本大小不一致 ${[...sizes].join(',')} —— 保留不动，需人工确认`);
        console.log(`[SKIP] ${r.tag_name}/${name} 大小不一致，跳过`);
        continue;
      }

      copies.sort((a, b) => a.id - b.id);
      const keep = copies[0];
      const del = copies.slice(1);
      plannedDel += del.length;
      console.log(`[${APPLY ? 'DEL' : 'dry'}] ${r.tag_name}/${name} (${(Number(keep.size) / 1048576).toFixed(2)}MB) 保留 id=${keep.id}，删除 ${del.map(f => 'id=' + f.id).join(', ')}`);
      report.push(`${APPLY ? 'DEL' : 'DRY'} ${r.tag_name}/${name} keep=${keep.id} del=${del.map(f => f.id).join(',')}`);

      if (APPLY) {
        for (const f of del) {
          const res = await fetch(`${API}/repos/${GT}/releases/${r.id}/attach_files/${f.id}?access_token=${TOKEN}`, { method: 'DELETE' });
          if (res.status === 204 || res.ok) { doneDel++; }
          else {
            const t = await res.text();
            report.push(`  FAIL del id=${f.id}: HTTP ${res.status} ${t.slice(0, 150)}`);
            console.log(`  [FAIL] del id=${f.id}: HTTP ${res.status}`);
          }
          await sleep(300);
        }
      }
    }
  }

  console.log(`\n==== ${APPLY ? '已删除' : '计划删除'} ${APPLY ? doneDel : plannedDel} 份 / 跳过 ${skipped} 组 ====`);
  fs.writeFileSync(`D:/桌面/voxlink/_gitee_rel_tools/cleanup_${APPLY ? 'applied' : 'plan'}.txt`, report.join('\n'), 'utf8');
}
main().catch(e => { console.error(e); process.exit(1); });
