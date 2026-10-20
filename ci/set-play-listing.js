// Pushes the en-US store listing (store/listing-en-US-{title,short-description,full-description}.txt)
// and the contact details (store/contact.txt) in one edit, commits, then reads them back.
// Graphics from store/graphics/ go in the same edit (each image type is replaced as a whole):
//   icon-512.png -> icon, feature-graphic.png -> featureGraphic, phone/*.png -> phoneScreenshots
//   (file-name order), tablet-7/*.png -> sevenInchScreenshots, tablet-10/*.png -> tenInchScreenshots.
// The read-back compares each image type's sha256 list with the files.
// Usage: node ci/set-play-listing.js <key.json> <package> <store-dir> [--read-only]
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { API, UPLOAD, getAccessToken, call, jsonBody } = require('./play-api');

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

function readGraphics(dir) {
  const g = path.join(dir, 'graphics');
  const pngs = (sub) => {
    const d = path.join(g, sub);
    return fs.existsSync(d) ? fs.readdirSync(d).filter((f) => f.endsWith('.png')).sort().map((f) => path.join(d, f)) : [];
  };
  const one = (f) => (fs.existsSync(path.join(g, f)) ? [path.join(g, f)] : []);
  const types = {
    icon: one('icon-512.png'),
    featureGraphic: one('feature-graphic.png'),
    phoneScreenshots: pngs('phone'),
    sevenInchScreenshots: pngs('tablet-7'),
    tenInchScreenshots: pngs('tablet-10'),
  };
  for (const k of Object.keys(types)) if (!types[k].length) delete types[k];
  return types;
}

const sha256 = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');

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
    for (const [type, files] of Object.entries(readGraphics(dir))) {
      const del = await call(auth, 'DELETE', `${API}/${pkg}/edits/${id}/listings/en-US/${type}`);
      if (!del.ok) throw new Error(`images.deleteall ${type} failed: ${JSON.stringify(del.json)}`);
      for (const f of files) {
        const up = await call(auth, 'POST', `${UPLOAD}/${pkg}/edits/${id}/listings/en-US/${type}?uploadType=media`,
          fs.readFileSync(f), { 'Content-Type': 'image/png' });
        if (!up.ok) throw new Error(`images.upload ${type} ${path.basename(f)} failed: ${JSON.stringify(up.json)}`);
        console.log(`uploaded ${type}: ${path.relative(dir, f)}`);
      }
    }
    const c = await call(auth, 'POST', `${API}/${pkg}/edits/${id}:commit`);
    if (!c.ok) throw new Error(`commit failed: ${JSON.stringify(c.json)}`);
    console.log('Listing + contact details + graphics committed; reading back...');
    edit = await call(auth, 'POST', `${API}/${pkg}/edits`);
    id = edit.json.id;
  }
  const got = (await call(auth, 'GET', `${API}/${pkg}/edits/${id}/listings/en-US`)).json;
  const det = (await call(auth, 'GET', `${API}/${pkg}/edits/${id}/details`)).json;
  const images = {};
  for (const type of ['icon', 'featureGraphic', 'phoneScreenshots', 'sevenInchScreenshots', 'tenInchScreenshots']) {
    images[type] = ((await call(auth, 'GET', `${API}/${pkg}/edits/${id}/listings/en-US/${type}`)).json.images || []);
  }
  await call(auth, 'DELETE', `${API}/${pkg}/edits/${id}`);
  let diff = 0;
  const wantImages = readGraphics(dir);
  for (const [type, imgs] of Object.entries(images)) {
    const want = (wantImages[type] || []).map(sha256);
    const got = imgs.map((i) => i.sha256);
    const same = want.length === 0 ? true : JSON.stringify([...want].sort()) === JSON.stringify([...got].sort());
    console.log(`${same ? 'same' : 'DIFF'} ${type}: ${got.length} on Play${want.length ? `, ${want.length} in store/graphics` : ''}`);
    if (!same) diff += 1;
  }
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
