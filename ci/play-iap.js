// ETPH Pro in-app product (Play "one-time product", monetization.onetimeproducts) from store/iap/<id>.json.
//   node ci/play-iap.js <key.json> <package> store/iap/pro.json [--read-only]
// Creates or updates the product: listing text, one buy purchase option (legacyCompatible, so
// Play Billing's queryProductDetails/launchBillingFlow see it as a plain in-app product) priced at
// priceUsd with every other region's price converted by Play (pricing:convertRegionPrices, the
// same conversion as Console's "Set price"), then activates the purchase option when
// "activate" is true. Reads the result back and fails unless it matches. Read-only mode only
// prints what Play has.
'use strict';
const fs = require('fs');
const { getAccessToken, call, jsonBody } = require('./play-api');

const ROOT = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';

function money(m) {
  if (!m) return '?';
  return `${m.units || 0}.${String(Math.round((m.nanos || 0) / 1e7)).padStart(2, '0')} ${m.currencyCode}`;
}

function summary(p) {
  const opts = (p.purchaseOptions || []).map((o) => {
    const cfgs = o.regionalPricingAndAvailabilityConfigs || [];
    const us = cfgs.find((c) => c.regionCode === 'US');
    return {
      id: o.purchaseOptionId,
      state: o.state,
      legacyCompatible: o.buyOption && o.buyOption.legacyCompatible,
      regions: cfgs.length,
      US: us ? money(us.price) : undefined,
      newRegions: o.newRegionsConfig ? `${o.newRegionsConfig.availability} ${money(o.newRegionsConfig.usdPrice)}` : undefined,
    };
  });
  return { productId: p.productId, listings: (p.listings || []).map((l) => `${l.languageCode}: ${l.title}`), purchaseOptions: opts };
}

function usd(str) {
  const [u, c] = str.split('.');
  return { currencyCode: 'USD', units: String(Number(u)), nanos: Number((c || '0').padEnd(9, '0')) };
}

