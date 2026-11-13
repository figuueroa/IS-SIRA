import { Router } from 'express';
import { permiso, tiene, crearTokenSeguridad } from '../auth.js';
import { HttpError, conflict, notFound, texto, correo as validarCorreo, telefono, nombreUsuario, nombreAlumno,
  rolPrincipal, PRIORIDAD_ROLES, badRequest, forbidden, ms } from '../util.js';

/** "Ana Sofía Ramírez López" → nombres "Ana Sofía", apellidos "Ramírez López" (los dos últimos). */
export function separarNombre(nombre) {
  const p = nombre.split(/\s+/).filter(Boolean);
  if (p.length <= 1) return { nombres: p.join(' '), apellidos: '' };
  const n = p.length >= 3 ? p.length - 2 : 1;
  return { nombres: p.slice(0, n).join(' '), apellidos: p.slice(n).join(' ') };
}

function rolValido(valor) {
  const r = typeof valor === 'string' ? valor.trim().toUpperCase() : '';
  if (!PRIORIDAD_ROLES.includes(r)) throw badRequest('ROL_INVALIDO');
  return r;
}

const soloDigitos = (s) => (s ?? '').replace(/\D/g, '');

export function rutasAdmin({ db, config, log }) {
  const r = Router();

  /* ---------- Usuarios (I-09) ---------- */
  r.get('/usuarios', permiso('users.manage', 'roles.manage'), async (_req, res) => {
    const { recordset } = await db.query(
      `SELECT u.UserId, u.Email, u.GivenNames, u.Surnames, u.AccountStatus, r.RoleCode
         FROM dbo.Users u
         LEFT JOIN dbo.UserRoles ur ON ur.UserId = u.UserId
         LEFT JOIN dbo.Roles r ON r.RoleId = ur.RoleId
        ORDER BY u.CreatedAtUtc`,
    );
    const porId = new Map();
    for (const f of recordset) {
      const u = porId.get(f.UserId) ?? { fila: f, roles: [] };
      if (f.RoleCode) u.roles.push(f.RoleCode);
      porId.set(f.UserId, u);
    }
    res.json([...porId.values()]
      .filter((u) => rolPrincipal(u.roles))
      .map(({ fila, roles }) => ({
        nombre: nombreUsuario(fila) || fila.Email,
        correo: fila.Email.toLowerCase(),
        rol: rolPrincipal(roles),
        estado: fila.AccountStatus,
      })));
  });

  r.post('/usuarios', permiso('users.manage'), async (req, res) => {
    const nombre = texto(req.body?.nombre, 'nombre', { max: 280 });
    const email = validarCorreo(req.body?.correo);
    const rol = rolValido(req.body?.rol);
    const { nombres, apellidos } = separarNombre(nombre);
    if (nombres.length > 120 || apellidos.length > 160) throw badRequest('NOMBRE_DEMASIADO_LARGO');

    const userId = await db.tx(req.user.id, async (q) => {
      const ya = await q('SELECT 1 AS x FROM dbo.Users WHERE EmailNormalized = @e', { e: email });
      if (ya.recordset.length) return null;
      const ins = await q(
        `DECLARE @nuevo TABLE (UserId UNIQUEIDENTIFIER);
         INSERT dbo.Users(Email, GivenNames, Surnames) OUTPUT inserted.UserId INTO @nuevo
         VALUES (@e, @n, @a);
         DECLARE @u UNIQUEIDENTIFIER = (SELECT UserId FROM @nuevo);
         INSERT dbo.UserRoles(UserId, RoleId, AssignedByUserId)
         SELECT @u, RoleId, @yo FROM dbo.Roles WHERE RoleCode = @rol;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, MetadataJson)
         VALUES (@yo, 'CREATE', 'USER', @u, @e, (SELECT @rol AS rol FOR JSON PATH, WITHOUT_ARRAY_WRAPPER));
         SELECT @u AS UserId;`,
        { e: email, n: nombres, a: apellidos, yo: req.user.id, rol },
      );
      return ins.recordset[0].UserId;
    }).catch((err) => {
      if (err.number === 2601 || err.number === 2627) return null;
      throw err;
    });
    if (!userId) throw conflict('CORREO_DUPLICADO');

    const token = await crearTokenSeguridad(db, userId, 'ACTIVATION', 72 * 60);
    // Hasta integrar un proveedor de correo, solo se muestran tokens en desarrollo.
    if (!config.production) log.info(`[activación] ${email}: POST /api/auth/restablecer con token=${token} (válido 72 h)`);
    else log.warn('Se creó una cuenta pendiente, pero no hay un proveedor de correo configurado.');
    res.status(201).json({ correo: email, estado: 'PENDING_ACTIVATION' });
  });

  /* ---------- Roles (I-10) ---------- */
  r.put('/usuarios/rol', permiso('roles.manage'), async (req, res) => {
    const email = validarCorreo(req.body?.correo);
    const rol = rolValido(req.body?.rol);
    const resultado = await db.tx(req.user.id, async (q) => {
      const u = await q(
        `SELECT u.UserId, r.RoleCode FROM dbo.Users u
           LEFT JOIN dbo.UserRoles ur ON ur.UserId = u.UserId LEFT JOIN dbo.Roles r ON r.RoleId = ur.RoleId
          WHERE u.EmailNormalized = @e`,
        { e: email },
      );
      if (!u.recordset.length) return 'NO_EXISTE';
      const userId = u.recordset[0].UserId;
      const anterior = rolPrincipal(u.recordset.map((x) => x.RoleCode).filter(Boolean));
      if (userId.toLowerCase() === req.user.id.toLowerCase() && rol !== 'ADMIN') return 'PROPIO';
      if (anterior === rol) return 'OK';
      await q(
        `DELETE dbo.UserRoles WHERE UserId = @u;
         INSERT dbo.UserRoles(UserId, RoleId, AssignedByUserId) SELECT @u, RoleId, @yo FROM dbo.Roles WHERE RoleCode = @rol;
         UPDATE dbo.UserSessions SET RevokedAtUtc = SYSUTCDATETIME() WHERE UserId = @u AND RevokedAtUtc IS NULL;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, MetadataJson)
         VALUES (@yo, 'ROLE_CHANGE', 'USER', @u, @e, (SELECT @de AS de, @rol AS a FOR JSON PATH, WITHOUT_ARRAY_WRAPPER));`,
        { u: userId, yo: req.user.id, rol, e: email, de: anterior },
      );
      return 'OK';
    });
    if (resultado === 'NO_EXISTE') throw notFound('USUARIO_NO_ENCONTRADO');
    if (resultado === 'PROPIO') throw conflict('NO_PUEDES_QUITARTE_ADMIN');
    res.status(204).end();
  });

  /* ---------- Tutores y vínculos alumno-familia (I-08) ---------- */
  r.get('/tutores', async (req, res) => {
    const todos = tiene(req, 'associations.manage') || tiene(req, 'guardians.manage');
    if (!todos && !tiene(req, 'incidents.read_linked')) throw forbidden();
    const { recordset } = await db.query(
      `SELECT u.Email, u.GivenNames, u.Surnames, u.Phone,
              s.EnrollmentCode, s.GivenNames AS AGivenNames, s.FirstSurname, s.SecondSurname
         FROM dbo.StudentGuardians g
         JOIN dbo.Users u ON u.UserId = g.GuardianUserId
         JOIN dbo.Students s ON s.StudentId = g.StudentId
        WHERE g.IsActive = 1 AND (@todos = 1 OR g.GuardianUserId = @yo)
        ORDER BY g.CreatedAtUtc`,
      { todos: todos ? 1 : 0, yo: req.user.id },
    );
    res.json(recordset.map((f) => ({
      nombre: nombreUsuario(f) || f.Email,
      correo: f.Email.toLowerCase(),
      telefono: f.Phone ?? '',
      alumno: {
        matricula: f.EnrollmentCode,
        nombre: nombreAlumno({ FirstSurname: f.FirstSurname, SecondSurname: f.SecondSurname, GivenNames: f.AGivenNames }),
      },
    })));
  });

  r.post('/tutores', permiso('associations.manage'), async (req, res) => {
    const b = req.body ?? {};
    const nombre = texto(b.nombre, 'nombre', { max: 280 });
    const email = validarCorreo(b.correo);
    const tel = telefono(b.telefono);
    const matricula = texto(b.matricula, 'matricula', { max: 32 });
    const { nombres, apellidos } = separarNombre(nombre);
    const yo = req.user.id;

    const resultado = await db.tx(yo, async (q) => {
      const s = await q('SELECT StudentId FROM dbo.Students WHERE EnrollmentCode = @m AND IsActive = 1', { m: matricula });
      const studentId = s.recordset[0]?.StudentId;
      if (!studentId) return { error: 'ALUMNO' };

      const u = await q(
        `SELECT u.UserId, u.GivenNames, u.Surnames, u.Phone,
                CASE WHEN EXISTS (SELECT 1 FROM dbo.UserRoles ur JOIN dbo.Roles r ON r.RoleId = ur.RoleId
                                   WHERE ur.UserId = u.UserId AND r.RoleCode = 'GUARDIAN') THEN 1 ELSE 0 END AS EsTutor
           FROM dbo.Users u WHERE u.EmailNormalized = @e`,
        { e: email },
      );
      let guardianId = u.recordset[0]?.UserId;
      let nuevo = false;
      if (guardianId) {
        const f = u.recordset[0];
        const mismoNombre = nombreUsuario(f).localeCompare(nombre, 'es', { sensitivity: 'accent' }) === 0;
        if (!f.EsTutor || !mismoNombre || soloDigitos(f.Phone) !== soloDigitos(tel)) return { error: 'CORREO_EN_USO' };
        const v = await q(
          'SELECT 1 AS x FROM dbo.StudentGuardians WHERE StudentId = @s AND GuardianUserId = @g AND IsActive = 1',
          { s: studentId, g: guardianId },
        );
        if (v.recordset.length) return { error: 'VINCULO_DUPLICADO' };
      } else {
        const ins = await q(
          `DECLARE @nuevo TABLE (UserId UNIQUEIDENTIFIER);
           INSERT dbo.Users(Email, GivenNames, Surnames, Phone) OUTPUT inserted.UserId INTO @nuevo VALUES (@e, @n, @a, @tel);
           DECLARE @u UNIQUEIDENTIFIER = (SELECT UserId FROM @nuevo);
           INSERT dbo.UserRoles(UserId, RoleId, AssignedByUserId) SELECT @u, RoleId, @yo FROM dbo.Roles WHERE RoleCode = 'GUARDIAN';
           INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, MetadataJson)
           VALUES (@yo, 'CREATE', 'USER', @u, @e, N'{"rol":"GUARDIAN"}');
           SELECT @u AS UserId;`,
          { e: email, n: nombres, a: apellidos, tel, yo },
        );
        guardianId = ins.recordset[0].UserId;
        nuevo = true;
      }
      await q(
        `INSERT dbo.StudentGuardians(StudentId, GuardianUserId, RelationshipCode, IsPrimaryContact, CreatedByUserId)
         SELECT @s, @g, 'OTHER',
                CASE WHEN EXISTS (SELECT 1 FROM dbo.StudentGuardians WHERE StudentId = @s AND IsActive = 1) THEN 0 ELSE 1 END, @yo;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey)
         VALUES (@yo, 'CREATE', 'STUDENT_GUARDIAN', @s, @e);`,
        { s: studentId, g: guardianId, yo, e: email },
      );
      return { guardianId, nuevo };
    }).catch((err) => {
      if (err.number === 2601 || err.number === 2627) return { error: 'VINCULO_DUPLICADO' };
      throw err;
    });

    if (resultado.error === 'ALUMNO') throw notFound('ALUMNO_NO_ENCONTRADO');
    if (resultado.error) throw conflict(resultado.error);
    if (resultado.nuevo) {
      const token = await crearTokenSeguridad(db, resultado.guardianId, 'ACTIVATION', 72 * 60);
      if (!config.production) log.info(`[activación] tutor ${email}: POST /api/auth/restablecer con token=${token} (válido 72 h)`);
      else log.warn('Se creó una cuenta de tutor pendiente, pero no hay un proveedor de correo configurado.');
    }
    res.status(201).json({ ok: true });
  });

  /* ---------- Respaldos (I-14) ---------- */
  const archivoDe = (ruta) => (ruta ?? '').split(/[\\/]/).pop();

  r.get('/respaldos', permiso('backups.manage'), async (_req, res) => {
    const { recordset } = await db.query(
      `SELECT TOP (200) BackupRunId, StartedAtUtc, StatusCode, StorageKey FROM dbo.BackupRuns ORDER BY StartedAtUtc DESC, BackupRunId DESC`,
    );
    res.json(recordset.map((b) => ({
      archivo: archivoDe(b.StorageKey) || `respaldo-${b.BackupRunId}`,
      fecha: ms(b.StartedAtUtc),
      correcto: b.StatusCode === 'SUCCEEDED',
      estado: b.StatusCode,
    })));
  });

  r.post('/respaldos', permiso('backups.manage'), async (req, res) => {
    if (!config.backupDir) throw new HttpError(503, 'RESPALDOS_NO_CONFIGURADOS');
    const ahora = new Date();
    const sello = ahora.toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
    const archivo = `sira_${sello}.bak`;
    const sep = config.backupDir.includes('\\') ? '\\' : '/';
    const ruta = config.backupDir.replace(/[\\/]+$/, '') + sep + archivo;

    const ins = await db.query(
      `INSERT dbo.BackupRuns(StorageKey, RequestedByUserId) OUTPUT inserted.BackupRunId VALUES (@ruta, @yo)`,
      { ruta, yo: req.user.id },
    );
    const runId = ins.recordset[0].BackupRunId;
    try {
      // BACKUP no puede ir dentro de una transacción; ruta y nombre van como variables.
      await db.query(
        `DECLARE @db SYSNAME = DB_NAME();
         BACKUP DATABASE @db TO DISK = @ruta WITH COPY_ONLY, INIT, CHECKSUM, NAME = @nombre;`,
        { ruta, nombre: archivo },
      );
      await db.query(
        `UPDATE dbo.BackupRuns SET StatusCode = 'SUCCEEDED', CompletedAtUtc = SYSUTCDATETIME() WHERE BackupRunId = @id;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityKey) VALUES (@yo, 'BACKUP', 'DATABASE', @archivo);`,
        { id: runId, yo: req.user.id, archivo },
      );
    } catch (err) {
      log.error(`Respaldo fallido: ${err.message}`);
      await db.query(
        `UPDATE dbo.BackupRuns SET StatusCode = 'FAILED', CompletedAtUtc = SYSUTCDATETIME(), ErrorCode = @code WHERE BackupRunId = @id`,
        { id: runId, code: `SQL_${err.number ?? 'ERROR'}`.slice(0, 80) },
      );
      throw new HttpError(502, 'RESPALDO_FALLIDO');
    }
    res.status(201).json({ archivo, fecha: ms(ahora), correcto: true });
  });

  return r;
}
