// Forge 26.3 客户端启动对照测试
// 用法: node launch_test_20260923.js <被测jar绝对路径> <超时秒> <输出文件>
// 逻辑: 清 mods -> 放被测 jar -> 直跑 java 复现启动 -> 到点 taskkill -> 输出原始日志
const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');

const JAR = process.argv[2];
const TIMEOUT_S = parseInt(process.argv[3] || '90', 10);
const OUT = process.argv[4];
if (!JAR || !OUT) { console.error('用法: node launch_test_20260923.js <jar> <超时秒> <输出文件>'); process.exit(1); }

const VER_DIR = 'D:\\桌面\\voxlink\\客户端测试\\versions\\26.3-Forge_66.0.2';
const VER_NAME = '26.3-Forge_66.0.2';
const ROOT = path.resolve(VER_DIR, '..', '..');
const LIBS = path.join(ROOT, 'libraries');
const ASSETS = path.join(ROOT, 'assets');
const NATIVES = path.join(VER_DIR, VER_NAME + '-natives');
const MODS = path.join(VER_DIR, 'mods');
const JAVA = 'C:\\Program Files\\Java\\jdk-25.0.2\\bin\\java.exe';

// --- 1. 只留被测 jar ---
fs.mkdirSync(MODS, { recursive: true });
for (const f of fs.readdirSync(MODS)) {
  if (f.toLowerCase().includes('voxlink')) fs.unlinkSync(path.join(MODS, f));
}
const jarName = 'voxlink-test-target.jar';
fs.copyFileSync(JAR, path.join(MODS, jarName));
console.log('被测 jar ->', path.join(MODS, jarName), fs.statSync(JAR).size, 'bytes');

// --- 2. 复现 classpath ---
const json = JSON.parse(fs.readFileSync(path.join(VER_DIR, VER_NAME + '.json'), 'utf8'));
const cp = [];
for (const lib of (json.libraries || [])) {
  const a = lib.downloads && lib.downloads.artifact;
  let p = null;
  if (a && a.path) p = path.join(LIBS, a.path);
  else if (lib.name) {
    const [g, n, v] = lib.name.split(':');
    const [nv, cls] = (n + '').split('@');
    p = path.join(LIBS, g.replace(/\./g, '/'), nv, v, `${nv}-${v}${cls ? '.' + cls : '.jar'}`);
  }
  if (p && fs.existsSync(p)) cp.push(p);
}
const mainJar = path.join(VER_DIR, VER_NAME + '.jar');
if (fs.existsSync(mainJar)) cp.push(mainJar);

const vars = {
  auth_player_name: 'AUGUHDAR', version_name: VER_NAME, game_directory: VER_DIR,
  assets_root: ASSETS, assets_index_name: '34',
  auth_uuid: 'f45725fc6a55449f9eaf8625a4a3b7ae', auth_access_token: '0', clientid: '0', auth_xuid: '0',
  user_type: 'msa', version_type: 'PCL',
  natives_directory: NATIVES, launcher_name: 'repro', launcher_version: '1',
  classpath: cp.join(';'), classpath_separator: ';', library_directory: LIBS,
  resolution_width: '1280', resolution_height: '720',
};
const subst = s => String(s).replace(/\$\{([^}]+)\}/g, (m, k) => (k in vars ? vars[k] : m));

const jvmArgs = [];
for (const a of (json.arguments && json.arguments.jvm) || []) {
  if (typeof a === 'object') continue;
  const v = subst(a);
  if (v.includes('${')) continue;
  jvmArgs.push(v);
}
const gameArgs = [];
for (const a of (json.arguments && json.arguments.game) || []) {
  if (typeof a === 'object') continue;
  gameArgs.push(subst(a));
}

console.log('启动中，最长', TIMEOUT_S, '秒 ...');
const out = fs.createWriteStream(OUT, { encoding: 'utf8' });
const ch = spawn(JAVA, [...jvmArgs, json.mainClass, ...gameArgs], { cwd: VER_DIR });
ch.stdout.pipe(out);
ch.stderr.pipe(out);

let exited = false;
ch.on('exit', (code, sig) => {
  exited = true;
  out.write(`\n===== PROCESS EXIT code=${code} signal=${sig} =====\n`);
  out.end(() => console.log(`进程自行退出 code=${code} signal=${sig}`));
});

setTimeout(() => {
  if (!exited) {
    try { execSync(`taskkill /F /T /PID ${ch.pid}`, { stdio: 'ignore' }); } catch (e) {}
    out.write(`\n===== TIMEOUT ${TIMEOUT_S}s, KILLED =====\n`);
    out.end(() => console.log(`到达 ${TIMEOUT_S}s 超时，已终止进程`));
  }
}, TIMEOUT_S * 1000);
