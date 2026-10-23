// Explicit local acceptance check; never reads legacy .env or prints credentials.
import assert from 'node:assert/strict';
import { readFile, writeFile, stat } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
const repo = new URL('../', import.meta.url);
const local = new URL('.local/', repo);
const stateFile = new URL('admin-smoke.json', local);
const phase = process.argv[2];
assert.ok(['prepare', 'resume'].includes(phase), 'Usage: verify-admin.mjs prepare|resume');
const credentials = JSON.parse(await readFile(new URL('credentials.json', local), 'utf8'));
assert.equal(credentials.environmentId, 'hotel-local-app-v1');
const origin = 'http://127.0.0.1:3000';
let cookies = new Map();
async function request(method, route, status, body, token) {
  const headers = { Accept: 'application/json' };
  if (method === 'POST') headers.Origin = origin;
  if (token) headers['X-CSRF-TOKEN'] = token;
  if (cookies.size) headers.Cookie = [...cookies].map(([k,v]) => `${k}=${v}`).join('; ');
  const multipart = body instanceof FormData;
  if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  const res = await fetch(new URL(route, origin), { method, headers, redirect: 'error',
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  for (const cookie of res.headers.getSetCookie()) {
    const pair = cookie.split(';')[0], at = pair.indexOf('=');
    if (at < 0) continue;
    const name = pair.slice(0,at), value = pair.slice(at+1);
    if (value) cookies.set(name,value); else cookies.delete(name);
  }
  assert.equal(res.status, status, `${method} ${route}: expected ${status}, got ${res.status}`);
  return res.status === 204 ? null : await res.json();
}
const csrf = async () => (await request('GET','/api/v2/auth/csrf',200)).token;
async function login(admin) {
  cookies = new Map();
  const profile = await request('POST','/api/v2/auth/login',200,{
    username: admin ? credentials.adminUsername : credentials.username,
    password: admin ? credentials.adminPassword : credentials.password,
  },await csrf());
  assert.deepEqual(profile.user.roles, [admin ? 'ADMIN' : 'USER']);
}
function hotelDraft(hotel) {
  const { id, ...draft } = hotel;
  return draft;
}
async function publicImage(url) {
  for (const route of [url, `/api/v2${url}`]) {
    const response = await fetch(new URL(route, origin), {redirect:'error',signal:AbortSignal.timeout(20000)});
    assert.equal(response.status,200,route);
    assert.match(response.headers.get('content-type'),/^image\/jpeg/);
    assert.equal(response.headers.get('set-cookie'), null);
    assert.ok((await response.arrayBuffer()).byteLength > 100);
  }
}
if (phase === 'prepare') {
  try { await stat(stateFile); throw new Error('Existing admin check: resume it; no duplicate objects sent'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await request('GET','/api/v2/admin/hotels',401);
  await login(false);
  const beforeBookings = await request('GET','/api/v2/me/bookings',200);
  await request('GET','/api/v2/admin/hotels',403);
  await request('POST','/api/v2/admin/hotels',403,{},await csrf());
  await login(true);
  await request('GET','/api/v2/admin/hotels',200);
  await request('POST','/api/v2/admin/hotels',403,{});
  const token = await csrf();
  const hotel = await request('POST','/api/v2/admin/hotels',201,{
    version:null,slug:`check-${randomUUID()}`,name:`Проверка админки ${Date.now()}`,
    city:'Москва',cityCode:'moscow',timeZone:'Europe/Moscow',address:'Диагностический объект, не для бронирования',
    description:'Вымышленный объект для проверки администрирования. После проверки скрывается.',
    distanceFromCityCenter:1,published:false,photos:[],
  },token);
  // Save identifiers immediately, so a failed check cannot silently create duplicates.
  let state = {createdAt:new Date().toISOString(),hotel,bookingIds:beforeBookings.items.map(b=>b.id)};
  await writeFile(stateFile,JSON.stringify(state,null,2),{flag:'wx',mode:0o600});
  const room = await request('POST',`/api/v2/admin/hotels/${hotel.id}/rooms`,201,{
    version:null,name:'Диагностический номер',number:901,capacity:2,nightlyPrice:'5400.25',
    description:'Проверка точной цены',published:true,photos:[],
  },token);
  const form = new FormData();
  form.append('file',new Blob([await readFile(new URL('frontend/public/photos/room.jpg',repo))],{type:'image/jpeg'}),'room.jpg');
  const media = await request('POST','/api/v2/admin/media',201,form,token);
  await publicImage(media.url);
  const published = await request('POST',`/api/v2/admin/hotels/${hotel.id}`,200,{
    ...hotelDraft(hotel),published:true,photos:[{url:media.url,alt:'Пример интерьера, стоковое фото',credit:'visualsofdana / Unsplash',license:'Unsplash License'}],
  },token);
  await request('GET',`/api/v2/hotels/${hotel.slug}`,200);
  await request('POST',`/api/v2/admin/hotels/${hotel.id}`,409,hotelDraft(hotel),token);
  const {id:roomId,hotelId,...roomBody} = room;
  const updatedRoom = await request('POST',`/api/v2/admin/rooms/${roomId}`,200,{...roomBody,nightlyPrice:'5500.00'},token);
  assert.equal(updatedRoom.nightlyPrice,'5500.00');
  const bad = new FormData();
  bad.append('file',new Blob(['<svg/>'],{type:'image/svg+xml'}),'bad.svg');
  await request('POST','/api/v2/admin/media',400,bad,token);
  const hidden = await request('POST',`/api/v2/admin/hotels/${hotel.id}`,200,{...hotelDraft(published),published:false},token);
  await request('GET',`/api/v2/hotels/${hotel.slug}`,404);
  state = {...state,hotel:hidden,room:updatedRoom,media:media.url,prepared:true};
  await writeFile(stateFile,JSON.stringify(state,null,2),{mode:0o600});
  console.log('PASS: real BFF/session/CSRF, ADMIN/USER access, hotel and room CRUD, photo upload/public delivery, stale version409, publish/hide, SVG rejection. Diagnostic hotel remains hidden.');
} else {
  const state = JSON.parse(await readFile(stateFile,'utf8'));
  assert.equal(state.prepared,true,'Preparation must finish before restart check');
  await login(true);
  const hotel = await request('GET',`/api/v2/admin/hotels/${state.hotel.id}`,200);
  assert.deepEqual(hotel,state.hotel,'Hotel and photo metadata persist unchanged');
  const rooms = await request('GET',`/api/v2/admin/hotels/${hotel.id}/rooms`,200);
  assert.deepEqual(rooms.items.find(r=>r.id===state.room.id),state.room);
  await publicImage(state.media);
  await request('GET',`/api/v2/hotels/${hotel.slug}`,404);
  await login(false);
  const bookings = await request('GET','/api/v2/me/bookings',200);
  for (const id of state.bookingIds) assert.ok(bookings.items.some(b=>b.id===id));
  await writeFile(new URL('../work/admin-protocol-result.json',repo),JSON.stringify({
    completedAt:new Date().toISOString(),hotelId:hotel.id,hidden:true,
    checks:['ADMIN/USER role enforcement','session and CSRF through Next BFF','create hotel/room','exact decimal price update','JPEG upload and anonymous media delivery','stale edit409','publish/hide','SVG rejected','credentials/catalogue/media survive full stop/start','existing USER bookings preserved'],
  },null,2)+'\n');
  console.log('PASS: admin/user credentials, hidden hotel, room price/version, uploaded photo and existing booking history survived full stop/start.');
}
