// Урезает GeoIP.dat / GeoSite.dat (формат v2ray) до категорий, которые использует Halo VPN для Android.
// Полные базы весят ~90 МБ — для телефона это много; урезанные кладём в APK (app/src/main/assets/geo).
// Запуск: node tools/trim-geo.cjs <папка с полными базами> <папка назначения>
const fs = require('node:fs');
const path = require('node:path');

const KEEP = {
  // cn — ядро загружает её при проверке файла; без неё считает базу битой и качает полную (90 МБ).
  'GeoIP.dat': ['ru-blocked', 'telegram', 'ru', 'cn'],
  'GeoSite.dat': [
    'ru-blocked', 'ru-available-only-inside', 'category-ru', 'category-ads-all', 'cn', // cn — ядро проверяет по ней, что база целая
    'youtube', 'instagram', 'facebook', 'twitter', 'category-ai-!cn', 'discord', 'telegram', 'spotify', 'category-public-tracker',
    'netflix', 'disney', 'notion', 'figma', 'canva', 'jetbrains', 'docker', 'intel', 'nvidia', 'autodesk', 'soundcloud', 'patreon', 'medium', 'tiktok',
  ],
};

function trim(src, dst, keep) {
  const b = fs.readFileSync(src);
  let i = 0;
  const varint = () => {
    let x = 0, s = 0, c;
    do { c = b[i++]; x += (c & 127) * 2 ** s; s += 7; } while (c & 128);
    return x;
  };
  const parts = [];
  const found = new Set();
  while (i < b.length) {
    const start = i;
    const tag = varint();
    const len = varint();
    const body = i;
    const end = i + len;
    if (tag >> 3 === 1) {
      varint(); // тег поля code
      const l = varint();
      const code = b.subarray(i, i + l).toString('utf8').toLowerCase();
      if (keep.includes(code)) { parts.push(b.subarray(start, end)); found.add(code); }
    }
    i = end;
    void body;
  }
  fs.writeFileSync(dst, Buffer.concat(parts));
  const missing = keep.filter((c) => !found.has(c));
  console.log(`${path.basename(dst)}: ${(fs.statSync(dst).size / 1048576).toFixed(1)} МБ, категорий ${found.size}${missing.length ? `, нет: ${missing.join(', ')}` : ''}`);
}

const [from, to] = process.argv.slice(2);
fs.mkdirSync(to, { recursive: true });
for (const [file, keep] of Object.entries(KEEP)) trim(path.join(from, file), path.join(to, file), keep);
