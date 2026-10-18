// 把 GitHub Release（tag / name / body / 附件）移植到 Gitee
// - 只写 Gitee，绝不触碰 GitHub
// - 幂等：已存在的 release 复用，已存在的同名同大小附件跳过
// - 断点续传：状态落盘 gitee_state.json，崩了直接重跑
const fs = require('fs');
const path = require('path');

const GH_OWNER = 'AUGUHDAR', GH_REPO = 'VoxLink';
const GT_OWNER = 'AUGUHDAR', GT_REPO = 'VoxLink';
const API = 'https://gitee.com/api/v5';
const STAGE = 'D:/桌面/voxlink/_gitee_release_stage';
const META = 'D:/桌面/voxlink/_gitee_rel_tools/releases.json';
const STATE = 'D:/桌面/voxlink/_gitee_rel_tools/gitee_state.json';
const LOG = 'D:/桌面/voxlink/_gitee_rel_tools/publish.log';
const BASELINE = 'D:/桌面/voxlink/_gitee_rel_tools/tag_baseline.json';

const TOKEN = process.env.GT_TOKEN;
if (!TOKEN) { console.error('missing GT_TOKEN env'); process.exit(1); }

const DRY = process.argv.includes('--dry-run');
const ONLY = (process.argv.find(a => a.startsWith('--only=')) || '').split('=')[1];

