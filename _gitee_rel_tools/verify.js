// 双向核对：GitHub Release 元数据 vs Gitee 实际落库结果
const fs = require('fs');
const META = 'D:/桌面/voxlink/_gitee_rel_tools/releases.json';
const TOKEN = process.env.GT_TOKEN;
const API = 'https://gitee.com/api/v5';
const GT = 'AUGUHDAR/VoxLink';

const norm = s => (s || '').replace(/\r\n/g, '\n').trim();
const mb = n => (n / 1048576).toFixed(2) + 'MB';

async function main() {
  const releases = JSON.parse(fs.readFileSync(META, 'utf8'))
    .sort((a, b) => new Date(a.created_at) - new Date(b.created_at));

  let okRel = 0, badRel = 0, okFile = 0, badFile = 0;
  const problems = [];

  console.log('tag'.padEnd(8) + 'release'.padEnd(10) + 'body'.padEnd(10) + 'files(gh/gt)'.padEnd(14) + '体积');
  console.log('-'.repeat(70));

  for (const rel of releases) {
    const tag = rel.tag_name;
    let r;
    try {
      r = await (await fetch(`${API}/repos/${GT}/releases/tags/${encodeURIComponent(tag)}?access_token=${TOKEN}`)).json();
    } catch (e) { problems.push(`${tag}: 查询失败 ${e.message}`); badRel++; continue; }
    if (!r || !r.id) { problems.push(`${tag}: Gitee 无此 Release`); badRel++; console.log(tag.padEnd(8) + '缺失'.padEnd(10)); continue; }

    const relOk = r.tag_name === tag && r.name === (rel.name || tag) && norm(r.body) === norm(rel.body);
    if (relOk) okRel++; else { badRel++; problems.push(`${tag}: 元数据不一致 name=${JSON.stringify(r.name)} bodyLen=${(r.body || '').length} vs ${(rel.body || '').length}`); }

    let files = [];
    try {
      let page = 1;
      while (true) {
        const batch = await (await fetch(`${API}/repos/${GT}/releases/${r.id}/attach_files?access_token=${TOKEN}&per_page=100&page=${page}`)).json();
        if (!Array.isArray(batch) || batch.length === 0) break;
        files = files.concat(batch);
        if (batch.length < 100) break;
        page++;
      }
    } catch (e) { /* ignore */ }
    const byName = new Map((Array.isArray(files) ? files : []).map(f => [f.name, f]));
    // 重复附件检测（同一文件名出现多次）
    const nameCount = new Map();
    (Array.isArray(files) ? files : []).forEach(f => nameCount.set(f.name, (nameCount.get(f.name) || 0) + 1));
    const dupNames = [...nameCount.entries()].filter(([, c]) => c > 1);
    if (dupNames.length) {
      const extra = dupNames.reduce((s, [, c]) => s + (c - 1), 0);
      problems.push(`${tag}: 存在重复附件 ${dupNames.length} 个文件名 / 多余 ${extra} 份`);
      console.log(tag.padEnd(8) + 'RELEASE'.padEnd(10) + 'DUP×' + String(extra).padEnd(6) + `${files.length} 条记录`);
    }

    let fOk = 0, fBad = 0, bytes = 0;
    for (const a of rel.assets) {
      const f = byName.get(a.name);
      if (!f) { fBad++; problems.push(`${tag}/${a.name}: 附件缺失`); continue; }
      const sz = Number(f.size);
      if (sz === a.size) { fOk++; bytes += a.size; }
      else { fBad++; problems.push(`${tag}/${a.name}: 大小 ${sz} != ${a.size}`); }
    }
    okFile += fOk; badFile += fBad;
    console.log(tag.padEnd(8) + (relOk ? 'OK' : 'DIFF').padEnd(10) +
      ((norm(r.body) === norm(rel.body)) ? 'OK' : 'DIFF').padEnd(10) +
      (`${fOk}/${rel.assets.length}`).padEnd(14) + mb(bytes));
  }

  console.log('-'.repeat(70));
  console.log(`Release 元数据: ${okRel} 正确 / ${badRel} 有问题`);
  console.log(`附件: ${okFile} 正确 / ${badFile} 缺失或大小不符`);
  const ghTotal = releases.reduce((s, r) => s + r.assets.reduce((x, a) => x + a.size, 0), 0);
  console.log(`GitHub 附件总量 = ${mb(ghTotal)}`);
  if (problems.length) {
    console.log('\n=== 问题清单 ===');
    problems.forEach(p => console.log(' * ' + p));
    fs.writeFileSync('D:/桌面/voxlink/_gitee_rel_tools/verify_problems.txt', problems.join('\n'), 'utf8');
  } else {
    console.log('\n全部一致，无差异 (๑•̀ㅂ•́)و✧');
  }
}
main().catch(e => { console.error(e); process.exit(1); });
