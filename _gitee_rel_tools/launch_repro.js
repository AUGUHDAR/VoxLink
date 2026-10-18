// 按版本 JSON 复现启动 Minecraft（Forge），捕获原始 stdout/stderr。
// 目的：Forge 的 log4j 会吞掉部分输出，启动器只报"未知错误"；直跑 java 能拿到真实报错。
// 用法: node launch_repro.js <versionDirAbsPath>
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const VER_DIR = process.argv[2];
if (!VER_DIR) { console.error('用法: node launch_repro.js <版本目录>'); process.exit(1); }
const VER_NAME = path.basename(VER_DIR);
const ROOT = path.resolve(VER_DIR, '..', '..');          // 客户端测试/
const LIBS = path.join(ROOT, 'libraries');
const ASSETS = path.join(ROOT, 'assets');
const NATIVES = path.join(VER_DIR, VER_NAME + '-natives');
const JAVA = 'C:\\Program Files\\Java\\jdk-25.0.2\\bin\\java.exe';
const OUT = path.join(__dirname, 'launch_repro.out.txt');

const json = JSON.parse(fs.readFileSync(path.join(VER_DIR, VER_NAME + '.json'), 'utf8'));

// 拼 classpath：libraries 下每个 library 的 artifact（存在才算）
const cp = [];
function libPath(lib) {
  const a = lib.downloads && lib.downloads.artifact;
  if (a && a.path) return path.join(LIBS, a.path);
  if (lib.name) {
    const [g, n, v] = lib.name.split(':');
    const [nv, cls] = (n + '').split('@');
    return path.join(LIBS, g.replace(/\./g, '/'), nv, v, `${nv}-${v}${cls ? '.' + cls : '.jar'}`);
  }
  return null;
}
for (const lib of (json.libraries || [])) {
  const p = libPath(lib);
  if (p && fs.existsSync(p)) cp.push(p);
}
// Forge 客户端主 jar
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
function subst(s) { return String(s).replace(/\$\{([^}]+)\}/g, (m, k) => (k in vars ? vars[k] : m)); }

const jvmArgs = [];
for (const a of (json.arguments && json.arguments.jvm) || []) {
  if (typeof a === 'object') { if (a.rules) continue; continue; }
  const v = subst(a);
  if (v.includes('${')) continue;
  jvmArgs.push(v);
}
const gameArgs = [];
for (const a of (json.arguments && json.arguments.game) || []) {
  if (typeof a === 'object') continue;
  gameArgs.push(subst(a));
}

const args = [...jvmArgs, json.mainClass, ...gameArgs];
console.log('java =', JAVA);
console.log('mainClass =', json.mainClass);
console.log('classpath 条目 =', cp.length);
console.log('jvmArgs =', jvmArgs.length, ' gameArgs =', gameArgs.length);
console.log('输出 ->', OUT);

const out = fs.createWriteStream(OUT, { encoding: 'utf8' });
const ch = spawn(JAVA, args, { cwd: VER_DIR });
ch.stdout.pipe(out);
ch.stderr.pipe(out);
ch.on('exit', (code, sig) => {
  const line = `\n===== PROCESS EXIT code=${code} signal=${sig} =====\n`;
  out.write(line);
  out.end(() => { console.log(line.trim()); });
});
