import crypto from 'node:crypto';

export class HttpError extends Error {
  constructor(status, code, message = code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const badRequest = (code = 'DATOS_INVALIDOS', msg) => new HttpError(400, code, msg);
export const notFound = (code = 'NO_ENCONTRADO') => new HttpError(404, code);
export const conflict = (code) => new HttpError(409, code);
export const forbidden = () => new HttpError(403, 'SIN_PERMISO');

/** Texto obligatorio, recortado y con longitud máxima (las de las columnas del esquema). */
export function texto(valor, campo, { max, min = 1, opcional = false } = {}) {
  if (valor === undefined || valor === null) {
    if (opcional) return null;
    throw badRequest('DATOS_INVALIDOS', `Falta ${campo}`);
  }
  if (typeof valor !== 'string') throw badRequest('DATOS_INVALIDOS', `${campo} debe ser texto`);
  const t = valor.trim();
  if (t.length < min) {
    if (opcional && t.length === 0) return null;
    throw badRequest('DATOS_INVALIDOS', `${campo} está vacío`);
  }
  if (max && t.length > max) throw badRequest('DATOS_INVALIDOS', `${campo} supera ${max} caracteres`);
  return t;
}

const CORREO = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/;
export function correo(valor) {
  const c = texto(valor, 'correo', { max: 254 }).toLowerCase();
  if (!CORREO.test(c) || c.includes('..')) throw badRequest('CORREO_INVALIDO');
  return c;
}

/** Igual que telefonoEmergenciaValido() de la app: 10 a 15 dígitos. */
export function telefono(valor, campo = 'telefono') {
  const t = texto(valor, campo, { max: 32 });
  if (!/^\+?[0-9()\s-]+$/.test(t)) throw badRequest('TELEFONO_INVALIDO');
  const digitos = t.replace(/\D/g, '').length;
  if (digitos < 10 || digitos > 15) throw badRequest('TELEFONO_INVALIDO');
  return t;
}

const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function guid(valor) {
  if (typeof valor !== 'string' || !GUID.test(valor)) throw notFound();
  return valor;
}

/** Misma política que NuevaPasswordScreen. */
export function politicaPassword(clave) {
  return typeof clave === 'string' && clave.length >= 8 && clave.length <= 128 &&
    /[A-ZÁÉÍÓÚÑ]/.test(clave) && /[a-záéíóúñ]/.test(clave) && /\d/.test(clave) && /[^\p{L}\p{N}\s]/u.test(clave);
}

export const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest();
export const tokenAleatorio = () => crypto.randomBytes(32).toString('base64url');

/** "Bañuelos Martinez Jesus Rogelio": así muestra la app el nombre del alumno. */
export function nombreAlumno(row) {
  return [row.FirstSurname, row.SecondSurname, row.GivenNames].filter(Boolean).join(' ');
}

export function nombreUsuario(row) {
  return [row.GivenNames, row.Surnames].filter((s) => s && s.trim()).join(' ').trim();
}

export const normalizar = (s) => s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

/** Códigos de rol de la BD ↔ lo que muestra la app. Si un usuario tiene varios, gana el primero. */
export const PRIORIDAD_ROLES = ['ADMIN', 'DIRECTOR', 'NURSE', 'GUARDIAN'];
export const rolPrincipal = (roles) => PRIORIDAD_ROLES.find((r) => roles.includes(r)) ?? null;

export const ms = (d) => (d instanceof Date ? d.getTime() : d == null ? null : new Date(d).getTime());
