// 发布 Gitee Release：创建 release + 上传 成品 下的 jar 附件
// 用法: node gitee_release.js --version 1.1.7 [--dry-run]
// 规则（2026-09-20 移植 GitHub Release 时踩坑所得）：
//   - 创建 release 必须带 target_commitish，且填 tag 自身 SHA（防 tag 漂移）
//   - attach_files 列表默认每页仅 20 条，必须分页，否则去重失效导致重复上传
//   - 附件端点用 release_id（数字），multipart 字段名 file
const fs = require('fs');
const path = require('path');

const OWNER = 'AUGUHDAR', REPO = 'VoxLink';
const API = 'https://gitee.com/api/v5';
const OUT = 'D:/桌面/voxlink/成品';
const PUB = 'D:/桌面/voxlink/tools/publish';

const args = process.argv.slice(2);
const VERSION = (args.find(a => a.startsWith('--version=')) || '').split('=')[1];
const DRY = args.includes('--dry-run');
// 强制重传指定文件名（逗号分隔）：同名但内容变更时用
const FORCE = new Set(((args.find(a => a.startsWith('--force=')) || '').split('=')[1] || '').split(',').map(s => s.trim()).filter(Boolean));
// 版本渠道：默认 beta（pre-release），传 --stable 则按正式版发布
const PRERELEASE = !args.includes('--stable');
const TOKEN = process.env.GT_TOKEN;
if (!VERSION) { console.error('missing --version='); process.exit(1); }
if (!TOKEN) { console.error('missing GT_TOKEN'); process.exit(1); }

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function gt(method, p, { json, form } = {}, attempt = 1) {
  const headers = { 'User-Agent': 'voxlink-publisher' };
  let body;
  if (json !== undefined) { headers['Content-Type'] = 'application/json;charset=UTF-8'; body = JSON.stringify(json); }
  if (form) body = form;
  let res;
  try { res = await fetch(`${API}${p}`, { method, headers, body, redirect: 'follow' }); }
  catch (e) { if (attempt < 5) { await sleep(2000 * attempt); return gt(method, p, { json, form }, attempt + 1); } throw e; }
  if ([429, 502, 503, 504].includes(res.status) && attempt < 6) { await sleep(2500 * attempt); return gt(method, p, { json, form }, attempt + 1); }
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) { const e = new Error(`HTTP ${res.status} ${method} ${p} :: ${String(typeof data === 'string' ? data : JSON.stringify(data)).slice(0, 300)}`); e.status = res.status; e.data = data; throw e; }
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

async function allAttachFiles(releaseId) {
  let all = [], page = 1;
  while (true) {
    const b = await gt('GET', `/repos/${OWNER}/${REPO}/releases/${releaseId}/attach_files?access_token=${TOKEN}&per_page=100&page=${page}`);
    if (!Array.isArray(b) || b.length === 0) break;
    all = all.concat(b);
    if (b.length < 100) break;
    page++;
  }
  return all;
}

async function blobFor(f) { try { return await fs.openAsBlob(f); } catch { return new Blob([fs.readFileSync(f)]); } }

async function main() {
  const body = fs.readFileSync(path.join(PUB, `changelog-${VERSION}.md`), 'utf8');
  const jars = collectJars();
  console.log(`[GITEE] 版本 ${VERSION} | 描述 ${body.length} 字 | jar ${jars.length} 个 | 共 ${(jars.reduce((s, j) => s + j.size, 0) / 1048576).toFixed(2)}MB`);
  if (jars.length !== 27) console.log(`  [警告] 预期 27 个 jar，实得 ${jars.length} 个`);
  if (DRY) { jars.forEach(j => console.log(`  [计划] ${j.name}  ${(j.size / 1048576).toFixed(2)}MB`)); return; }

  // 1) 取 tag 自身 SHA 作为 target_commitish
  const tags = await gt('GET', `/repos/${OWNER}/${REPO}/tags?access_token=${TOKEN}&per_page=100`);
  const t = (Array.isArray(tags) ? tags : []).find(x => x.name === VERSION);
  if (!t) { console.error(`  [错误] Gitee 上没有 tag ${VERSION}，请先 git push gitee ${VERSION}`); process.exit(1); }
  const sha = t.commit.sha;
  console.log(`  [tag] ${VERSION} -> ${sha.slice(0, 10)}`);

  // 2) 创建 / 复用 release
  let rel = null;
  try { rel = await gt('GET', `/repos/${OWNER}/${REPO}/releases/tags/${VERSION}?access_token=${TOKEN}`); }
  catch (e) { if (e.status !== 404) throw e; }

  if (rel && rel.id) {
    console.log(`  [复用] 已存在 release id=${rel.id}`);
    rel = await gt('PATCH', `/repos/${OWNER}/${REPO}/releases/${rel.id}`, { json: { access_token: TOKEN, tag_name: VERSION, name: VERSION, body, prerelease: PRERELEASE } });
  } else {
    rel = await gt('POST', `/repos/${OWNER}/${REPO}/releases`, { json: {
      access_token: TOKEN, tag_name: VERSION, target_commitish: sha, name: VERSION, body, prerelease: PRERELEASE
    } });
    console.log(`  [创建] release id=${rel.id} tag=${rel.tag_name}`);
  }

  // 3) 附件去重 + 上传
  const existing = await allAttachFiles(rel.id);
  const byName = new Map(existing.map(f => [f.name, f]));
  let up = 0, skip = 0, fail = 0; const failed = [];
  for (const j of jars) {
    const exList = existing.filter(f => f.name === j.name);
    const force = FORCE.has(j.name);
    if (exList.length && !force) { skip++; console.log(`  [跳过-已存在] ${j.name}`); continue; }
    if (exList.length && force) {
      // 内容变更（大小可能相同）→ 先删旧的再传新的
      for (const f of exList) {
        await gt('DELETE', `/repos/${OWNER}/${REPO}/releases/${rel.id}/attach_files/${f.id}?access_token=${TOKEN}`);
        console.log(`  [覆盖] 删除旧 ${j.name} id=${f.id}`);
        await sleep(400);
      }
    }
    let ok = false;
    for (let attempt = 1; attempt <= 5 && !ok; attempt++) {
      try {
        const fd = new FormData();
        fd.append('access_token', TOKEN);
        fd.append('file', await blobFor(j.p), j.name);
        const r = await gt('POST', `/repos/${OWNER}/${REPO}/releases/${rel.id}/attach_files`, { form: fd });
        up++; ok = true; console.log(`  [上传 ${up}] ${j.name} (${(j.size / 1048576).toFixed(2)}MB) -> id=${r && r.id}`);
      } catch (e) {
        if (attempt === 5) { fail++; failed.push(`${j.name}: ${e.message}`); console.log(`  [失败] ${j.name}: ${e.message}`); }
        else await sleep(2500 * attempt);
      }
    }
    await sleep(150);
  }
  console.log(`\n[GITEE] 上传 ${up} 跳过 ${skip} 失败 ${fail}`);
  console.log(`[GITEE] 页面: https://gitee.com/${OWNER}/${REPO}/releases/tag/${VERSION}`);
  if (failed.length) { fs.writeFileSync('D:/桌面/voxlink/_gitee_rel_tools/gitee_upload_failed.txt', failed.join('\n'), 'utf8'); process.exit(1); }
}
main().catch(e => { console.error('FATAL', e.message); process.exit(1); });
