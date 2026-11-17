// Run: node docs/verification/test_theme_contrast.js
// Loads the real theme.js with a stub Tailwind and checks WCAG contrast of the colours it defines.
const fs = require('fs'), path = require('path'), vm = require('vm');
const root = path.join(__dirname, '..', '..');
const dir = ['springboot_backend/src/src/main/resources/static', 'src/src/main/resources/static', 'src/main/resources/static'].map(d => path.join(root, d)).find(d => fs.existsSync(path.join(d, 'theme.js')));
const ctx = { tailwind: {}, window: {} }; vm.createContext(ctx); vm.runInContext(fs.readFileSync(path.join(dir, 'theme.js'), 'utf8'), ctx);
const colors = ctx.tailwind.config.theme.extend.colors;

const lum = h => { const c = [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255).map(x => x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)); return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; };
const cr = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
let n = 0, bad = 0; const ok = (name, c) => { n++; if (!c) bad++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); };

for (const bg of ['#ffffff', '#f8fafc', '#f1f5f9']) ok(`slate-400 secondary text reaches 4.5:1 on ${bg} (${cr(colors.slate[400], bg).toFixed(2)}:1)`, cr(colors.slate[400], bg) >= 4.5);
ok('slate override only changes shade 400 (other slate shades keep Tailwind defaults)', Object.keys(colors.slate).join() === '400');
ok(`brand-600 buttons: white text reaches 4.5:1 (${cr('#ffffff', colors.brand[600]).toFixed(2)}:1)`, cr('#ffffff', colors.brand[600]) >= 4.5);
ok(`brand-700 hover keeps white text above 4.5:1 (${cr('#ffffff', colors.brand[700]).toFixed(2)}:1)`, cr('#ffffff', colors.brand[700]) >= 4.5);
ok(`brand-600 links on white reach 4.5:1`, cr(colors.brand[600], '#ffffff') >= 4.5);
ok(`navy-950 sidebar with slate-300 text reaches 4.5:1 (${cr('#cbd5e1', colors.navy[950]).toFixed(2)}:1)`, cr('#cbd5e1', colors.navy[950]) >= 4.5);
console.log(`\n${n} checks run - ${bad ? bad + ' FAILED' : 'all passed'}`); process.exit(bad ? 1 : 0);
