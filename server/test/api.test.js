import { test } from 'node:test';
import assert from 'node:assert/strict';
import bcrypt from 'bcryptjs';
import { createApp } from '../src/app.js';
import { sha256 } from '../src/util.js';
import { sangreABd, sangreAApp } from '../src/rutas/salud.js';
import { separarNombre } from '../src/rutas/admin.js';

/* BD simulada: cada regla es [regex sobre el SQL, (params) => resultado]. Guarda lo que se ejecutó. */
function fakeDb(reglas) {
  const llamadas = [];
  const run = async (text, params = {}) => {
    llamadas.push({ text, params });
    for (const [re, fn] of reglas) if (re.test(text)) return normalizar(await fn(params, text));
    return normalizar({});
  };
  const normalizar = (r) => ({ recordset: r.recordset ?? r.recordsets?.[0] ?? [], recordsets: r.recordsets ?? [r.recordset ?? []], rowsAffected: r.rowsAffected ?? [0] });
  return { llamadas, query: run, tx: async (_a, fn) => fn(run), ping: async () => true };
}

const config = { maxFailedLogins: 3, lockMinutes: 30, sessionHours: 12, backupDir: null };
const silencio = { info() {}, warn() {}, error() {} };

const PERMISOS = {
  DIRECTOR: ['students.read', 'incidents.create', 'incidents.read_all', 'incidents.update_all', 'reports.read', 'notifications.read'],
  GUARDIAN: ['incidents.create', 'incidents.read_linked', 'incidents.update_own', 'notifications.read'],
  ADMIN: ['students.read', 'incidents.read_all', 'incidents.delete', 'users.manage', 'roles.manage', 'audit.read'],
};
const sesion = (rol) => [/FROM dbo\.UserSessions s\s+JOIN dbo\.Users u/, () => ({
  recordsets: [
    [{ UserSessionId: '1', UserId: 'AAAAAAAA-0000-0000-0000-000000000001', Email: 'x@sira.app', GivenNames: 'X', Surnames: 'Y' }],
    PERMISOS[rol].map((p) => ({ RoleCode: rol, PermissionCode: p })),
  ],
})];

