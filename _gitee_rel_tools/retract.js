// 从 GitHub / Gitee / Modrinth 撤回指定文件名的发布产物（用于撤回损坏的 jar）
// 用法: node retract.js --files=a.jar,b.jar [--dry-run]
// CurseForge 无删除 API，不在本脚本范围内（需网页后台手删）。
const fs = require('fs');
const path = require('path');

const OWNER = 'AUGUHDAR', REPO = 'VoxLink';
const VERSION = '1.1.7';
const args = process.argv.slice(2);
const DRY = args.includes('--dry-run');
const FILES = ((args.find(a => a.startsWith('--files=')) || '').split('=')[1] || '').split(',').map(s => s.trim()).filter(Boolean);
const GT = process.env.GT_TOKEN;
const GH = process.env.GH_TOKEN;

if (!FILES.length) { console.error('缺 --files='); process.exit(1); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function ghApi(method, url) {
  const r = await fetch(url, { method, headers: { Authorization: 'Bearer ' + GH, 'User-Agent': 'voxlink-retract', Accept: 'application/vnd.github+json' } });
  return { status: r.status, body: await r.text() };
}
async function gtApi(method, url) {
  const r = await fetch(url, { method, headers: { 'User-Agent': 'voxlink-retract' }, redirect: 'follow' });
  return { status: r.status, body: await r.text() };
}

(async () => {
  console.log('撤回清单:', FILES.join(', '), DRY ? '(dry-run)' : '');

  // ---------- GitHub ----------
  if (GH) {
    console.log('\n=== GitHub ===');
    const rel = await (await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${VERSION}`, { headers: { Authorization: 'Bearer ' + GH, 'User-Agent': 'v' } })).json();
    for (const name of FILES) {
      const a = (rel.assets || []).find(x => x.name === name);
      if (!a) { console.log(`  [无此附件] ${name}`); continue; }
      if (DRY) { console.log(`  [计划删除] ${name} id=${a.id}`); continue; }
      const r = await ghApi('DELETE', `https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${a.id}`);
      console.log(`  [${r.status === 204 ? '已删除' : '失败 ' + r.status}] ${name} id=${a.id}`);
      await sleep(400);
    }
  } else console.log('\n=== GitHub: 无 token，跳过 ===');

  // ---------- Gitee ----------
  if (GT) {
    console.log('\n=== Gitee ===');
    const rel = await (await fetch(`https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases/tags/${VERSION}?access_token=${GT}`)).json();
    let files = [], page = 1;
    while (true) {
      const b = await (await fetch(`https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases/${rel.id}/attach_files?access_token=${GT}&per_page=100&page=${page}`)).json();
      if (!Array.isArray(b) || !b.length) break;
      files = files.concat(b);
      if (b.length < 100) break;
      page++;
    }
    for (const name of FILES) {
      const hits = files.filter(f => f.name === name);
      if (!hits.length) { console.log(`  [无此附件] ${name}`); continue; }
      for (const f of hits) {
        if (DRY) { console.log(`  [计划删除] ${name} id=${f.id}`); continue; }
        const r = await gtApi('DELETE', `https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases/${rel.id}/attach_files/${f.id}?access_token=${GT}`);
        console.log(`  [${[200, 204].includes(r.status) ? '已删除' : '失败 ' + r.status}] ${name} id=${f.id}`);
        await sleep(500);
      }
    }
  } else console.log('\n=== Gitee: 无 token，跳过 ===');

  // ---------- Modrinth ----------
  console.log('\n=== Modrinth ===');
  const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'tools', 'publish', 'config.json'), 'utf8'));
  const MR = cfg.modrinth.token;
  const pid = (await (await fetch('https://api.modrinth.com/v2/project/' + cfg.modrinth.project_id, { headers: { Authorization: MR } })).json()).id;
  const vers = await (await fetch(`https://api.modrinth.com/v2/project/${pid}/version`, { headers: { Authorization: MR } })).json();
  for (const name of FILES) {
    const hit = vers.find(v => v.version_number === VERSION && (v.files || []).some(f => f.filename === name));
    if (!hit) { console.log(`  [无此 version] ${name}`); continue; }
    if (DRY) { console.log(`  [计划删除] ${name} version_id=${hit.id} name=${hit.name}`); continue; }
    const r = await fetch(`https://api.modrinth.com/v2/version/${hit.id}`, { method: 'DELETE', headers: { Authorization: MR } });
    console.log(`  [${[200, 204].includes(r.status) ? '已删除' : '失败 ' + r.status}] ${name} version_id=${hit.id}`);
    await sleep(600);
  }
  console.log('\n完成。');
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
