import { Router } from 'express';
import bcrypt from 'bcryptjs';
import { HttpError, correo as validarCorreo, politicaPassword, rolPrincipal, sha256, tokenAleatorio, nombreUsuario, badRequest } from './util.js';

// Hash cualquiera para gastar el mismo tiempo cuando el correo no existe (evita descubrir cuentas).
const HASH_SEÑUELO = bcrypt.hashSync('señuelo-sin-uso', 10);
const BCRYPT_COSTO = 12;

export const hashPassword = (clave) => bcrypt.hash(clave, BCRYPT_COSTO);

/**
 * Lee "Authorization: Bearer <token>", busca la sesión en UserSessions (se guarda solo el SHA-256
 * del token) y deja en req.user: { id, correo, nombre, rol, roles[], permisos:Set }.
 */
export function autenticar(db) {
  return async (req, _res, next) => {
    try {
      const header = req.get('authorization') ?? '';
      const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
      if (!token) throw new HttpError(401, 'SIN_SESION');

      const r = await db.query(
        `SELECT TOP (1) s.UserSessionId, u.UserId, u.Email, u.GivenNames, u.Surnames
           FROM dbo.UserSessions s
           JOIN dbo.Users u ON u.UserId = s.UserId
          WHERE s.RefreshTokenHash = @h AND s.RevokedAtUtc IS NULL
            AND s.ExpiresAtUtc > SYSUTCDATETIME() AND u.AccountStatus = 'ACTIVE';

         SELECT r.RoleCode, p.PermissionCode
           FROM dbo.UserSessions s
           JOIN dbo.UserRoles ur ON ur.UserId = s.UserId
           JOIN dbo.Roles r ON r.RoleId = ur.RoleId AND r.IsActive = 1
           LEFT JOIN dbo.RolePermissions rp ON rp.RoleId = r.RoleId
           LEFT JOIN dbo.Permissions p ON p.PermissionId = rp.PermissionId
          WHERE s.RefreshTokenHash = @h;

         UPDATE dbo.UserSessions SET LastUsedAtUtc = SYSUTCDATETIME()
          WHERE RefreshTokenHash = @h
            AND (LastUsedAtUtc IS NULL OR LastUsedAtUtc < DATEADD(MINUTE, -5, SYSUTCDATETIME()));`,
        { h: sha256(token) },
      );
      const fila = r.recordsets[0]?.[0];
      if (!fila) throw new HttpError(401, 'SESION_INVALIDA');

      const filas = r.recordsets[1] ?? [];
      const roles = [...new Set(filas.map((f) => f.RoleCode))];
      req.user = {
        id: fila.UserId,
        sesionId: fila.UserSessionId,
        correo: fila.Email,
        nombre: nombreUsuario(fila) || fila.Email,
        roles,
        rol: rolPrincipal(roles),
        permisos: new Set(filas.map((f) => f.PermissionCode).filter(Boolean)),
      };
      next();
    } catch (err) {
      next(err);
    }
  };
}

/** Exige al menos uno de los permisos (códigos de dbo.Permissions). */
export const permiso = (...codigos) => (req, _res, next) =>
  codigos.some((c) => req.user?.permisos.has(c)) ? next() : next(new HttpError(403, 'SIN_PERMISO'));

export const tiene = (req, codigo) => req.user?.permisos.has(codigo) ?? false;

