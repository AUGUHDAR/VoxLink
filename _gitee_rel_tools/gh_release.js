// 发布 GitHub Release：创建 release + 上传 成品 下的 jar 附件
// 用法: node gh_release.js --version 1.1.7 [--dry-run]
// Token: 环境变量 GH_TOKEN（由调用方从 git credential 取出，不落盘）
const fs = require('fs');
const path = require('path');

const OWNER = 'AUGUHDAR', REPO = 'VoxLink';
const API = 'https://api.github.com';
const UPLOADS = 'https://uploads.github.com';
const OUT = 'D:/桌面/voxlink/成品';
const PUB = 'D:/桌面/voxlink/tools/publish';

const args = process.argv.slice(2);
const VERSION = (args.find(a => a.startsWith('--version=')) || '').split('=')[1];
const DRY = args.includes('--dry-run');
// 强制重传指定文件名（逗号分隔），用于同名内容变更
const FORCE = new Set(((args.find(a => a.startsWith('--force=')) || '').split('=')[1] || '').split(',').map(s => s.trim()).filter(Boolean));
// 版本渠道：默认 beta（pre-release），传 --stable 则按正式版发布
const PRERELEASE = !args.includes('--stable');
const TOKEN = process.env.GH_TOKEN;
if (!VERSION) { console.error('missing --version='); process.exit(1); }
if (!TOKEN) { console.error('missing GH_TOKEN'); process.exit(1); }

const H = { 'Authorization': `Bearer ${TOKEN}`, 'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'voxlink-publisher' };

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(method, url, body, attempt = 1) {
  const res = await fetch(url, { method, headers: H, body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 403 || res.status === 429 || res.status >= 500) {
    if (attempt < 5) { await sleep(2500 * attempt); return api(method, url, body, attempt + 1); }
  }
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) { const e = new Error(`HTTP ${res.status} ${method} ${url} :: ${String(typeof data === 'string' ? data : JSON.stringify(data)).slice(0, 300)}`); e.status = res.status; throw e; }
  return data;
}

function collectJars() {
  const items = [];
  for (const sub of fs.readdirSync(OUT)) {
    const d = path.join(OUT, sub);
    if (!fs.statSync(d).isDirectory()) continue;
    for (const f of fs.readdirSync(d)) {
      if (!f.startsWith(`voxlink-${VERSION}-`) || !f.endsWith('.jar')) continue;
      if (f.includes('-sources') || f.includes('-dev')) continue;
      const p = path.join(d, f);
      items.push({ name: f, p, size: fs.statSync(p).size });
    }
  }
  items.sort((a, b) => a.name.localeCompare(b.name));
  return items;
}

async function uploadAsset(releaseId, file) {
  const url = `${UPLOADS}/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?name=${encodeURIComponent(file.name)}`;
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const buf = fs.readFileSync(file.p);
      const res = await fetch(url, {
        method: 'POST',
        headers: { ...H, 'Content-Type': 'application/java-archive', 'Content-Length': String(buf.length) },
        body: buf
      });
      const t = await res.text();
      if (res.ok) return JSON.parse(t);
      if (attempt === 5) throw new Error(`HTTP ${res.status} ${t.slice(0, 200)}`);
    } catch (e) { if (attempt === 5) throw e; }
    await sleep(2500 * attempt);
  }
}

async function main() {
  const body = fs.readFileSync(path.join(PUB, `changelog-${VERSION}.md`), 'utf8');
  const jars = collectJars();
  console.log(`[GH] 版本 ${VERSION} | 描述 ${body.length} 字 | jar ${jars.length} 个 | 共 ${(jars.reduce((s, j) => s + j.size, 0) / 1048576).toFixed(2)}MB`);
  if (jars.length !== 28) console.log(`  [警告] 预期 28 个 jar，实得 ${jars.length} 个`);

  if (DRY) { jars.forEach(j => console.log(`  [计划] ${j.name}  ${(j.size / 1048576).toFixed(2)}MB`)); return; }

  // 1) 确保 tag 已存在于远端（由 git push 负责，这里只校验）
  try {
    await api('GET', `${API}/repos/${OWNER}/${REPO}/git/ref/tags/${VERSION}`);
    console.log(`  [tag] ${VERSION} 已存在于远端`);
  } catch (e) {
    if (e.status === 404) { console.error(`  [错误] 远端没有 tag ${VERSION}，请先 git push origin ${VERSION}`); process.exit(1); }
    throw e;
  }

  // 2) 创建 / 复用 release
  let rel = null;
  try { rel = await api('GET', `${API}/repos/${OWNER}/${REPO}/releases/tags/${VERSION}`); }
  catch (e) { if (e.status !== 404) throw e; }

  if (rel) {
    console.log(`  [复用] 已存在 release id=${rel.id}`);
    rel = await api('PATCH', `${API}/repos/${OWNER}/${REPO}/releases/${rel.id}`, { name: VERSION, body, draft: false, prerelease: PRERELEASE });
  } else {
    rel = await api('POST', `${API}/repos/${OWNER}/${REPO}/releases`, {
      tag_name: VERSION, name: VERSION, body, draft: false, prerelease: PRERELEASE
    });
    console.log(`  [创建] release id=${rel.id} tag=${rel.tag_name}`);
  }

  // 3) 附件去重 + 上传
  const existing = await api('GET', `${API}/repos/${OWNER}/${REPO}/releases/${rel.id}/assets?per_page=100`);
  const byName = new Map(existing.map(a => [a.name, a]));
  let up = 0, skip = 0, fail = 0;
  const failed = [];
  for (const j of jars) {
    const ex = byName.get(j.name);
    // FORCE：同名但内容变了的文件（大小可能完全相同，比如只改了一个等长字符串）必须强制覆盖，
    // 仅靠 size 去重会漏掉这种情况。
    if (ex && !FORCE.has(j.name) && ex.size === j.size) { skip++; console.log(`  [跳过-已存在] ${j.name}`); continue; }
    if (ex) { await api('DELETE', `${API}/repos/${OWNER}/${REPO}/releases/assets/${ex.id}`); console.log(`  [覆盖] 删除旧 ${j.name}`); }
    try {
      const r = await uploadAsset(rel.id, j);
      up++; console.log(`  [上传 ${up}] ${j.name} (${(j.size / 1048576).toFixed(2)}MB) -> id=${r.id}`);
    } catch (e) { fail++; failed.push(`${j.name}: ${e.message}`); console.log(`  [失败] ${j.name}: ${e.message}`); }
    await sleep(120);
  }
  console.log(`\n[GH] 上传 ${up} 跳过 ${skip} 失败 ${fail}`);
  console.log(`[GH] 页面: https://github.com/${OWNER}/${REPO}/releases/tag/${VERSION}`);
  if (failed.length) { fs.writeFileSync('D:/桌面/voxlink/_gitee_rel_tools/gh_upload_failed.txt', failed.join('\n'), 'utf8'); process.exit(1); }
}
main().catch(e => { console.error('FATAL', e.message); process.exit(1); });
