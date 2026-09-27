// Uploads an AAB and rolls it out to a Play Console track.
// Usage: node publish-to-play.js <service-account-key.json> <package-name> <aab-path> <track>
//
// Example (matches this repo's actual applicationId and default build output path):
//   node ci/publish-to-play.js /path/to/play-service-account.json app.ssherpa \
//     android/app/build/outputs/bundle/release/app-release.aab internal
//
// No dependencies beyond Node's built-ins — talks to the Android Publisher API v3 directly:
// mint a JWT from the service account key, exchange it for an OAuth token, create an edit,
// upload the bundle to it, assign it to a track, commit the edit.
const fs = require('fs');
const crypto = require('crypto');

function base64url(input) {
  return Buffer.from(input).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function getAccessToken(key) {
  const now = Math.floor(Date.now() / 1000);
  const claim = {
    iss: key.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${base64url(JSON.stringify(claim))}`;
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(unsigned);
  const signature = signer.sign(key.private_key).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const jwt = `${unsigned}.${signature}`;
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  const data = await res.json();
  if (!data.access_token) throw new Error(`Auth failed: ${JSON.stringify(data)}`);
  return data.access_token;
}

async function main() {
  const [, , keyPath, packageName, aabPath, track] = process.argv;
  if (!keyPath || !packageName || !aabPath || !track) {
    console.error('Usage: node publish-to-play.js <service-account-key.json> <package-name> <aab-path> <track>');
    process.exit(1);
  }

  const key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
  const token = await getAccessToken(key);
  const auth = { Authorization: `Bearer ${token}` };
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}`;
  const uploadBase = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${packageName}`;

  console.log('Creating edit...');
  const editRes = await fetch(`${base}/edits`, { method: 'POST', headers: auth });
  const edit = await editRes.json();
  if (!edit.id) throw new Error(`Could not create edit: ${JSON.stringify(edit)}`);
  console.log(`Edit: ${edit.id}`);

  console.log(`Uploading ${aabPath}...`);
  const aabBuffer = fs.readFileSync(aabPath);
  const uploadRes = await fetch(`${uploadBase}/edits/${edit.id}/bundles?uploadType=media`, {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/octet-stream' },
    body: aabBuffer,
  });
  const uploaded = await uploadRes.json();
  if (!uploaded.versionCode) throw new Error(`Upload failed: ${JSON.stringify(uploaded)}`);
  console.log(`Uploaded as versionCode ${uploaded.versionCode}`);

  console.log(`Assigning to track "${track}"...`);
  const trackRes = await fetch(`${base}/edits/${edit.id}/tracks/${track}`, {
    method: 'PUT',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      track,
      releases: [{ versionCodes: [String(uploaded.versionCode)], status: 'completed' }],
    }),
  });
  const trackData = await trackRes.json();
  if (!trackRes.ok) throw new Error(`Track assignment failed: ${JSON.stringify(trackData)}`);

  console.log('Committing edit...');
  const commitRes = await fetch(`${base}/edits/${edit.id}:commit`, { method: 'POST', headers: auth });
  const commitData = await commitRes.json();
  if (!commitRes.ok) throw new Error(`Commit failed: ${JSON.stringify(commitData)}`);

  console.log(`Published versionCode ${uploaded.versionCode} to track "${track}".`);
}

main().catch((err) => {
  console.error('Error:', err.message);
  process.exit(1);
});
