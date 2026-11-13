import { Router } from 'express';
import crypto from 'node:crypto';
import { permiso, tiene } from '../auth.js';
import { sql } from '../db.js';
import { badRequest, forbidden, notFound, guid, texto, nombreAlumno, normalizar, ms } from '../util.js';

/** Personal con acceso a todo el padrón; los tutores solo ven a sus hijos asociados. */
const veTodosLosAlumnos = (req) =>
  ['students.read', 'associations.manage', 'medical.read'].some((p) => tiene(req, p));

/** Dirección/Admin (read_all) y quien genera reportes ven todo; el tutor solo lo de sus hijos. */
function alcanceIncidencias(req) {
  if (tiene(req, 'incidents.read_all') || tiene(req, 'reports.read')) return { todos: 1 };
  if (tiene(req, 'incidents.read_linked')) return { todos: 0 };
  throw forbidden();
}

const FILTRO_VINCULO = (col) => `(@todos = 1 OR EXISTS (
    SELECT 1 FROM dbo.StudentGuardians g
     WHERE g.StudentId = ${col} AND g.GuardianUserId = @yo AND g.IsActive = 1))`;

const alumnoDto = (row) => ({ matricula: row.EnrollmentCode, nombre: nombreAlumno(row) });

/**
 * Misma regla que FakeIncidenciasRepository.buscarAlumno: acepta matrícula, nombre (2+ palabras)
 * o ambos; ignora mayúsculas/acentos y nunca elige "el primero" si hay varios.
 */
export async function buscarAlumno(db, req, consulta) {
  const tokens = normalizar(consulta).match(/[\p{L}\p{N}]+/gu)?.slice(0, 8) ?? [];
  if (tokens.length === 0) return null;
  const conLetras = tokens.filter((t) => /\p{L}/u.test(t));

  const params = { todos: veTodosLosAlumnos(req) ? 1 : 0, yo: req.user.id };
  tokens.forEach((t, i) => { params[`t${i}`] = t; });
  conLetras.forEach((t, i) => { params[`l${i}`] = `%${t}%`; });
  const porNombre = conLetras.length
    ? `OR (${conLetras.map((_, i) =>
        `CONCAT_WS(N' ', s.FirstSurname, s.SecondSurname, s.GivenNames) COLLATE Latin1_General_CI_AI LIKE @l${i}`).join(' AND ')})`
    : '';

  const { recordset } = await db.query(
    `SELECT TOP (50) s.StudentId, s.EnrollmentCode, s.GivenNames, s.FirstSurname, s.SecondSurname
       FROM dbo.Students s
      WHERE s.IsActive = 1 AND ${FILTRO_VINCULO('s.StudentId')}
        AND (s.EnrollmentCode IN (${tokens.map((_, i) => `@t${i}`).join(', ')}) ${porNombre})`,
    params,
  );

  const matricula = tokens.find((t) => recordset.some((a) => normalizar(a.EnrollmentCode) === t)) ?? null;
  const palabras = tokens.filter((t) => t !== matricula && /\p{L}/u.test(t));
  if (matricula === null && palabras.length < 2) return null;
  const coincidencias = recordset.filter((a) => {
    const delAlumno = new Set(normalizar(nombreAlumno(a)).match(/\p{L}+/gu) ?? []);
    return (matricula === null || normalizar(a.EnrollmentCode) === matricula) && palabras.every((p) => delAlumno.has(p));
  });
  return coincidencias.length === 1 ? coincidencias[0] : null;
}

async function catalogos(db) {
  const r = await db.query(
    `SELECT IncidentTypeCode AS codigo, DisplayName AS nombre FROM dbo.IncidentTypes WHERE IsActive = 1;
     SELECT SeverityCode AS codigo, DisplayName AS nombre FROM dbo.IncidentSeverities;`,
  );
  return { tipos: r.recordsets[0], gravedades: r.recordsets[1] };
}

/** La app trabaja con los nombres visibles ("Caída", "Fuerte"); la BD con códigos. */
async function codigos(db, tipo, gravedad) {
  const { tipos, gravedades } = await catalogos(db);
  const t = tipos.find((x) => x.nombre === tipo || x.codigo === tipo);
  const g = gravedades.find((x) => x.nombre === gravedad || x.codigo === gravedad);
  if (!t) throw badRequest('TIPO_INVALIDO');
  if (!g) throw badRequest('GRAVEDAD_INVALIDA');
  return { tipo: t.codigo, gravedad: g.codigo };
}

