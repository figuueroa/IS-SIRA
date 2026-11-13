import { Router } from 'express';
import crypto from 'node:crypto';
import { permiso } from '../auth.js';
import { badRequest, conflict, notFound, texto, telefono, nombreUsuario, ms } from '../util.js';

/* La app muestra "A−" (signo menos tipográfico) y "No identificado"; la BD guarda 'A-' y 'UNKNOWN'. */
const SANGRE_BD = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];
export function sangreABd(valor) {
  const v = texto(valor, 'tipoSangre', { max: 20 });
  if (v.toLowerCase() === 'no identificado' || v.toUpperCase() === 'UNKNOWN') return 'UNKNOWN';
  const ascii = v.replace(/[−–—]/g, '-').toUpperCase();
  if (!SANGRE_BD.includes(ascii)) throw badRequest('TIPO_SANGRE_INVALIDO');
  return ascii;
}
export const sangreAApp = (bd) => (bd == null ? '' : bd === 'UNKNOWN' ? 'No identificado' : bd.replace('-', '−'));

const RUBROS = [
  ['alergias', 'ALLERGY', 'Alergias'],
  ['padecimientos', 'CHRONIC_CONDITION', 'Padecimientos crónicos'],
  ['medicamentos', 'MEDICATION', 'Medicamentos'],
];

function leerExpediente(b) {
  const datos = { tipoSangre: sangreABd(b.tipoSangre) };
  for (const [campo] of RUBROS) datos[campo] = texto(b[campo] ?? '', campo, { max: 1000, min: 0 });
  datos.contactoEmergencia = texto(b.contactoEmergencia, 'contactoEmergencia', { max: 200 });
  datos.telefonoEmergencia = telefono(b.telefonoEmergencia, 'telefonoEmergencia');
  return datos;
}

async function insertarRubrosYContacto(q, { recordId, studentId, yo, datos }) {
  for (const [campo, codigo, etiqueta] of RUBROS) {
    if (!datos[campo]) continue;
    await q(
      `INSERT dbo.MedicalRecordItems(MedicalRecordId, ItemTypeCode, Name, Details, CreatedByUserId, UpdatedByUserId)
       VALUES (@mr, @tipo, @nombre, @det, @yo, @yo)`,
      { mr: recordId, tipo: codigo, nombre: etiqueta, det: datos[campo], yo },
    );
  }
  await q(
    `INSERT dbo.EmergencyContacts(StudentId, FullName, RelationshipCode, Phone, PriorityOrder)
     VALUES (@s, @nombre, 'OTHER', @tel, 1)`,
    { s: studentId, nombre: datos.contactoEmergencia, tel: datos.telefonoEmergencia },
  );
}

