// 优化 27 个项目的 gradle.properties（用户明确授权："给我改Gradle，让它构建快一点"）
// 改动：开启 daemon / parallel / caching / vfs.watch
// 安全：原文件先备份为 gradle.properties.bak_20260920（存在则跳过，不覆盖）
// 保留：各项目已有的 org.gradle.jvmargs / org.gradle.java.home 等一切原有键，只增不删
const fs = require('fs');
const path = require('path');

const BASE = 'D:/桌面/voxlink';
const LOADERS = ['fabric', 'forge', 'neoforge'];
const WANT = [
  ['org.gradle.daemon', 'true'],
  ['org.gradle.parallel', 'true'],
  ['org.gradle.caching', 'true'],
  ['org.gradle.vfs.watch', 'true'],
];

let changed = 0; const report = [];

for (const loader of LOADERS) {
  const ldir = path.join(BASE, loader);
  if (!fs.existsSync(ldir)) continue;
  for (const ver of fs.readdirSync(ldir)) {
    const dir = path.join(ldir, ver);
    if (!fs.statSync(dir).isDirectory()) continue;
    const gp = path.join(dir, 'gradle.properties');
    if (!fs.existsSync(gp)) { report.push(`SKIP ${loader}/${ver}: 无 gradle.properties`); continue; }

    const raw = fs.readFileSync(gp, 'utf8');
    const hadBom = raw.charCodeAt(0) === 0xFEFF;
    let text = hadBom ? raw.slice(1) : raw;

    // 备份
    const bak = gp + '.bak_20260920';
    if (!fs.existsSync(bak)) {
      fs.copyFileSync(gp, bak);
    }

    const before = text;
    const lines = text.split(/\r?\n/);
    const touched = {};

    // 1) 已存在的键 → 改值
    for (let i = 0; i < lines.length; i++) {
      const m = /^\s*(org\.gradle\.[A-Za-z.]+)\s*=\s*(.*)$/.exec(lines[i]);
      if (!m) continue;
      const key = m[1];
      const want = WANT.find(w => w[0] === key);
      if (want && m[2].trim() !== want[1]) {
        touched[key] = `${m[2].trim()} -> ${want[1]}`;
        lines[i] = `${key}=${want[1]}`;
      }
    }
    // 2) 缺失的键 → 追加
    const appended = [];
    for (const [k, v] of WANT) {
      if (!lines.some(l => new RegExp('^\\s*' + k.replace(/\./g, '\\.') + '\\s*=').test(l))) {
        lines.push(`${k}=${v}`);
        appended.push(`${k}=${v}`);
      }
    }

    text = lines.join('\n');
    if (text === before && !appended.length) { report.push(`UNCHANGED ${loader}/${ver}`); continue; }

    fs.writeFileSync(gp, (hadBom ? '\uFEFF' : '') + text, 'utf8');
    changed++;
    const chg = Object.entries(touched).map(([k, v]) => `${k}: ${v}`).join(', ');
    report.push(`OK ${loader}/${ver}  [改动] ${chg || '-'}  [追加] ${appended.join(', ') || '-'}`);
  }
}

console.log(report.join('\n'));
console.log(`\n共修改 ${changed} 个 gradle.properties（原文件已备份为 .bak_20260920）`);