export function rutasAuth({ db, config, log }) {
  const r = Router();

  r.post('/login', async (req, res) => {
    const email = typeof req.body?.correo === 'string' ? req.body.correo.trim().toLowerCase() : '';
    const clave = typeof req.body?.clave === 'string' ? req.body.clave : '';
    if (!email || !clave) throw new HttpError(401, 'CREDENCIALES_INVALIDAS');

    const { recordset } = await db.query(
      `SELECT TOP (1) UserId, Email, GivenNames, Surnames, PasswordHash, PasswordHashAlgorithm,
              AccountStatus, FailedLoginCount, LockedUntilUtc
         FROM dbo.Users WHERE EmailNormalized = @email`,
      { email },
    );
    const u = recordset[0];
    const ahora = Date.now();

    if (u?.LockedUntilUtc && u.LockedUntilUtc.getTime() > ahora) {
      throw new HttpError(423, 'CUENTA_BLOQUEADA');
    }
    const usable = u && u.AccountStatus === 'ACTIVE' && u.PasswordHash && u.PasswordHashAlgorithm === 'BCRYPT';
    const ok = await bcrypt.compare(clave, usable ? u.PasswordHash : HASH_SEÑUELO);

    if (!usable || !ok) {
      if (u) {
        // Si un bloqueo anterior ya venció, el contador vuelve a empezar.
        const previos = u.LockedUntilUtc ? 0 : u.FailedLoginCount;
        const intentos = previos + 1;
        const bloquear = intentos >= config.maxFailedLogins;
        await db.query(
          `UPDATE dbo.Users
              SET FailedLoginCount = @intentos,
                  LockedUntilUtc = CASE WHEN @bloquear = 1 THEN DATEADD(MINUTE, @min, SYSUTCDATETIME()) ELSE NULL END,
                  UpdatedAtUtc = SYSUTCDATETIME()
            WHERE UserId = @id;
           INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, IpAddress, MetadataJson)
           VALUES (@id, 'LOGIN', 'USER', @id, @ip, @meta);`,
          {
            intentos, bloquear, min: config.lockMinutes, id: u.UserId, ip: (req.ip ?? '').slice(0, 45),
            meta: JSON.stringify({ ok: false, intentos, bloqueada: bloquear }),
          },
        );
        if (bloquear) throw new HttpError(423, 'CUENTA_BLOQUEADA');
      }
      throw new HttpError(401, 'CREDENCIALES_INVALIDAS');
    }

    const token = tokenAleatorio();
    const r2 = await db.query(
      `UPDATE dbo.Users SET FailedLoginCount = 0, LockedUntilUtc = NULL, LastLoginAtUtc = SYSUTCDATETIME()
        WHERE UserId = @id;
       INSERT dbo.UserSessions(UserId, RefreshTokenHash, ExpiresAtUtc)
       VALUES (@id, @h, DATEADD(HOUR, @horas, SYSUTCDATETIME()));
       INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, IpAddress, MetadataJson)
       VALUES (@id, 'LOGIN', 'USER', @id, @ip, N'{"ok":true}');
       SELECT r.RoleCode FROM dbo.UserRoles ur JOIN dbo.Roles r ON r.RoleId = ur.RoleId AND r.IsActive = 1
        WHERE ur.UserId = @id;`,
      { id: u.UserId, h: sha256(token), horas: config.sessionHours, ip: (req.ip ?? '').slice(0, 45) },
    );
    const rol = rolPrincipal(r2.recordset.map((x) => x.RoleCode));
    if (!rol) {
      log.warn(`Usuario ${u.Email} inició sesión pero no tiene rol asignado.`);
      throw new HttpError(403, 'SIN_ROL');
    }
    res.json({
      token,
      rol,
      correo: u.Email.toLowerCase(),
      nombre: nombreUsuario(u) || u.Email,
      expiraEn: ahora + config.sessionHours * 3600_000,
    });
  });

  r.post('/logout', autenticar(db), async (req, res) => {
    await db.query(
      `UPDATE dbo.UserSessions SET RevokedAtUtc = SYSUTCDATETIME() WHERE UserSessionId = @s AND RevokedAtUtc IS NULL;
       INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId) VALUES (@u, 'LOGOUT', 'USER', @u);`,
      { s: req.user.sesionId, u: req.user.id },
    );
    res.status(204).end();
  });

  /** RF-21 / HU-22: responde 202 exista o no el correo. */
  r.post('/recuperar', async (req, res) => {
    let email;
    try { email = validarCorreo(req.body?.correo); } catch { throw badRequest('CORREO_INVALIDO'); }
    const { recordset } = await db.query(
      `SELECT TOP (1) UserId FROM dbo.Users WHERE EmailNormalized = @email AND AccountStatus IN ('ACTIVE','PENDING_ACTIVATION')`,
      { email },
    );
    if (recordset[0]) {
      const token = await crearTokenSeguridad(db, recordset[0].UserId, 'PASSWORD_RESET', 60);
      // Hasta integrar un proveedor de correo, solo se muestran tokens en desarrollo.
      // En producción nunca registrar credenciales de un solo uso.
      if (!config.production) log.info(`[recuperación] Token para ${email} (válido 60 min): ${token}`);
      else log.warn('Se solicitó recuperación, pero no hay un proveedor de correo configurado.');
    }
    res.status(202).json({ aceptada: true });
  });

  /** Usa un token de ACTIVATION o PASSWORD_RESET para fijar la contraseña. */
  r.post('/restablecer', async (req, res) => {
    const token = typeof req.body?.token === 'string' ? req.body.token.trim() : '';
    const clave = req.body?.clave;
    if (!politicaPassword(clave)) throw badRequest('POLITICA_PASSWORD');
    if (!token) throw badRequest('TOKEN_INVALIDO');
    const hash = await hashPassword(clave);
    const r2 = await db.tx(null, async (q) => {
      const t = await q(
        `UPDATE dbo.UserSecurityTokens SET ConsumedAtUtc = SYSUTCDATETIME()
         OUTPUT inserted.UserId
          WHERE TokenHash = @h AND ConsumedAtUtc IS NULL AND ExpiresAtUtc > SYSUTCDATETIME();`,
        { h: sha256(token) },
      );
      const userId = t.recordset?.[0]?.UserId;
      if (!userId) return null;
      await q(
        `UPDATE dbo.Users
            SET PasswordHash = @hash, PasswordHashAlgorithm = 'BCRYPT', FailedLoginCount = 0, LockedUntilUtc = NULL,
                AccountStatus = CASE WHEN AccountStatus = 'PENDING_ACTIVATION' THEN 'ACTIVE' ELSE AccountStatus END,
                EmailConfirmedAtUtc = COALESCE(EmailConfirmedAtUtc, SYSUTCDATETIME()), UpdatedAtUtc = SYSUTCDATETIME()
          WHERE UserId = @u;
         UPDATE dbo.UserSessions SET RevokedAtUtc = SYSUTCDATETIME() WHERE UserId = @u AND RevokedAtUtc IS NULL;
         INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, MetadataJson)
         VALUES (@u, 'UPDATE', 'USER', @u, N'{"campo":"password"}');`,
        { hash, u: userId },
      );
      return userId;
    });
    if (!r2) throw badRequest('TOKEN_INVALIDO');
    res.status(204).end();
  });

  return r;
}

/** Crea un token de un solo uso; en la BD solo queda su SHA-256. Devuelve el token en claro. */
export async function crearTokenSeguridad(db, userId, proposito, minutos) {
  const token = tokenAleatorio();
  await db.query(
    `INSERT dbo.UserSecurityTokens(UserId, PurposeCode, TokenHash, ExpiresAtUtc)
     VALUES (@u, @p, @h, DATEADD(MINUTE, @m, SYSUTCDATETIME()))`,
    { u: userId, p: proposito, h: sha256(token), m: minutos },
  );
  return token;
}
