// Pushes the en-US store listing (store/listing-en-US-{title,short-description,full-description}.txt)
// and the contact details (store/contact.txt) in one edit, commits, then reads them back.
// Usage: node ci/set-play-listing.js <key.json> <package> <store-dir> [--read-only]
'use strict';
const fs = require('fs');
const path = require('path');
const { API, getAccessToken, call, jsonBody } = require('./play-api');

const LIMITS = { title: 30, shortDescription: 80, fullDescription: 4000 };

function readStore(dir) {
  const rd = (f) => fs.readFileSync(path.join(dir, f), 'utf8').replace(/\n+$/, '');
  const listing = {
    language: 'en-US',
    title: rd('listing-en-US-title.txt'),
    shortDescription: rd('listing-en-US-short-description.txt'),
    fullDescription: rd('listing-en-US-full-description.txt'),
  };
  for (const [k, max] of Object.entries(LIMITS)) {
    if (!listing[k] || listing[k].length > max) throw new Error(`${k} is ${listing[k].length} chars (1..${max})`);
  }
  const kv = Object.fromEntries(rd('contact.txt').split('\n').filter((l) => l && !l.startsWith('#'))
    .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
  const details = { contactEmail: kv.contactEmail, contactWebsite: kv.contactWebsite, defaultLanguage: kv.defaultLanguage };
  return { listing, details };
}

async function main() {
  const [, , keyPath, pkg, dir, flag] = process.argv;
  if (!keyPath || !pkg || !dir) {
    console.error('Usage: node ci/set-play-listing.js <key.json> <package> <store-dir> [--read-only]');
    process.exit(2);
  }
  const want = readStore(dir);
  const auth = await getAccessToken(keyPath);
  let edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
  if (!edit.ok) throw new Error(`Could not create edit (HTTP ${edit.status}): ${JSON.stringify(edit.json)}`);
  let id = edit.json.id;
  if (flag !== '--read-only') {
    const d = await call(auth, 'PATCH', `${API}/${pkg}/edits/${id}/details`, ...jsonBody(want.details));
    if (!d.ok) throw new Error(`details update failed: ${JSON.stringify(d.json)}`);
    const l = await call(auth, 'PUT', `${API}/${pkg}/edits/${id}/listings/en-US`, ...jsonBody(want.listing));
    if (!l.ok) throw new Error(`listing update failed: ${JSON.stringify(l.json)}`);
    const c = await call(auth, 'POST', `${API}/${pkg}/edits/${id}:commit`);
    if (!c.ok) throw new Error(`commit failed: ${JSON.stringify(c.json)}`);
    console.log('Listing + contact details committed; reading back...');
    edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
    id = edit.json.id;
  }
  const got = (await call(auth, 'GET', `${API}/${pkg}/edits/${id}/listings/en-US`)).json;
  const det = (await call(auth, 'GET', `${API}/${pkg}/edits/${id}/details`)).json;
  await call(auth, 'DELETE', `${API}/${pkg}/edits/${id}`);
  let diff = 0;
  for (const k of Object.keys(LIMITS)) {
    const same = (got[k] || '') === want.listing[k];
    console.log(`${same ? 'same' : 'DIFF'} ${k}: ${JSON.stringify((got[k] || '').slice(0, 90))}`);
    if (!same) diff += 1;
  }
  for (const k of Object.keys(want.details)) {
    const same = (det[k] || '') === want.details[k];
    console.log(`${same ? 'same' : 'DIFF'} ${k}: ${det[k] || ''}`);
    if (!same) diff += 1;
  }
  if (diff && flag !== '--read-only') process.exit(1);
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
