// Explicitly invoked, additive import into the dedicated local environment only.
// Does not reset seed data, use legacy .env, retry mutations or change existing hotels.
import assert from "node:assert/strict";
import { readFile, writeFile, chmod } from "node:fs/promises";
const repo = new URL("../", import.meta.url);
const local = new URL(".local/", repo);
const dataset = JSON.parse(await readFile(new URL("docs/catalogue/hotels.json", repo), "utf8"));
assert.equal(dataset.datasetId, "real-hotels-demo-v1");
assert.equal(dataset.hotels.length, 10);
assert.equal(new Set(dataset.hotels.map(h => h.slug)).size, 10);
const credentials = JSON.parse(await readFile(new URL("credentials.json", local), "utf8"));
assert.equal(credentials.environmentId, "hotel-local-app-v1");
assert.equal(credentials.adminUsername, "local-admin");
const origin = "http://127.0.0.1:3000";
const journalFile = new URL("hotel-catalogue-import.json", local);
let journal;
try { journal = JSON.parse(await readFile(journalFile, "utf8")); }
catch (error) { if (error.code !== "ENOENT") throw error; journal = { datasetId: dataset.datasetId, hotels: {} }; }
assert.equal(journal.datasetId, dataset.datasetId);
const save = async () => {
  await writeFile(journalFile, JSON.stringify(journal, null, 2) + "\n", { mode: 0o600 });
  await chmod(journalFile, 0o600);
};
const cookies = new Map();
async function request(method, route, expected, body, token) {
  const headers = { Accept: "application/json" };
  if (cookies.size) headers.Cookie = [...cookies].map(([k,v]) => `${k}=${v}`).join("; ");
  if (method === "POST") headers.Origin = origin;
  if (token) headers["X-CSRF-TOKEN"] = token;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(new URL(route, origin), { method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "error", signal: AbortSignal.timeout(30000) });
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(";")[0], at = pair.indexOf("=");
    if (at < 0) continue;
    const name = pair.slice(0, at), value = pair.slice(at + 1);
    if (value) cookies.set(name, value); else cookies.delete(name);
  }
  assert.equal(response.status, expected, `${method} ${route}: HTTP ${response.status}; stopped without retry. Inspect admin before resuming.`);
  return response.json();
}
const csrf = () => request("GET", "/api/v2/auth/csrf", 200);
async function post(route, status, body) {
  return request("POST", route, status, body, (await csrf()).token);
}
const profile = await post("/api/v2/auth/login", 200, { username: credentials.adminUsername, password: credentials.adminPassword });
assert.ok(profile.user.roles.includes("ADMIN"));
async function collection(route) {
  const result = [];
  for (let page = 0; ; page++) {
    const batch = await request("GET", `${route}?page=${page}&size=100`, 200);
    result.push(...batch.items);
    if (page + 1 >= batch.totalPages) return result;
    assert.ok(page < 1000, "Unexpected collection size");
  }
}
const before = await collection("/api/v2/admin/hotels");
let created = 0, createdRooms = 0, skipped = 0;
for (const input of dataset.hotels) {
  const { sourceUrl, rooms, ...fields } = input;
  assert.match(sourceUrl, /^https:\/\//);
  const existing = before.find(h => h.slug === fields.slug);
  let hotel = existing;
  if (hotel) {
    // Never overwrite previously published objects, including later user edits.
    if (hotel.published || journal.hotels[fields.slug]?.id !== hotel.id) { skipped++; continue; }
    const {id, version, published, ...existingFields} = hotel;
    assert.deepEqual(existingFields, fields, "Existing draft changed: finish it manually in admin");
  } else {
    hotel = await post("/api/v2/admin/hotels", 201, { ...fields, version: null, published: false });
    journal.hotels[fields.slug] = { id: hotel.id, complete: false };
    await save();
    created++;
  }
  const present = await collection(`/api/v2/admin/hotels/${hotel.id}/rooms`);
  for (const room of rooms) {
    const found = present.find(r => r.number === room.number);
    if (found) {
      const {id, hotelId, version, ...values} = found;
      assert.deepEqual(values, room, "Existing room changed: finish the draft manually in admin");
    } else {
      await post(`/api/v2/admin/hotels/${hotel.id}/rooms`, 201, { ...room, version: null });
      createdRooms++;
    }
  }
  const current = await request("GET", `/api/v2/admin/hotels/${hotel.id}`, 200);
  const {id, ...draft} = current;
  hotel = await post(`/api/v2/admin/hotels/${hotel.id}`, 200, { ...draft, published: true });
  journal.hotels[fields.slug] = { id: hotel.id, complete: true };
  await save();
}
const after = await collection("/api/v2/admin/hotels");
for (const original of before) {
  if (journal.hotels[original.slug]?.id === original.id && !original.published) continue;
  assert.deepEqual(after.find(h => h.id === original.id), original, "Existing hotel must remain unchanged");
}
for (const input of dataset.hotels) {
  const hotel = after.find(h => h.slug === input.slug);
  assert.ok(hotel?.published, `Not published: ${input.slug}`);
  const detail = await request("GET", `/api/v2/hotels/${input.slug}`, 200);
  assert.equal(detail.name, input.name);
  assert.equal(detail.isDemo, true);
  const rooms = await request("GET", `/api/v2/hotels/${input.slug}/rooms?guests=2`, 200);
  assert.ok(rooms.totalElements >= 3, `Room variants missing: ${input.slug}`);
}
console.log(JSON.stringify({ datasetId: dataset.datasetId, created, createdRooms, skipped, verifiedPublished: 10, existingHotelsPreserved: before.length, totalHotels: after.length }));
