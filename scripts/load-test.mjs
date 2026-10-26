// Scale-out check against a running deployment (docker compose --profile full, or Azure).
//
//   node scripts/load-test.mjs [baseUrl] [--reads 400] [--concurrency 50]
//
// 1. Fires availability reads in parallel and shows which API instance answered each one
//    (the X-Served-By response header), so you can see the load spread across replicas.
// 2. Books ONE slot from many vehicles at the same instant. However many instances race,
//    PostgreSQL's exclusion constraints let exactly `capacity` bookings through and every
//    other request gets 409: no double booking, with no in-memory lock shared between instances.
// 3. Cancels the test bookings again so the demo data stays clean.
//
// Needs Node.js 22+ (built-in fetch). Uses only the public API, like the web app does.

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? Number(args[i + 1]) : fallback;
};
const base = (args.find((a) => /^https?:\/\//.test(a)) ?? 'http://localhost:8080').replace(/\/$/, '');
const READS = flag('reads', 400);
const CONCURRENCY = flag('concurrency', 50);

async function call(method, path, { token, body, headers = {} } = {}) {
  const started = performance.now();
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return {
    status: res.status,
    json,
    servedBy: res.headers.get('x-served-by') ?? '(no header)',
    traceId: res.headers.get('x-trace-id'),
    ms: performance.now() - started,
  };
}

async function must(method, path, opts) {
  const r = await call(method, path, opts);
  if (r.status >= 300) throw new Error(`${method} ${path} → ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}

const items = (x) => (Array.isArray(x) ? x : (x?.items ?? x?.data ?? []));

/** Runs `tasks` with at most `limit` in flight. */
async function pool(tasks, limit) {
  const results = new Array(tasks.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, tasks.length) }, async () => {
      while (next < tasks.length) {
        const i = next++;
        results[i] = await tasks[i]();
      }
    }),
  );
  return results;
}

function summary(title, results, wallMs) {
  const ms = results.map((r) => r.ms).sort((a, b) => a - b);
  const pct = (p) => ms[Math.min(ms.length - 1, Math.floor((p / 100) * ms.length))].toFixed(0);
  const count = (key) =>
    Object.entries(results.reduce((acc, r) => ({ ...acc, [r[key]]: (acc[r[key]] ?? 0) + 1 }), {}))
      .sort()
      .map(([k, n]) => `${k}: ${n}`)
      .join(', ');
  console.log(`\n${title}`);
  console.log(`  ${results.length} requests in ${wallMs.toFixed(0)} ms (${((results.length / wallMs) * 1000).toFixed(0)} req/s)`);
  console.log(`  latency p50 ${pct(50)} ms · p95 ${pct(95)} ms · p99 ${pct(99)} ms`);
  console.log(`  status   ${count('status')}`);
  console.log(`  served by ${count('servedBy')}`);
}

async function main() {
  console.log(`Target ${base}`);
  const health = await call('GET', '/health');
  console.log(`Health ${health.status} ${JSON.stringify(health.json)} (served by ${health.servedBy})`);

  // A fresh customer for each run, so runs never collide with each other or the demo data.
  const stamp = Date.now().toString(36);
  const email = `load-${stamp}@demo.vn`;
  const password = 'LoadTest2026';
  await must('POST', '/api/auth/register', { body: { email, password, fullName: 'Load Test' } });
  const { token } = await must('POST', '/api/auth/login', { body: { email, password } });

  const dealership = items(await must('GET', '/api/dealerships')).find((d) => d.code === 'HCM-D1');
  const pkg = items(await must('GET', '/api/service-packages?vehicleType=MOTORBIKE')).find((p) => p.code === 'MB-ESSENTIAL');
  if (!dealership || !pkg) throw new Error('Demo data not found (HCM-D1 / MB-ESSENTIAL). Is SEED_DEMO_DATA on?');

  // The emptiest slot in the next two weeks: that is where the race happens.
  const dates = Array.from({ length: 14 }, (_, i) => new Date(Date.now() + (i + 1) * 86_400_000).toISOString().slice(0, 10));
  const availabilityPath = (date) => `/api/availability?dealershipId=${dealership.id}&packageId=${pkg.id}&date=${date}`;
  let target;
  for (const date of dates) {
    const slots = (await must('GET', availabilityPath(date), { token })).slots ?? [];
    // `capacity` = how many more jobs of this kind fit at that start time.
    const best = slots.filter((s) => s.available).sort((a, b) => b.capacity - a.capacity)[0];
    if (best && (!target || best.capacity > target.capacity)) target = best;
  }
  if (!target) throw new Error('No free slot in the next 14 days');

  // ---- 1. parallel reads ----------------------------------------------------------------------
  let t0 = performance.now();
  const reads = await pool(
    Array.from({ length: READS }, (_, i) => () => call('GET', availabilityPath(dates[i % dates.length]), { token })),
    CONCURRENCY,
  );
  summary(`1) ${READS} availability reads, ${CONCURRENCY} at a time`, reads, performance.now() - t0);

  // ---- 2. same-slot booking race --------------------------------------------------------------
  const contenders = Math.min(40, target.capacity * 3 + 6);
  const vehicles = await pool(
    Array.from({ length: contenders }, (_, i) => () =>
      must('POST', '/api/me/vehicles', {
        token,
        body: { vehicleType: 'MOTORBIKE', make: 'Honda', model: 'Wave Alpha', plate: `59-LT ${stamp.slice(-3)}.${String(i).padStart(2, '0')}` },
      }),
    ),
    10,
  );
  t0 = performance.now();
  const bookings = await Promise.all(
    vehicles.map((v) =>
      call('POST', '/api/appointments', {
        token,
        body: { dealershipId: dealership.id, vehicleId: v.id, packageId: pkg.id, start: target.start },
      }),
    ),
  );
  summary(
    `2) ${contenders} vehicles book ${target.start} at the same instant (capacity ${target.capacity})`,
    bookings,
    performance.now() - t0,
  );
  const won = bookings.filter((b) => b.status === 201);
  const ok = won.length === target.capacity && bookings.every((b) => b.status === 201 || b.status === 409);
  console.log(
    ok
      ? `  ✔ exactly ${won.length} bookings succeeded for ${target.capacity} free bay+technician pairs; the rest got 409`
      : `  ✘ expected ${target.capacity} successes, got ${won.length}`,
  );

  // ---- 3. clean up ----------------------------------------------------------------------------
  await pool(
    won.map((b) => () => call('POST', `/api/appointments/${b.json.id}/cancel`, { token, body: { reason: 'load test clean-up' } })),
    10,
  );
  console.log(`\nCancelled the ${won.length} test bookings. Test customer: ${email}`);
  if (!ok) process.exitCode = 1;
}

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