async function llamar(app, metodo, ruta, { token, body } = {}) {
  const server = app.listen(0);
  try {
    const { port } = server.address();
    const r = await fetch(`http://127.0.0.1:${port}${ruta}`, {
      method: metodo,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const texto = await r.text();
    return { status: r.status, body: texto ? JSON.parse(texto) : null };
  } finally {
    server.close();
  }
}

test('salud responde y no exige sesión', async () => {
  const app = createApp({ db: fakeDb([]), config, log: silencio });
  const r = await llamar(app, 'GET', '/api/salud');
  assert.equal(r.status, 200);
  assert.equal(r.body.baseDeDatos, true);
});

test('sin token → 401', async () => {
  const app = createApp({ db: fakeDb([]), config, log: silencio });
  assert.equal((await llamar(app, 'GET', '/api/incidencias')).status, 401);
});

test('login correcto crea sesión con el hash del token y devuelve el rol', async () => {
  const hash = await bcrypt.hash('SiraDemo2026!', 4);
  const db = fakeDb([
    [/FROM dbo\.Users WHERE EmailNormalized/, () => ({ recordset: [{
      UserId: 'U1', Email: 'demo@sira.app', GivenNames: 'Dirección', Surnames: 'Demo', PasswordHash: hash,
      PasswordHashAlgorithm: 'BCRYPT', AccountStatus: 'ACTIVE', FailedLoginCount: 0, LockedUntilUtc: null }] })],
    [/INSERT dbo\.UserSessions/, () => ({ recordset: [{ RoleCode: 'DIRECTOR' }] })],
  ]);
  const app = createApp({ db, config, log: silencio });
  const r = await llamar(app, 'POST', '/api/auth/login', { body: { correo: ' Demo@Sira.app ', clave: 'SiraDemo2026!' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.rol, 'DIRECTOR');
  const ins = db.llamadas.find((c) => /INSERT dbo\.UserSessions/.test(c.text));
  assert.deepEqual(ins.params.h, sha256(r.body.token), 'en la BD solo se guarda el SHA-256');
  assert.equal(db.llamadas[0].params.email, 'demo@sira.app');
});

test('tercer intento fallido bloquea la cuenta (423)', async () => {
  const hash = await bcrypt.hash('otra', 4);
  const db = fakeDb([[/FROM dbo\.Users WHERE EmailNormalized/, () => ({ recordset: [{
    UserId: 'U1', Email: 'demo@sira.app', PasswordHash: hash, PasswordHashAlgorithm: 'BCRYPT',
    AccountStatus: 'ACTIVE', FailedLoginCount: 2, LockedUntilUtc: null }] })]]);
  const app = createApp({ db, config, log: silencio });
  const r = await llamar(app, 'POST', '/api/auth/login', { body: { correo: 'demo@sira.app', clave: 'mal' } });
  assert.equal(r.status, 423);
  assert.equal(db.llamadas[1].params.bloquear, true);
});

test('correo inexistente → 401 sin revelar nada', async () => {
  const app = createApp({ db: fakeDb([]), config, log: silencio });
  const r = await llamar(app, 'POST', '/api/auth/login', { body: { correo: 'nadie@sira.app', clave: 'x' } });
  assert.equal(r.status, 401);
  assert.equal(r.body.error, 'CREDENCIALES_INVALIDAS');
});

test('recuperar siempre responde 202', async () => {
  const app = createApp({ db: fakeDb([]), config, log: silencio });
  assert.equal((await llamar(app, 'POST', '/api/auth/recuperar', { body: { correo: 'nadie@sira.app' } })).status, 202);
});

test('un tutor no puede ver usuarios (403)', async () => {
  const app = createApp({ db: fakeDb([sesion('GUARDIAN')]), config, log: silencio });
  assert.equal((await llamar(app, 'GET', '/api/usuarios', { token: 't' })).status, 403);
});

test('el tutor solo consulta incidencias de sus hijos (todos = 0)', async () => {
  const db = fakeDb([sesion('GUARDIAN')]);
  const app = createApp({ db, config, log: silencio });
  assert.equal((await llamar(app, 'GET', '/api/incidencias', { token: 't' })).status, 200);
  const q = db.llamadas.find((c) => /FROM dbo\.Incidents i/.test(c.text));
  assert.equal(q.params.todos, 0);
});

test('dirección consulta todas e incluye folio y fecha en ms', async () => {
  const fecha = new Date('2026-11-10T15:00:00Z');
  const db = fakeDb([sesion('DIRECTOR'), [/FROM dbo\.Incidents i/, () => ({ recordset: [{
    IncidentId: 'I1', Folio: 'INC-2026-000001', OccurredAt: fecha, Description: 'x', StatusCode: 'PENDING',
    EnrollmentCode: '19071726', GivenNames: 'Jesus Rogelio', FirstSurname: 'Bañuelos', SecondSurname: 'Martinez',
    Tipo: 'Caída', Gravedad: 'Media' }] })]]);
  const r = await llamar(createApp({ db, config, log: silencio }), 'GET', '/api/incidencias', { token: 't' });
  assert.equal(r.body[0].alumno.nombre, 'Bañuelos Martinez Jesus Rogelio');
  assert.equal(r.body[0].fecha, fecha.getTime());
  assert.equal(db.llamadas.find((c) => /FROM dbo\.Incidents i/.test(c.text)).params.todos, 1);
});

const catalogo = [/FROM dbo\.IncidentTypes WHERE IsActive/, () => ({ recordsets: [
  [{ codigo: 'CAIDA', nombre: 'Caída' }], [{ codigo: 'MEDIA', nombre: 'Media' }]] })];

test('registrar incidencia con tipo desconocido → 400', async () => {
  const app = createApp({ db: fakeDb([sesion('DIRECTOR'), catalogo]), config, log: silencio });
  const r = await llamar(app, 'POST', '/api/incidencias', { token: 't',
    body: { matricula: '19071726', tipo: 'Inventado', gravedad: 'Media', fecha: Date.now(), descripcion: '' } });
  assert.equal(r.status, 400);
  assert.equal(r.body.error, 'TIPO_INVALIDO');
});

test('registrar incidencia traduce nombres a códigos y devuelve folio', async () => {
  const db = fakeDb([sesion('DIRECTOR'), catalogo,
    [/SELECT s\.StudentId FROM dbo\.Students s/, () => ({ recordset: [{ StudentId: 'S1' }] })],
    [/NEXT VALUE FOR/, () => ({ recordset: [{ Folio: 'INC-2026-000007' }] })]]);
  const r = await llamar(createApp({ db, config, log: silencio }), 'POST', '/api/incidencias', { token: 't',
    body: { matricula: '19071726', tipo: 'Caída', gravedad: 'Media', fecha: Date.now() - 1000, descripcion: ' golpe ' } });
  assert.equal(r.status, 201);
  assert.equal(r.body.folio, 'INC-2026-000007');
  const ins = db.llamadas.find((c) => /NEXT VALUE FOR/.test(c.text)).params;
  assert.equal(ins.tipo, 'CAIDA');
  assert.equal(ins.grav, 'MEDIA');
  assert.equal(ins.desc, 'golpe');
});

test('fecha futura → 400', async () => {
  const app = createApp({ db: fakeDb([sesion('DIRECTOR'), catalogo]), config, log: silencio });
  const r = await llamar(app, 'POST', '/api/incidencias', { token: 't',
    body: { matricula: '1', tipo: 'Caída', gravedad: 'Media', fecha: Date.now() + 86_400_000 } });
  assert.equal(r.body.error, 'FECHA_INVALIDA');
});

test('eliminar exige motivo y traduce los THROW del procedimiento', async () => {
  const err = Object.assign(new Error('no'), { number: 51013 });
  const db = fakeDb([sesion('ADMIN'), [/usp_DeleteIncident/, () => { throw err; }]]);
  const app = createApp({ db, config, log: silencio });
  const id = '6f1c2d3e-0000-4000-8000-000000000001';
  assert.equal((await llamar(app, 'POST', `/api/incidencias/${id}/eliminar`, { token: 't', body: {} })).status, 400);
  assert.equal((await llamar(app, 'POST', `/api/incidencias/${id}/eliminar`, { token: 't', body: { motivo: 'duplicada' } })).status, 404);
});

const alumnosBd = [
  { StudentId: 'S1', EnrollmentCode: '19071726', GivenNames: 'Jesus Rogelio', FirstSurname: 'Bañuelos', SecondSurname: 'Martinez' },
  { StudentId: 'S2', EnrollmentCode: '19071801', GivenNames: 'Ana Sofia', FirstSurname: 'Ramirez', SecondSurname: 'Lopez' },
  { StudentId: 'S3', EnrollmentCode: '19071999', GivenNames: 'Ana Lucia', FirstSurname: 'Ramirez', SecondSurname: 'Diaz' },
];
const buscar = async (q) => {
  const db = fakeDb([sesion('DIRECTOR'), [/SELECT TOP \(50\)/, () => ({ recordset: alumnosBd })]]);
  return llamar(createApp({ db, config, log: silencio }), 'GET', `/api/alumnos/buscar?q=${encodeURIComponent(q)}`, { token: 't' });
};

test('buscar alumno: matrícula, nombre sin acentos, y nunca adivina entre varios', async () => {
  assert.equal((await buscar('19071726')).body.nombre, 'Bañuelos Martinez Jesus Rogelio');
  assert.equal((await buscar('banuelos JESUS')).body.matricula, '19071726');
  assert.equal((await buscar('19071726 — Bañuelos Martinez Jesus Rogelio')).body.matricula, '19071726');
  assert.equal((await buscar('Ramirez Ana')).status, 404, 'dos alumnos coinciden');
  assert.equal((await buscar('Ramirez')).status, 404, 'una sola palabra no basta');
  assert.equal((await buscar('19071726 Ramirez')).status, 404, 'matrícula y nombre de alumnos distintos');
});

test('tipo de sangre: la app usa "−" y "No identificado"', () => {
  assert.equal(sangreABd('AB−'), 'AB-');
  assert.equal(sangreABd('No identificado'), 'UNKNOWN');
  assert.equal(sangreAApp('O-'), 'O−');
  assert.equal(sangreAApp('UNKNOWN'), 'No identificado');
  assert.throws(() => sangreABd('Z+'));
});

test('separar nombre completo en nombres y apellidos', () => {
  assert.deepEqual(separarNombre('Ana Sofía Ramírez López'), { nombres: 'Ana Sofía', apellidos: 'Ramírez López' });
  assert.deepEqual(separarNombre('Luis Pérez'), { nombres: 'Luis', apellidos: 'Pérez' });
  assert.deepEqual(separarNombre('Cher'), { nombres: 'Cher', apellidos: '' });
});
