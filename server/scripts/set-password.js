/*
 * Fija la contraseña de una cuenta y la activa (útil en desarrollo, mientras no se envían correos).
 * Uso: npm run set-password -- correo@dominio.mx "NuevaClave#2026"
 */
import { loadConfig } from '../src/config.js';
import { createDb } from '../src/db.js';
import { hashPassword } from '../src/auth.js';
import { politicaPassword } from '../src/util.js';

const [correo, clave] = process.argv.slice(2);
if (!correo || !clave) {
  console.error('Uso: npm run set-password -- correo@dominio.mx "NuevaClave#2026"');
  process.exit(1);
}
if (!politicaPassword(clave)) {
  console.error('La contraseña debe tener 8+ caracteres, mayúscula, minúscula, número y símbolo.');
  process.exit(1);
}

const db = createDb(loadConfig().db);
try {
  const r = await db.query(
    `UPDATE dbo.Users
        SET PasswordHash = @h, PasswordHashAlgorithm = 'BCRYPT', FailedLoginCount = 0, LockedUntilUtc = NULL,
            AccountStatus = CASE WHEN AccountStatus = 'PENDING_ACTIVATION' THEN 'ACTIVE' ELSE AccountStatus END,
            UpdatedAtUtc = SYSUTCDATETIME()
      WHERE EmailNormalized = @e`,
    { e: correo.trim().toLowerCase(), h: await hashPassword(clave) },
  );
  console.log(r.rowsAffected[0] ? `Contraseña actualizada para ${correo}.` : `No existe la cuenta ${correo}.`);
} finally {
  await db.close();
}
