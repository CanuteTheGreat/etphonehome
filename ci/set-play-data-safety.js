// Writes the Play Data safety declaration from store/data-safety.csv (Play's CSV import format)
// via applications.dataSafety. store/data-safety.md explains every answer. Play has no read-back
// endpoint: success is a 2xx here; on a 400 the message names the question to fix.
// Usage: node ci/set-play-data-safety.js <key.json> <package> <csv>
'use strict';
const fs = require('fs');
const { API, getAccessToken, call, jsonBody } = require('./play-api');

async function main() {
  const [, , keyPath, pkg, csvPath] = process.argv;
  if (!keyPath || !pkg || !csvPath) {
    console.error('Usage: node ci/set-play-data-safety.js <key.json> <package> <csv>');
    process.exit(2);
  }
  const csv = fs.readFileSync(csvPath, 'utf8');
  const answered = csv.split('\n').filter((l) => /,(TRUE|FALSE),/.test(l)).length;
  const auth = await getAccessToken(keyPath);
  console.log(`Submitting ${csvPath}: ${csv.length} bytes, ${answered} answered rows, as ${auth.email}`);
  const r = await call(auth, 'POST', `${API}/${pkg}/dataSafety`, ...jsonBody({ safetyLabels: csv }));
  console.log(`HTTP ${r.status} ${Object.keys(r.json).length ? JSON.stringify(r.json) : ''}`);
  if (!r.ok) process.exit(1);
  console.log('Data safety declaration accepted by the API.');
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