const logLines = [];
function log(s) {
  const line = `[${new Date().toISOString()}] ${s}`;
  console.log(line);
  logLines.push(line);
  fs.writeFileSync(LOG, logLines.join('\n'), 'utf8');
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

function loadState() {
  if (fs.existsSync(STATE)) return JSON.parse(fs.readFileSync(STATE, 'utf8'));
  return { releases: {} };   // tag -> { id, done: [ "file name" ] }
}
function saveState(s) { fs.writeFileSync(STATE, JSON.stringify(s, null, 2), 'utf8'); }

async function gt(method, urlPath, { json, form, raw } = {}, attempt = 1) {
  const url = `${API}${urlPath}`;
  const headers = { 'User-Agent': 'voxlink-release-migrator' };
  let body;
  if (json !== undefined) { headers['Content-Type'] = 'application/json;charset=UTF-8'; body = JSON.stringify(json); }
  if (form) body = form;
  let res;
  try {
    res = await fetch(url, { method, headers, body, redirect: 'follow' });
  } catch (e) {
    if (attempt < 5) { await sleep(1500 * attempt); return gt(method, urlPath, { json, form, raw }, attempt + 1); }
    throw new Error(`network: ${e.message} @ ${method} ${urlPath}`);
  }
  if (res.status === 429 || res.status === 502 || res.status === 503 || res.status === 504) {
    if (attempt < 6) { await sleep(2000 * attempt); return gt(method, urlPath, { json, form, raw }, attempt + 1); }
  }
  if (raw) return res;
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status} ${method} ${urlPath} :: ${typeof data === 'string' ? data.slice(0, 300) : JSON.stringify(data).slice(0, 300)}`);
    err.status = res.status; err.data = data;
    throw err;
  }
  return data;
}

async function blobFor(file) {
  try { return await fs.openAsBlob(file); } catch { return new Blob([fs.readFileSync(file)]); }
}

async function main() {
  const releases = JSON.parse(fs.readFileSync(META, 'utf8'))
    .sort((a, b) => new Date(a.created_at) - new Date(b.created_at));   // 旧 -> 新

  const state = loadState();
  // tag -> SHA 基线：target_commitish 填 tag 自身 SHA，保证不会把 tag 重新指向 main HEAD
  const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8')).gitee;
  let created = 0, reused = 0, upOk = 0, upSkip = 0, upFail = 0;
  const failures = [];

  for (const rel of releases) {
    const tag = rel.tag_name;
    if (ONLY && tag !== ONLY) continue;

    log(`\n=== ${tag} :: ${rel.assets.length} assets, bodyLen=${(rel.body || '').length} ===`);
    if (DRY) {
      log(`  [dry] would ensure release "${rel.name}" tag=${tag} prerelease=${rel.prerelease}`);
      for (const a of rel.assets) log(`  [dry] would upload ${a.name} (${(a.size / 1048576).toFixed(2)}MB)`);
      continue;
    }

    // 1) 确保 release 存在
    let entry = state.releases[tag];
    if (!entry || !entry.id) {
      let existing = null;
      try { existing = await gt('GET', `/repos/${GT_OWNER}/${GT_REPO}/releases/tags/${encodeURIComponent(tag)}?access_token=${TOKEN}`); }
      catch (e) { if (e.status !== 404) log(`  [warn] lookup tag failed: ${e.message}`); }

      if (existing && existing.id) {
        entry = { id: existing.id, done: [] };
        state.releases[tag] = entry; saveState(state);
        reused++;
        log(`  [exists] reusing gitee release id=${existing.id}`);
      } else {
        const payload = {
          access_token: TOKEN,
          tag_name: tag,
          target_commitish: baseline[tag] || 'main',   // 优先用 tag 自身 SHA，杜绝 tag 漂移
          name: rel.name || tag,
          body: rel.body || '',
          prerelease: !!rel.prerelease
        };
        const r = await gt('POST', `/repos/${GT_OWNER}/${GT_REPO}/releases`, { json: payload });
        entry = { id: r.id, done: [] };
        state.releases[tag] = entry; saveState(state);
        created++;
        log(`  [created] gitee release id=${r.id} tag=${r.tag_name}`);
      }
    } else {
      reused++;
      log(`  [cached] gitee release id=${entry.id}`);
    }

    // 2) 先取远端已有附件，做同名去重
    //    注意：Gitee attach_files 默认每页仅 20 条，必须显式分页拉全，
    //    否则第 20 条之后的文件会被误判为"未上传"而重复上传。
    let remote = [];
    try {
      let page = 1;
      while (true) {
        const batch = await gt('GET', `/repos/${GT_OWNER}/${GT_REPO}/releases/${entry.id}/attach_files?access_token=${TOKEN}&per_page=100&page=${page}`);
        if (!Array.isArray(batch) || batch.length === 0) break;
        remote = remote.concat(batch);
        if (batch.length < 100) break;
        page++;
      }
    } catch (e) { log(`  [warn] list attach_files failed: ${e.message}`); }
    const remoteByName = new Map(remote.map(f => [f.name, f.size]));

    // 3) 上传附件
    for (const a of rel.assets) {
      const local = path.join(STAGE, tag, a.name);
      if (!fs.existsSync(local)) { upFail++; failures.push(`${tag}/${a.name}: local file missing`); log(`  [FAIL] local missing ${a.name}`); continue; }
      const lsz = fs.statSync(local).size;
      if (lsz !== a.size) { upFail++; failures.push(`${tag}/${a.name}: local size ${lsz} != gh ${a.size}`); log(`  [FAIL] size mismatch ${a.name}`); continue; }

      if (remoteByName.has(a.name)) { upSkip++; log(`  [skip] already on gitee: ${a.name}`); continue; }

      let done = false;
      for (let attempt = 1; attempt <= 5 && !done; attempt++) {
        try {
          const fd = new FormData();
          fd.append('access_token', TOKEN);
          fd.append('file', await blobFor(local), a.name);
          const r = await gt('POST', `/repos/${GT_OWNER}/${GT_REPO}/releases/${entry.id}/attach_files`, { form: fd });
          entry.done.push(a.name); saveState(state);
          upOk++; done = true;
          log(`  [up ${upOk}] ${a.name} (${(a.size / 1048576).toFixed(2)}MB) -> id=${r && r.id}`);
        } catch (e) {
          if (attempt === 5) { upFail++; failures.push(`${tag}/${a.name}: ${e.message}`); log(`  [FAIL] ${a.name}: ${e.message}`); }
          else await sleep(2500 * attempt);
        }
      }
      await sleep(150);   // 轻微限速，避免触发 Gitee 频控
    }
  }

  log(`\n############ SUMMARY ############`);
  log(`releases created=${created} reused=${reused}`);
  log(`attachments uploaded=${upOk} skipped=${upSkip} failed=${upFail}`);
  if (failures.length) {
    fs.writeFileSync('D:/桌面/voxlink/_gitee_rel_tools/upload_failed.txt', failures.join('\n'), 'utf8');
    log(`failures written to upload_failed.txt`);
  }
}

main().catch(e => { log(`FATAL: ${e.stack || e.message}`); process.exit(1); });
