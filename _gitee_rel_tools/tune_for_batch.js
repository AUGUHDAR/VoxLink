// 批量构建专用 Gradle 配置
// 依据实测：daemon=true 会让守护进程常驻并长期持有 D:\.gradle\caches\journal-1\journal-1.lock，
// 导致后续构建 "拒绝访问" 秒失败（连续 5 个成功后必现）。
// 因此批量场景改为：不用守护进程 + 并行执行 + 单次 JVM 用完即退。
// 速度来自并行度，而不是守护进程复用。
const fs = require('fs');
const path = require('path');

const BASE = 'D:/桌面/voxlink';
const LOADERS = ['fabric', 'forge', 'neoforge'];
const WANT = [
  ['org.gradle.daemon', 'false'],      // 关键：避免 journal-1.lock 被常驻占用
  ['org.gradle.vfs.watch', 'false'],   // 无守护进程时无意义，且会加重 journal 锁压力
  ['org.gradle.parallel', 'true'],     // 项目内并行（无害）
  ['org.gradle.caching', 'true'],      // 构建缓存：clean 后仍能 FROM-CACHE，是主要提速项
];
const XMX = '-Xmx1536m -XX:MaxMetaspaceSize=512m';   // 4 并发 × 1.5G ≈ 6G，可控

const report = [];
let changed = 0;

for (const loader of LOADERS) {
  const ldir = path.join(BASE, loader);
  for (const ver of fs.readdirSync(ldir)) {
    const dir = path.join(ldir, ver);
    if (!fs.statSync(dir).isDirectory()) continue;
    const gp = path.join(dir, 'gradle.properties');
    if (!fs.existsSync(gp)) continue;

    const raw = fs.readFileSync(gp, 'utf8');
    const hadBom = raw.charCodeAt(0) === 0xFEFF;
    let text = hadBom ? raw.slice(1) : raw;
    const before = text;
    let lines = text.split(/\r?\n/);
    const notes = [];

    // 1) 处理目标键
    for (let i = 0; i < lines.length; i++) {
      const m = /^\s*(org\.gradle\.[A-Za-z.]+)\s*=\s*(.*)$/.exec(lines[i]);
      if (!m) continue;
      const key = m[1], cur = m[2].trim();
      const w = WANT.find(x => x[0] === key);
      if (w && cur !== w[1]) { lines[i] = `${key}=${w[1]}`; notes.push(`${key}: ${cur} -> ${w[1]}`); }
    }
    // 2) 缺失的键补上
    for (const [k, v] of WANT) {
      if (!lines.some(l => new RegExp('^\\s*' + k.replace(/\./g, '\\.') + '\\s*=').test(l))) {
        lines.push(`${k}=${v}`);
        notes.push(`新增 ${k}=${v}`);
      }
    }
    // 3) jvmargs 统一（保留 --add-opens 等原有附加参数）
    for (let i = 0; i < lines.length; i++) {
      const m = /^\s*org\.gradle\.jvmargs\s*=\s*(.*)$/.exec(lines[i]);
      if (!m) continue;
      const cur = m[1].trim();
      const extra = cur.replace(/-Xmx\s*\d+[GgMm]/g, '').replace(/-XX:MaxMetaspaceSize=\S+/g, '').trim();
      const next = (XMX + (extra ? ' ' + extra : '')).trim();
      if (next !== cur) { lines[i] = `org.gradle.jvmargs=${next}`; notes.push(`jvmargs: ${cur} -> ${next}`); }
    }

    text = lines.join('\n');
    if (text !== before) {
      fs.writeFileSync(gp, (hadBom ? '\uFEFF' : '') + text, 'utf8');
      changed++;
      report.push(`${loader}/${ver}\n    ` + notes.join('\n    '));
    }
  }
}
console.log(report.join('\n'));
console.log(`\n共修改 ${changed} 个文件`);
