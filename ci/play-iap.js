// ETPH Pro in-app product (Play "one-time product") from store/iap/<id>.json.
//   node ci/play-iap.js <key.json> <package> store/iap/etph_pro_lifetime.json [--read-only]
// Creates the product if it doesn't exist and keeps its title/description in sync. It NEVER
// activates a purchase option or changes a price: new purchase options start inactive (draft),
// and the owner sets the price and activates it in Play Console (owner decision 2026-10-20:
// price not confirmed yet). Read-only mode only prints what Play has.
'use strict';
const fs = require('fs');
const { getAccessToken, call, jsonBody } = require('./play-api');

const ROOT = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';

function summary(p) {
  const opts = (p.purchaseOptions || []).map((o) => ({
    id: o.purchaseOptionId,
    state: o.state,
    legacyCompatible: o.buyOption && o.buyOption.legacyCompatible,
    prices: (o.regionalPricingAndAvailabilityConfigs || []).length,
    newRegions: o.newRegionsConfig ? o.newRegionsConfig.availability : undefined,
  }));
  return { productId: p.productId, listings: (p.listings || []).map((l) => `${l.languageCode}: ${l.title}`), purchaseOptions: opts };
}

async function main() {
  const [keyPath, pkg, file, flag] = process.argv.slice(2);
  const cfg = JSON.parse(fs.readFileSync(file, 'utf8'));
  const auth = await getAccessToken(keyPath);
  const url = `${ROOT}/${pkg}/oneTimeProducts/${cfg.productId}`;
  const got = await call(auth, 'GET', url);
  if (got.ok) {
    console.log(`Play has ${cfg.productId}: ${JSON.stringify(summary(got.json))}`);
    if (flag === '--read-only') return;
    // Only the listing text: purchase options, prices and states are the owner's in Console.
    const body = { packageName: pkg, productId: cfg.productId, listings: cfg.listings, purchaseOptions: got.json.purchaseOptions };
    const r = await call(auth, 'PATCH', `${ROOT}/${pkg}/onetimeproducts/${cfg.productId}?updateMask=listings&regionsVersion.version=2022/02`, ...jsonBody(body));
    console.log(`listing update: HTTP ${r.status} ${r.ok ? JSON.stringify(summary(r.json)) : JSON.stringify(r.json)}`);
    if (!r.ok) process.exit(1);
    return;
  }
  console.log(`Play has no ${cfg.productId} yet (HTTP ${got.status}: ${JSON.stringify(got.json).slice(0, 300)})`);
  if (flag === '--read-only') return;
  const base = { packageName: pkg, productId: cfg.productId, listings: cfg.listings };
  const buy = { legacyCompatible: true, multiQuantityEnabled: false };
  const price = (units, nanos, cur) => ({ currencyCode: cur, units: String(units), nanos });
  const [u, c] = cfg.proposedPriceUsd.split('.');
  const usd = price(u, Number((c || '0').padEnd(9, '0')), 'USD');
  // Least to most: no price at all first; the proposed USD price only on a draft option, and
  // only if Play insists on one. No attempt activates anything.
  const attempts = [
    ['no purchase option', { ...base, purchaseOptions: [] }],
    ['purchase option without a price', { ...base, purchaseOptions: [{ purchaseOptionId: cfg.purchaseOptionId, buyOption: buy }] }],
    [`draft purchase option, proposed US price ${cfg.proposedPriceUsd} USD, US only`, {
      ...base,
      purchaseOptions: [{
        purchaseOptionId: cfg.purchaseOptionId,
        buyOption: buy,
        regionalPricingAndAvailabilityConfigs: [{ regionCode: 'US', price: usd, availability: 'AVAILABLE' }],
      }],
    }],
  ];
  for (const version of ['2022/02', '2025/03']) {
    for (const [what, body] of attempts) {
      const r = await call(auth, 'PATCH',
        `${ROOT}/${pkg}/onetimeproducts/${cfg.productId}?allowMissing=true&regionsVersion.version=${encodeURIComponent(version)}`,
        ...jsonBody(body));
      console.log(`create (${what}, regions ${version}): HTTP ${r.status} ${r.ok ? '' : JSON.stringify(r.json).slice(0, 600)}`);
      if (r.ok) {
        const back = await call(auth, 'GET', url);
        const s = summary(back.ok ? back.json : r.json);
        console.log(`created: ${JSON.stringify(s)}`);
        const active = s.purchaseOptions.filter((o) => o.state === 'ACTIVE');
        if (active.length) { console.error(`UNEXPECTED: active purchase option(s) ${JSON.stringify(active)}`); process.exit(2); }
        console.log('Nothing is on sale: the owner sets the price and activates the purchase option in Play Console.');
        return;
      }
      if (!/regions?.?version/i.test(JSON.stringify(r.json))) continue;
    }
  }
  process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
