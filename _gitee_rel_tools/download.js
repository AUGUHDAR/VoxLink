// 从 GitHub 下载 VoxLink 全部 Release 附件到本地暂存区（只读，不修改 GitHub）
const fs = require('fs');
const path = require('path');

const REPO = 'AUGUHDAR/VoxLink';
const STAGE = 'D:/桌面/voxlink/_gitee_release_stage';
const META = 'D:/桌面/voxlink/_gitee_rel_tools/releases.json';

async function main() {
  fs.mkdirSync(STAGE, { recursive: true });

  let releases;
  if (!fs.existsSync(META)) {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, {
      headers: { 'User-Agent': 'voxlink-release-migrator' }
    });
    releases = await r.json();
    fs.writeFileSync(META, JSON.stringify(releases, null, 2), 'utf8');
  } else {
    releases = JSON.parse(fs.readFileSync(META, 'utf8'));
  }
  console.log(`[meta] releases=${releases.length}`);

  // 按创建时间升序（旧 -> 新），保证 Gitee 侧"最新发行版"落在最高版本
  releases.sort((a, b) => new Date(a.created_at) - new Date(b.created_at));

  const jobs = [];
  for (const rel of releases) {
    const dir = path.join(STAGE, rel.tag_name);
    fs.mkdirSync(dir, { recursive: true });
    for (const a of rel.assets) jobs.push({ rel, asset: a, dir, dest: path.join(dir, a.name) });
  }
  console.log(`[plan] assets=${jobs.length}`);

  let ok = 0, skip = 0, fail = 0;
  const failed = [];

  async function downloadOne(job, attempt = 1) {
    const { asset, dest } = job;
    try {
      if (fs.existsSync(dest) && fs.statSync(dest).size === asset.size) { skip++; return; }
      const res = await fetch(asset.browser_download_url, {
        headers: { 'User-Agent': 'voxlink-release-migrator', 'Accept': 'application/octet-stream' },
        redirect: 'follow'
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length !== asset.size) throw new Error(`size mismatch got=${buf.length} want=${asset.size}`);
      fs.writeFileSync(dest, buf);
      ok++;
      process.stdout.write(`[ok ${ok + skip}/${jobs.length}] ${job.rel.tag_name}/${asset.name}\n`);
    } catch (e) {
      if (attempt < 4) {
        await new Promise(r => setTimeout(r, 2000 * attempt));
        return downloadOne(job, attempt + 1);
      }
      fail++;
      failed.push(`${job.rel.tag_name}/${asset.name}: ${e.message}`);
      process.stdout.write(`[FAIL] ${job.rel.tag_name}/${asset.name}: ${e.message}\n`);
    }
  }

  const CONC = 5;
  let idx = 0;
  await Promise.all(Array.from({ length: CONC }, async () => {
    while (idx < jobs.length) {
      const my = jobs[idx++];
      await downloadOne(my);
    }
  }));

  console.log(`\n=== DONE ok=${ok} skip=${skip} fail=${fail} total=${jobs.length} ===`);
  if (failed.length) fs.writeFileSync('D:/桌面/voxlink/_gitee_rel_tools/download_failed.txt', failed.join('\n'), 'utf8');
  // 汇总校验
  let bytes = 0;
  for (const rel of releases) for (const a of rel.assets) {
    const p = path.join(STAGE, rel.tag_name, a.name);
    if (fs.existsSync(p)) bytes += fs.statSync(p).size;
  }
  console.log(`local bytes = ${(bytes / 1048576).toFixed(2)} MB`);
}
main().catch(e => { console.error(e); process.exit(1); });
