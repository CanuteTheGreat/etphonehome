// Uploads a signed AAB (or APK) to Play's Internal App Sharing and prints the install link.
// Works for a Draft app and needs no review: anyone with the link can install, if Play Console >
// Setup > Internal app sharing allows it ("Anyone you shared the link with can download").
// Play re-signs internal-app-sharing builds with its own sharing key, so the installed app can't
// be updated in place from a store or sideloaded release build (uninstall first).
// Usage: node ci/internal-app-sharing.js <key.json> <package> <file.aab|file.apk> [<url-out-file>]
'use strict';
const fs = require('fs');
const { UPLOAD, getAccessToken, call } = require('./play-api');

async function main() {
  const [, , keyPath, pkg, file, outFile] = process.argv;
  if (!keyPath || !pkg || !file) {
    console.error('Usage: node ci/internal-app-sharing.js <key.json> <package> <file.aab|file.apk> [<url-out-file>]');
    process.exit(2);
  }
  const kind = file.endsWith('.apk') ? 'apk' : 'bundle';
  const auth = await getAccessToken(keyPath);
  console.log(`Uploading ${file} (${fs.statSync(file).size} bytes) to Internal App Sharing as ${kind}...`);
  const r = await call(auth, 'POST',
    `${UPLOAD}/internalappsharing/${pkg}/artifacts/${kind}?uploadType=media`,
    fs.readFileSync(file), { 'Content-Type': 'application/octet-stream' });
  if (!r.ok || !r.json.downloadUrl) {
    const msg = (r.json.error && r.json.error.message) || JSON.stringify(r.json);
    console.error(`Internal App Sharing refused the upload: HTTP ${r.status} ${msg}`);
    if (r.status === 403 || /permission|not enabled|uploader/i.test(msg)) {
      console.error(`Play Console > Setup > Internal app sharing: add ${auth.email} as an uploader and choose`);
      console.error('"Anyone you shared the link with can download".');
    }
    process.exit(1);
  }
  console.log(`INTERNAL_APP_SHARING_URL=${r.json.downloadUrl}`);
  console.log(`sha256 ${r.json.sha256}, sharing-key cert ${r.json.certificateFingerprint}`);
  if (outFile) fs.writeFileSync(outFile, `${r.json.downloadUrl}\n`);
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
