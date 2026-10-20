// Uploads a signed AAB to a Play Console track.
// Usage: node ci/publish-to-play.js <key.json> <package> <aab> <track> [<release-notes.txt>]
//
// New apps: until the first release has been rolled out in Play Console, Play accepts only DRAFT
// releases ("Only releases with status draft may be created on draft app"). Then this retries
// the whole edit with status draft and says so: the release appears in Play Console under the
// track, ready for "Review release > Start rollout".
// A versionCode Play already has (a re-run, or the hand-uploaded first bundle) is not uploaded
// again, but is still assigned to the track, so the track always ends up carrying it.
'use strict';
const fs = require('fs');
const { API, UPLOAD, getAccessToken, classify, call, jsonBody } = require('./play-api');

async function publish(auth, pkg, aab, track, notes, status) {
  const edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
  if (!edit.ok) {
    const kind = classify(edit.status, edit.json);
    if (kind === 'missing') throw new Error(`Play app ${pkg} not found: create it and upload its FIRST bundle by hand in Play Console (nothing uploaded)`);
    if (kind === 'denied') throw new Error(`${auth.email} has no permission on ${pkg} (Play Console > Users and permissions)`);
    throw new Error(`Could not create edit: ${JSON.stringify(edit.json)}`);
  }
  const id = edit.json.id;
  console.log(`Edit ${id}: uploading ${aab} (${fs.statSync(aab).size} bytes)...`);
  const up = await call(auth, 'POST', `${UPLOAD}/${pkg}/edits/${id}/bundles?uploadType=media`,
    fs.readFileSync(aab), { 'Content-Type': 'application/octet-stream' });
  let vc;
  if (up.json.versionCode) {
    vc = String(up.json.versionCode);
    console.log(`Uploaded as versionCode ${vc}; assigning to "${track}" as ${status}...`);
  } else {
    const m = ((up.json.error && up.json.error.message) || '').match(/Version code (\d+) has already been used/i);
    if (!m) {
      await call(auth, 'DELETE', `${API}/${pkg}/edits/${id}`);
      throw new Error(`Upload failed: HTTP ${up.status} ${JSON.stringify(up.json)}`);
    }
    // The bundle is already in the app's library (an earlier run, or the first bundle uploaded by
    // hand in Play Console), but that doesn't put it on the track: a Console draft can be left
    // without it (run 5046 read "internal: draft []"). Assign it again, in this same edit.
    vc = m[1];
    console.log(`versionCode ${vc} is already in Play; making sure "${track}" carries it (${status})...`);
  }
  const release = { versionCodes: [vc], status };
  if (notes) release.releaseNotes = [{ language: 'en-US', text: notes }];
  const tr = await call(auth, 'PUT', `${API}/${pkg}/edits/${id}/tracks/${track}`, ...jsonBody({ track, releases: [release] }));
  if (!tr.ok) {
    await call(auth, 'DELETE', `${API}/${pkg}/edits/${id}`);
    return { error: tr.json };
  }
  const commit = await call(auth, 'POST', `${API}/${pkg}/edits/${id}:commit`);
  if (!commit.ok) return { error: commit.json };
  console.log(`Published versionCode ${vc} to track "${track}" (${status}).`);
  return 'done';
}

async function main() {
  const [, , keyPath, pkg, aab, track, notesFile] = process.argv;
  if (!keyPath || !pkg || !aab || !track) {
    console.error('Usage: node ci/publish-to-play.js <key.json> <package> <aab> <track> [<release-notes.txt>]');
    process.exit(2);
  }
  if (track === 'production') throw new Error('this pipeline never publishes to production');
  // Play allows 500 characters of release notes per language.
  let notes = notesFile ? fs.readFileSync(notesFile, 'utf8').trim() : '';
  if (notes.length > 500) notes = `${notes.slice(0, 496).replace(/\s+\S*$/, '')} ...`;
  const auth = await getAccessToken(keyPath);
  let r = await publish(auth, pkg, aab, track, notes, 'completed');
  if (r && r.error) {
    const msg = (r.error.error && r.error.error.message) || JSON.stringify(r.error);
    if (!/draft/i.test(msg)) throw new Error(`Play refused the release: ${msg}`);
    console.log(`Play: "${msg}" -> the app has never been rolled out; creating a DRAFT release instead.`);
    r = await publish(auth, pkg, aab, track, notes, 'draft');
    if (r && r.error) throw new Error(`Play refused the draft release: ${JSON.stringify(r.error)}`);
    console.log(`DRAFT on "${track}": roll it out once in Play Console (Test and release > ${track} > Edit release > Review > Start rollout).`);
  }
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
