// Read-only: does the Play Console app exist, and may the service account manage it?
// Opens an edit, lists the tracks, deletes the edit (nothing is committed).
// Usage: node ci/play-app-status.js <key.json> <package> [--allow-missing]
//   exit 0  app exists and the account has access (tracks printed)
//   exit 0  app does not exist yet, with --allow-missing (the dry run before the owner creates it)
//   exit 3  app does not exist yet (without --allow-missing)
//   exit 4  app exists but the account has no permission on it
'use strict';
const { API, getAccessToken, classify, call } = require('./play-api');

async function main() {
  const [, , keyPath, pkg, flag] = process.argv;
  if (!keyPath || !pkg) {
    console.error('Usage: node ci/play-app-status.js <key.json> <package> [--allow-missing]');
    process.exit(2);
  }
  const auth = await getAccessToken(keyPath);
  console.log(`Service account: ${auth.email}`);
  const edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
  if (!edit.ok) {
    const kind = classify(edit.status, edit.json);
    const msg = (edit.json.error && edit.json.error.message) || JSON.stringify(edit.json);
    if (kind === 'missing') {
      console.log(`Play app ${pkg}: NOT FOUND (HTTP ${edit.status}: ${msg})`);
      console.log('Either the app is not created yet, or it was created but has no bundle yet: a new Play app gets');
      console.log('its package name with the FIRST bundle, uploaded by hand in Play Console (Test and release >');
      console.log('Internal testing > Create new release). The API can upload only after that.');
      process.exit(flag === '--allow-missing' ? 0 : 3);
    }
    if (kind === 'denied') {
      console.log(`Play app ${pkg}: EXISTS, but ${auth.email} has NO PERMISSION (HTTP ${edit.status}: ${msg})`);
      console.log('Play Console > Users and permissions > this account > App permissions > Add app.');
      process.exit(4);
    }
    throw new Error(`edits.insert HTTP ${edit.status}: ${msg}`);
  }
  const tracks = await call(auth, 'GET', `${API}/${pkg}/edits/${edit.json.id}/tracks`);
  await call(auth, 'DELETE', `${API}/${pkg}/edits/${edit.json.id}`);
  console.log(`Play app ${pkg}: exists, ${auth.email} has access.`);
  for (const t of (tracks.json.tracks || [])) {
    const rel = (t.releases || []).map((r) => `${r.status} ${JSON.stringify(r.versionCodes || [])}`).join('; ');
    console.log(`  track ${t.track}: ${rel || '(no releases)'}`);
  }
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