async function main() {
  const [keyPath, pkg, file, flag] = process.argv.slice(2);
  const cfg = JSON.parse(fs.readFileSync(file, 'utf8'));
  const auth = await getAccessToken(keyPath);
  const url = `${ROOT}/${pkg}/oneTimeProducts/${cfg.productId}`;
  const got = await call(auth, 'GET', url);
  console.log(got.ok
    ? `Play has ${cfg.productId}: ${JSON.stringify(summary(got.json))}`
    : `Play has no ${cfg.productId} yet (HTTP ${got.status}: ${JSON.stringify(got.json).slice(0, 300)})`);
  if (flag === '--read-only') return;

  // 1. Play's own conversion of the USD price to every region it sells in.
  const price = usd(cfg.priceUsd);
  const conv = await call(auth, 'POST', `${ROOT}/${pkg}/pricing:convertRegionPrices`, ...jsonBody({ price }));
  if (!conv.ok) { console.error(`convertRegionPrices: HTTP ${conv.status} ${JSON.stringify(conv.json)}`); process.exit(1); }
  const version = (conv.json.regionVersion && conv.json.regionVersion.version) || '2022/02';
  const regional = Object.values(conv.json.convertedRegionPrices || {})
    .map((r) => ({ regionCode: r.regionCode, price: r.price, availability: 'AVAILABLE' }))
    .sort((a, b) => a.regionCode.localeCompare(b.regionCode));
  const us = regional.find((r) => r.regionCode === 'US');
  if (!us) { console.error('convertRegionPrices returned no US price'); process.exit(1); }
  us.price = price; // exactly the owner's price, whatever the conversion rounds to
  const other = conv.json.convertedOtherRegionsPrice || {};
  console.log(`converted ${cfg.priceUsd} USD to ${regional.length} regions (regions version ${version}); e.g. ` +
    ['US', 'GB', 'DE', 'JP', 'IN', 'BR'].map((c) => { const r = regional.find((x) => x.regionCode === c); return r ? `${c} ${money(r.price)}` : null; }).filter(Boolean).join(', '));

  // 2. Create or update the product with that purchase option.
  const existing = got.ok ? (got.json.purchaseOptions || []).find((o) => o.purchaseOptionId === cfg.purchaseOptionId) : null;
  const option = {
    purchaseOptionId: cfg.purchaseOptionId,
    buyOption: { legacyCompatible: true, multiQuantityEnabled: false },
    regionalPricingAndAvailabilityConfigs: regional,
  };
  if (other.usdPrice && other.eurPrice) {
    option.newRegionsConfig = { usdPrice: other.usdPrice, eurPrice: other.eurPrice, availability: 'AVAILABLE' };
  }
  const others = got.ok ? (got.json.purchaseOptions || []).filter((o) => o.purchaseOptionId !== cfg.purchaseOptionId) : [];
  const body = { packageName: pkg, productId: cfg.productId, listings: cfg.listings, purchaseOptions: [...others, option] };
  const q = `allowMissing=true&updateMask=listings,purchaseOptions&regionsVersion.version=${encodeURIComponent(version)}`;
  let r = await call(auth, 'PATCH', `${ROOT}/${pkg}/onetimeproducts/${cfg.productId}?${q}`, ...jsonBody(body));
  if (!r.ok) {
    // Play names the region it refuses (not billable there / price out of range): drop it and retry.
    for (let i = 0; i < 20 && !r.ok; i++) {
      const msg = JSON.stringify(r.json);
      const m = msg.match(/region(?:Code)?[^A-Z]{0,20}\b([A-Z]{2})\b/);
      if (!m || !option.regionalPricingAndAvailabilityConfigs.some((c) => c.regionCode === m[1])) break;
      console.log(`Play refused region ${m[1]} (${msg.slice(0, 300)}); leaving it out`);
      option.regionalPricingAndAvailabilityConfigs = option.regionalPricingAndAvailabilityConfigs.filter((c) => c.regionCode !== m[1]);
      r = await call(auth, 'PATCH', `${ROOT}/${pkg}/onetimeproducts/${cfg.productId}?${q}`, ...jsonBody(body));
    }
  }
  console.log(`${got.ok ? 'update' : 'create'}: HTTP ${r.status} ${r.ok ? JSON.stringify(summary(r.json)) : JSON.stringify(r.json).slice(0, 1500)}`);
  if (!r.ok) process.exit(1);

  // 3. Put it on sale.
  if (cfg.activate) {
    const now = await call(auth, 'GET', url);
    const st = ((now.json.purchaseOptions || []).find((o) => o.purchaseOptionId === cfg.purchaseOptionId) || {}).state;
    if (st !== 'ACTIVE') {
      const a = await call(auth, 'POST', `${ROOT}/${pkg}/oneTimeProducts/${cfg.productId}/purchaseOptions:batchUpdateStates`,
        ...jsonBody({ requests: [{ activatePurchaseOptionRequest: { packageName: pkg, productId: cfg.productId, purchaseOptionId: cfg.purchaseOptionId } }] }));
      console.log(`activate ${cfg.purchaseOptionId} (was ${st}): HTTP ${a.status} ${a.ok ? '' : JSON.stringify(a.json).slice(0, 800)}`);
      if (!a.ok) process.exit(1);
    } else {
      console.log(`${cfg.purchaseOptionId} already ACTIVE`);
    }
  }

  // 4. Read back.
  const back = await call(auth, 'GET', url);
  if (!back.ok) { console.error(`read back: HTTP ${back.status}`); process.exit(1); }
  const s = summary(back.json);
  console.log(`Play now has: ${JSON.stringify(s)}`);
  const o = s.purchaseOptions.find((x) => x.id === cfg.purchaseOptionId);
  const want = money(price);
  if (!o || o.US !== want || (cfg.activate && o.state !== 'ACTIVE')) {
    console.error(`MISMATCH: want ${cfg.purchaseOptionId} at ${want}${cfg.activate ? ', ACTIVE' : ''}`);
    process.exit(1);
  }
  console.log(`OK: ${cfg.productId}/${cfg.purchaseOptionId} ${o.state} at ${want} in ${o.regions} regions`);
}

main().catch((e) => { console.error(e); process.exit(1); });
