// 把 27 个项目的 -Xmx4G 降为 -Xmx2G，并补 -XX:MaxMetaspaceSize=512m
// 原因：daemon=true 后最多会有 3 个 JVM 家族（jdk-17/21/25）的守护进程并存，
//      4G × 3 = 12G 在 15.7GB 机器上必然 OOM（项目里已有 5 份 hs_err OOM 崩溃日志）。
//      108 个源文件用 2G 绰绰有余。
// 保留各项目原有的其它 JVM 参数（如 --add-opens）与所有非 jvmargs 键。
const fs = require('fs');
const path = require('path');

const BASE = 'D:/桌面/voxlink';
const LOADERS = ['fabric', 'forge', 'neoforge'];
const report = [];

for (const loader of LOADERS) {
  const ldir = path.join(BASE, loader);
  if (!fs.existsSync(ldir)) continue;
  for (const ver of fs.readdirSync(ldir)) {
    const dir = path.join(ldir, ver);
    if (!fs.statSync(dir).isDirectory()) continue;
    const gp = path.join(dir, 'gradle.properties');
    if (!fs.existsSync(gp)) continue;

    const raw = fs.readFileSync(gp, 'utf8');
    const hadBom = raw.charCodeAt(0) === 0xFEFF;
    let text = hadBom ? raw.slice(1) : raw;

    const before = text;
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const m = /^\s*org\.gradle\.jvmargs\s*=\s*(.*)$/.exec(lines[i]);
      if (!m) continue;
      let v = m[1].trim();
      const orig = v;
      v = v.replace(/-Xmx\s*\d+[GgMm]/g, '-Xmx2G');
      if (!/-XX:MaxMetaspaceSize=/.test(v)) v += ' -XX:MaxMetaspaceSize=512m';
      if (v !== orig) { lines[i] = `org.gradle.jvmargs=${v}`; report.push(`${loader}/${ver}\n      ${orig}\n   -> ${v}`); }
      else report.push(`${loader}/${ver}: 无需改动 (${v})`);
    }
    text = lines.join('\n');
    if (text !== before) fs.writeFileSync(gp, (hadBom ? '\uFEFF' : '') + text, 'utf8');
  }
}

console.log(report.join('\n'));
console.log('\n完成。');