export function rutasSalud({ db }) {
  const r = Router();

  /* ---------- Expediente médico (I-05 / I-06) ---------- */
  r.get('/expedientes/:matricula', permiso('medical.read'), async (req, res) => {
    const m = texto(req.params.matricula, 'matricula', { max: 32 });
    const x = await db.query(
      `SELECT TOP (1) mr.MedicalRecordId, mr.BloodType, mr.CreatedAtUtc, mr.UpdatedAtUtc, s.StudentId,
              u.Email, u.GivenNames, u.Surnames
         FROM dbo.Students s
         JOIN dbo.MedicalRecords mr ON mr.StudentId = s.StudentId
         LEFT JOIN dbo.Users u ON u.UserId = mr.UpdatedByUserId
        WHERE s.EnrollmentCode = @m;
       SELECT it.ItemTypeCode, it.Details
         FROM dbo.MedicalRecordItems it
         JOIN dbo.MedicalRecords mr ON mr.MedicalRecordId = it.MedicalRecordId
         JOIN dbo.Students s ON s.StudentId = mr.StudentId
        WHERE s.EnrollmentCode = @m AND it.IsActive = 1
        ORDER BY it.UpdatedAtUtc;
       SELECT TOP (1) ec.FullName, ec.Phone
         FROM dbo.EmergencyContacts ec
         JOIN dbo.Students s ON s.StudentId = ec.StudentId
        WHERE s.EnrollmentCode = @m AND ec.IsActive = 1
        ORDER BY ec.PriorityOrder, ec.EmergencyContactId DESC;`,
      { m },
    );
    const exp = x.recordsets[0][0];
    if (!exp) throw notFound('SIN_EXPEDIENTE');

    // Datos de salud de un menor: cada consulta queda en la bitácora (sin el contenido).
    await db.query(
      `INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey)
       VALUES (@yo, 'READ', 'MEDICAL_RECORD', @id, @m)`,
      { yo: req.user.id, id: exp.MedicalRecordId, m },
    );

    const items = x.recordsets[1];
    const rubro = (codigo) => items.filter((i) => i.ItemTypeCode === codigo).map((i) => i.Details ?? '').join('\n');
    const contacto = x.recordsets[2][0];
    res.json({
      matriculaAlumno: m,
      tipoSangre: sangreAApp(exp.BloodType),
      alergias: rubro('ALLERGY'),
      padecimientos: rubro('CHRONIC_CONDITION'),
      medicamentos: rubro('MEDICATION'),
      contactoEmergencia: contacto?.FullName ?? '',
      telefonoEmergencia: contacto?.Phone ?? '',
      creadoEn: ms(exp.CreatedAtUtc),
      actualizadoEn: ms(exp.UpdatedAtUtc),
      actualizadoPor: exp.Email ? nombreUsuario(exp) || exp.Email : null,
    });
  });

  r.post('/expedientes', permiso('medical.write'), async (req, res) => {
    const b = req.body ?? {};
    const m = texto(b.matriculaAlumno, 'matriculaAlumno', { max: 32 });
    const datos = leerExpediente(b);
    const yo = req.user.id;
    const resultado = await db.tx(yo, async (q) => {
      const s = await q(
        `SELECT s.StudentId, CASE WHEN mr.MedicalRecordId IS NULL THEN 0 ELSE 1 END AS Existe
           FROM dbo.Students s LEFT JOIN dbo.MedicalRecords mr ON mr.StudentId = s.StudentId
          WHERE s.EnrollmentCode = @m`,
        { m },
      );
      const alumno = s.recordset[0];
      if (!alumno) return 'NO_ALUMNO';
      if (alumno.Existe) return 'DUPLICADO';
      const recordId = crypto.randomUUID();
      await q(
        `INSERT dbo.MedicalRecords(MedicalRecordId, StudentId, BloodType, CreatedByUserId, UpdatedByUserId)
         VALUES (@id, @s, @sangre, @yo, @yo);
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey)
         VALUES (@yo, 'CREATE', 'MEDICAL_RECORD', @id, @m);`,
        { id: recordId, s: alumno.StudentId, sangre: datos.tipoSangre, yo, m },
      );
      await insertarRubrosYContacto(q, { recordId, studentId: alumno.StudentId, yo, datos });
      return 'OK';
    }).catch((err) => {
      if (err.number === 2627 || err.number === 2601) return 'DUPLICADO'; // dos registros al mismo tiempo
      throw err;
    });
    if (resultado === 'NO_ALUMNO') throw notFound('ALUMNO_NO_ENCONTRADO');
    if (resultado === 'DUPLICADO') throw conflict('EXPEDIENTE_DUPLICADO');
    res.status(201).json({ ok: true });
  });

  /** Los rubros y el contacto anteriores se desactivan (IsActive = 0), no se borran: queda historial. */
  r.put('/expedientes/:matricula', permiso('medical.write'), async (req, res) => {
    const m = texto(req.params.matricula, 'matricula', { max: 32 });
    const datos = leerExpediente(req.body ?? {});
    const yo = req.user.id;
    const ok = await db.tx(yo, async (q) => {
      const x = await q(
        `SELECT mr.MedicalRecordId, s.StudentId
           FROM dbo.Students s JOIN dbo.MedicalRecords mr ON mr.StudentId = s.StudentId
          WHERE s.EnrollmentCode = @m`,
        { m },
      );
      const exp = x.recordset[0];
      if (!exp) return false;
      await q(
        `UPDATE dbo.MedicalRecords SET BloodType = @sangre, UpdatedAtUtc = SYSUTCDATETIME(), UpdatedByUserId = @yo
          WHERE MedicalRecordId = @mr;
         UPDATE dbo.MedicalRecordItems SET IsActive = 0, UpdatedAtUtc = SYSUTCDATETIME(), UpdatedByUserId = @yo
          WHERE MedicalRecordId = @mr AND IsActive = 1 AND ItemTypeCode IN ('ALLERGY', 'CHRONIC_CONDITION', 'MEDICATION');
         UPDATE dbo.EmergencyContacts SET IsActive = 0
          WHERE StudentId = @s AND IsActive = 1 AND PriorityOrder = 1;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey)
         VALUES (@yo, 'UPDATE', 'MEDICAL_RECORD', @mr, @m);`,
        { sangre: datos.tipoSangre, yo, mr: exp.MedicalRecordId, s: exp.StudentId, m },
      );
      await insertarRubrosYContacto(q, { recordId: exp.MedicalRecordId, studentId: exp.StudentId, yo, datos });
      return true;
    });
    if (!ok) throw notFound('SIN_EXPEDIENTE');
    res.status(204).end();
  });

  /* ---------- Ingresos a enfermería (I-04) ---------- */
  const ingresoDto = (m) => (v) => ({
    id: v.MedicalVisitId, matricula: m, fecha: ms(v.EntryAtUtc), motivo: v.Reason, tratamiento: v.Treatment ?? '',
  });

  r.get('/alumnos/:matricula/ingresos', permiso('medical.read'), async (req, res) => {
    const m = texto(req.params.matricula, 'matricula', { max: 32 });
    const { recordset } = await db.query(
      `SELECT TOP (500) v.MedicalVisitId, v.EntryAtUtc, v.Reason, v.Treatment
         FROM dbo.MedicalVisits v JOIN dbo.Students s ON s.StudentId = v.StudentId
        WHERE s.EnrollmentCode = @m
        ORDER BY v.EntryAtUtc DESC, v.CreatedAtUtc DESC`,
      { m },
    );
    res.json(recordset.map(ingresoDto(m)));
  });

  r.post('/alumnos/:matricula/ingresos', permiso('medical_visits.create'), async (req, res) => {
    const m = texto(req.params.matricula, 'matricula', { max: 32 });
    const motivo = texto(req.body?.motivo, 'motivo', { max: 500 });
    const tratamiento = texto(req.body?.tratamiento ?? '', 'tratamiento', { max: 2000, min: 0 });
    const id = crypto.randomUUID();
    const r2 = await db.tx(req.user.id, async (q) => q(
      `DECLARE @s UNIQUEIDENTIFIER = (SELECT StudentId FROM dbo.Students WHERE EnrollmentCode = @m);
       IF @s IS NOT NULL
       BEGIN
         INSERT dbo.MedicalVisits(MedicalVisitId, StudentId, EntryAtUtc, Reason, Treatment, CreatedByUserId)
         VALUES (@id, @s, TODATETIMEOFFSET(SYSUTCDATETIME(), 0), @motivo, @trat, @yo);
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey)
         VALUES (@yo, 'CREATE', 'MEDICAL_VISIT', @id, @m);
         SELECT MedicalVisitId, EntryAtUtc, Reason, Treatment FROM dbo.MedicalVisits WHERE MedicalVisitId = @id;
       END`,
      { m, id, motivo, trat: tratamiento || null, yo: req.user.id },
    ));
    const fila = r2.recordset?.[0];
    if (!fila) throw notFound('ALUMNO_NO_ENCONTRADO');
    res.status(201).json(ingresoDto(m)(fila));
  });

  return r;
}