export function rutasAlumnosIncidencias({ db }) {
  const r = Router();

  /* ---------- Alumnos ---------- */
  r.get('/alumnos', permiso('students.read', 'associations.manage', 'incidents.create', 'medical.read'), async (req, res) => {
    const { recordset } = await db.query(
      `SELECT TOP (2000) s.StudentId, s.EnrollmentCode, s.GivenNames, s.FirstSurname, s.SecondSurname
         FROM dbo.Students s
        WHERE s.IsActive = 1 AND ${FILTRO_VINCULO('s.StudentId')}
        ORDER BY s.FirstSurname, s.SecondSurname, s.GivenNames`,
      { todos: veTodosLosAlumnos(req) ? 1 : 0, yo: req.user.id },
    );
    res.json(recordset.map(alumnoDto));
  });

  r.get('/alumnos/buscar', permiso('students.read', 'incidents.create', 'medical.read'), async (req, res) => {
    const q = typeof req.query.q === 'string' ? req.query.q.slice(0, 200) : '';
    const alumno = await buscarAlumno(db, req, q);
    if (!alumno) throw notFound('ALUMNO_NO_ENCONTRADO');
    res.json(alumnoDto(alumno));
  });

  r.get('/catalogos', async (_req, res) => {
    const { tipos, gravedades } = await catalogos(db);
    res.json({ tipos: tipos.map((t) => t.nombre), gravedades: gravedades.map((g) => g.nombre) });
  });

  /* ---------- Incidencias ---------- */
  r.get('/incidencias', async (req, res) => {
    const { todos } = alcanceIncidencias(req);
    const { recordset } = await db.query(
      `SELECT TOP (5000) i.IncidentId, i.Folio, i.OccurredAt, i.Description, i.StatusCode,
              s.EnrollmentCode, s.GivenNames, s.FirstSurname, s.SecondSurname,
              t.DisplayName AS Tipo, sv.DisplayName AS Gravedad
         FROM dbo.Incidents i
         JOIN dbo.Students s ON s.StudentId = i.StudentId
         JOIN dbo.IncidentTypes t ON t.IncidentTypeCode = i.IncidentTypeCode
         JOIN dbo.IncidentSeverities sv ON sv.SeverityCode = i.SeverityCode
        WHERE ${FILTRO_VINCULO('i.StudentId')}
        ORDER BY i.OccurredAt DESC`,
      { todos, yo: req.user.id },
    );
    res.json(recordset.map((x) => ({
      id: x.IncidentId,
      folio: x.Folio,
      alumno: alumnoDto(x),
      tipo: x.Tipo,
      gravedad: x.Gravedad,
      fecha: ms(x.OccurredAt),
      descripcion: x.Description,
      estado: x.StatusCode,
    })));
  });

  /** Para el aviso "Nueva incidencia registrada, actualice": cambia con cada alta/edición/baja. */
  r.get('/incidencias/version', async (req, res) => {
    alcanceIncidencias(req);
    const { recordset } = await db.query(
      `SELECT CONVERT(VARCHAR(20), MAX(AuditEventId)) AS v FROM dbo.AuditEvents WHERE EntityCode = 'INCIDENT'`,
    );
    res.json({ version: recordset[0]?.v ?? '0' });
  });

  r.post('/incidencias', permiso('incidents.create'), async (req, res) => {
    const b = req.body ?? {};
    const matricula = texto(b.matricula, 'matricula', { max: 32 });
    const descripcion = texto(b.descripcion ?? '', 'descripcion', { max: 3000, min: 0 });
    const fecha = Number(b.fecha);
    if (!Number.isFinite(fecha) || fecha > Date.now() + 5 * 60_000 || fecha < Date.UTC(2000, 0, 1)) {
      throw badRequest('FECHA_INVALIDA');
    }
    const cod = await codigos(db, b.tipo, b.gravedad);

    const { recordset } = await db.query(
      `SELECT s.StudentId FROM dbo.Students s
        WHERE s.EnrollmentCode = @m AND s.IsActive = 1 AND ${FILTRO_VINCULO('s.StudentId')}`,
      { m: matricula, todos: veTodosLosAlumnos(req) ? 1 : 0, yo: req.user.id },
    );
    const studentId = recordset[0]?.StudentId;
    if (!studentId) throw notFound('ALUMNO_NO_ENCONTRADO');

    const id = crypto.randomUUID();
    const folio = await db.tx(req.user.id, async (q) => {
      const f = await q(
        `DECLARE @n BIGINT = NEXT VALUE FOR dbo.IncidentFolioSeq;
         DECLARE @folio VARCHAR(32) = CONCAT('INC-', YEAR(SYSUTCDATETIME()), '-', RIGHT(CONCAT('000000', @n), 6));
         INSERT dbo.Incidents(IncidentId, Folio, StudentId, IncidentTypeCode, SeverityCode, OccurredAt, Description, ReportedByUserId)
         VALUES (@id, @folio, @s, @tipo, @grav, @fecha, @desc, @yo);
         INSERT dbo.NotificationOutbox(RecipientUserId, StudentId, IncidentId, EventCode, ChannelCode, DedupeKey)
         SELECT g.GuardianUserId, @s, @id, 'INCIDENT_CREATED', 'IN_APP',
                CONCAT('INCIDENT_CREATED:', @id, ':', CONVERT(VARCHAR(36), g.GuardianUserId), ':IN_APP')
           FROM dbo.StudentGuardians g
          WHERE g.StudentId = @s AND g.IsActive = 1 AND g.ReceivesAlerts = 1;
         SELECT @folio AS Folio;`,
        {
          id, s: studentId, tipo: cod.tipo, grav: cod.gravedad, desc: descripcion, yo: req.user.id,
          fecha: { type: sql.DateTimeOffset(0), value: new Date(fecha) },
        },
      );
      return f.recordset[0].Folio;
    });
    res.status(201).json({ id, folio });
  });

  r.put('/incidencias/:id', permiso('incidents.update_all', 'incidents.update_own'), async (req, res) => {
    const id = guid(req.params.id);
    const b = req.body ?? {};
    const descripcion = texto(b.descripcion ?? '', 'descripcion', { max: 3000, min: 0 });
    const cod = await codigos(db, b.tipo, b.gravedad);
    const todas = tiene(req, 'incidents.update_all') ? 1 : 0;

    const filas = await db.tx(req.user.id, async (q) => {
      const u = await q(
        `UPDATE dbo.Incidents
            SET IncidentTypeCode = @tipo, SeverityCode = @grav, Description = @desc,
                UpdatedAtUtc = SYSUTCDATETIME(), UpdatedByUserId = @yo
          WHERE IncidentId = @id
            AND (@todas = 1 OR (ReportedByUserId = @yo AND StatusCode <> 'CLOSED'))`,
        { id, tipo: cod.tipo, grav: cod.gravedad, desc: descripcion, yo: req.user.id, todas },
      );
      return u.rowsAffected[0];
    });
    if (filas === 1) return res.status(204).end();
    const existe = await db.query('SELECT 1 AS x FROM dbo.Incidents WHERE IncidentId = @id', { id });
    throw existe.recordset.length ? forbidden() : notFound();
  });

  /** POST (no DELETE) porque lleva cuerpo con el motivo; usa usp_DeleteIncident (HU-18/RF-17). */
  r.post('/incidencias/:id/eliminar', permiso('incidents.delete'), async (req, res) => {
    const id = guid(req.params.id);
    const motivo = texto(req.body?.motivo, 'motivo', { max: 1000 });
    try {
      await db.query(
        'EXEC dbo.usp_DeleteIncident @IncidentId = @id, @ActorUserId = @yo, @Reason = @motivo',
        { id, yo: req.user.id, motivo },
      );
    } catch (err) {
      if (err.number === 51011) throw badRequest('MOTIVO_OBLIGATORIO');
      if (err.number === 51012) throw forbidden();
      if (err.number === 51013) throw notFound();
      throw err;
    }
    res.status(204).end();
  });

  /* ---------- Bitácora / avisos ---------- */
  r.get('/bitacora', permiso('audit.read', 'notifications.read'), async (req, res) => {
    // Sin audit.read ni read_all (tutor) solo ve eventos de sus hijos.
    const todos = tiene(req, 'audit.read') || tiene(req, 'incidents.read_all') || tiene(req, 'reports.read') ? 1 : 0;
    const { recordset } = await db.query(
      `SELECT TOP (1000) a.AuditEventId, a.ActionCode, a.EntityId, a.EntityKey, a.OccurredAtUtc, a.MetadataJson,
              u.Email, u.GivenNames, u.Surnames
         FROM dbo.AuditEvents a
         LEFT JOIN dbo.Users u ON u.UserId = a.ActorUserId
        WHERE a.EntityCode = 'INCIDENT' AND a.ActionCode IN ('CREATE', 'UPDATE', 'DELETE')
          AND ${FILTRO_VINCULO("TRY_CONVERT(UNIQUEIDENTIFIER, JSON_VALUE(a.MetadataJson, '$.studentId'))")}
        ORDER BY a.AuditEventId DESC`,
      { todos, yo: req.user.id },
    );
    const ACCION = { CREATE: 'Registrada', UPDATE: 'Editada', DELETE: 'Eliminada' };
    res.json(recordset.map((a) => {
      let m = {};
      try { m = a.MetadataJson ? JSON.parse(a.MetadataJson) : {}; } catch { /* metadatos viejos */ }
      const actor = a.Email ? ([a.GivenNames, a.Surnames].filter((s) => s && s.trim()).join(' ').trim() || a.Email) : null;
      return {
        id: String(a.AuditEventId),
        incidenciaId: a.EntityId,
        folio: a.EntityKey ?? '',
        accion: ACCION[a.ActionCode],
        alumno: { matricula: m.matricula ?? '', nombre: m.alumno ?? '' },
        tipo: m.tipo ?? '',
        gravedad: m.gravedad ?? '',
        fechaIncidente: m.fechaIncidente ? Date.parse(m.fechaIncidente) : ms(a.OccurredAtUtc),
        registradoEn: ms(a.OccurredAtUtc),
        resumen: m.resumen ?? '',
        actor,
      };
    }));
  });

  return r;
}

