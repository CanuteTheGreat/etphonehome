// Android Publisher API v3 helpers shared by the ci/*play*.js scripts. Node built-ins only:
// mint a JWT from the service-account key, exchange it for an OAuth token, call the API.
// The key file is written by the workflow from the PLAY_SERVICE_ACCOUNT_JSON secret (shredded after).
'use strict';
const fs = require('fs');
const crypto = require('crypto');

const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';
const UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications';

function base64url(input) {
  return Buffer.from(input).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function getAccessToken(keyPath) {
  const key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
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
  const signature = base64url(signer.sign(key.private_key));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${unsigned}.${signature}`,
  });
  const data = await res.json();
  if (!data.access_token) throw new Error(`Auth failed: ${JSON.stringify(data)}`);
  return { token: data.access_token, email: key.client_email };
}

// Play answers 404 "Package not found" both for an app that doesn't exist yet and (for some
// endpoints) one the service account can't see; 403 "The caller does not have permission"
// for an app it can see but has no rights on.
function classify(status, body) {
  const msg = (body && body.error && body.error.message) || '';
  if (status === 404 || /Package not found/i.test(msg)) return 'missing';
  if (status === 403 || status === 401) return 'denied';
  return 'error';
}

async function call(auth, method, url, body, headers = {}) {
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${auth.token}`, ...headers },
    body,
  });
  const text = await res.text();
  let json = {};
  try { json = text ? JSON.parse(text) : {}; } catch (e) { json = { raw: text }; }
  return { status: res.status, ok: res.ok, json };
}

const jsonBody = (o) => [JSON.stringify(o), { 'Content-Type': 'application/json' }];

module.exports = { API, UPLOAD, getAccessToken, classify, call, jsonBody };
