// Read-only dump of everything the Play Developer API says about the app's testing setup, for
// "the opt-in link 404s" / "testers can't see the app" questions. Commits nothing: opens an edit,
// reads, validates it (a no-op edit, so Play lists what would block a commit), deletes it.
// Usage: node ci/play-diagnose.js <key.json> <package>
//   tracks (status, userFraction, countryTargeting, versionCodes), testers per testing track
//   (googleGroups; email lists are Console-only and never appear here), country availability per
//   testing track, release lifecycle state (tracks.releases.list: DRAFT / IN_REVIEW / PUBLISHED /
//   rejected), app details, listings, bundles in the library.
'use strict';
const { API, getAccessToken, call } = require('./play-api');

const show = (label, r) => {
  console.log(`\n== ${label} (HTTP ${r.status})`);
  console.log(JSON.stringify(r.json, null, 2));
};

async function main() {
  const [, , keyPath, pkg] = process.argv;
  if (!keyPath || !pkg) {
    console.error('Usage: node ci/play-diagnose.js <key.json> <package>');
    process.exit(2);
  }
  const auth = await getAccessToken(keyPath);
  console.log(`Service account: ${auth.email}, package ${pkg}`);
  const edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
  if (!edit.ok) { show('edits.insert FAILED', edit); process.exit(3); }
  const id = edit.json.id;
  const e = `${API}/${pkg}/edits/${id}`;
  try {
    const tracks = await call(auth, 'GET', `${e}/tracks`);
    show('edits.tracks.list', tracks);
    const names = (tracks.json.tracks || []).map((t) => t.track);
    for (const t of new Set(['internal', 'alpha', 'beta', ...names])) {
      if (t === 'production') continue;
      show(`edits.testers.get(${t})`, await call(auth, 'GET', `${e}/testers/${t}`));
      show(`edits.countryavailability.get(${t})`, await call(auth, 'GET', `${e}/countryAvailability/${t}`));
    }
    for (const t of new Set(['internal', ...names])) {
      show(`tracks.releases.list(${t})`, await call(auth, 'GET', `${API}/${pkg}/tracks/${t}/releases`));
    }
    show('edits.details.get', await call(auth, 'GET', `${e}/details`));
    const listings = await call(auth, 'GET', `${e}/listings`);
    const brief = (listings.json.listings || []).map((l) => ({
      language: l.language, title: l.title,
      shortDescription: (l.shortDescription || '').length, fullDescription: (l.fullDescription || '').length,
    }));
    show('edits.listings.list (lengths only)', { status: listings.status, json: brief });
    const bundles = await call(auth, 'GET', `${e}/bundles`);
    show('edits.bundles.list', { status: bundles.status,
      json: (bundles.json.bundles || []).map((b) => ({ versionCode: b.versionCode, sha256: b.sha256 })) });
    show('edits.validate (empty edit)', await call(auth, 'POST', `${e}:validate`));
  } finally {
    await call(auth, 'DELETE', e);
    console.log(`\nEdit ${id} deleted (nothing committed).`);
  }
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
