/*
 * Datos de prueba para DESARROLLO: alumnos y las cuentas demo que autocompleta la app en debug.
 * Uso: npm run seed:dev   (se puede repetir; no duplica nada)
 * Las contraseñas se guardan con bcrypt; nunca en texto plano.
 * Necesita una cuenta con permiso de escritura (la de sira_api sirve).
 */
import { loadConfig } from '../src/config.js';
import { createDb } from '../src/db.js';
import { hashPassword } from '../src/auth.js';

const config = loadConfig();
if (config.production) {
  console.error('seed:dev no se ejecuta con NODE_ENV=production.');
  process.exit(1);
}
const clave = process.env.SEED_DEMO_PASSWORD;
if (!clave) {
  console.error('Define SEED_DEMO_PASSWORD en .env (igual a DEMO_LOGIN_PASSWORD de la app).');
  process.exit(1);
}

const alumnos = [
  ['19071726', 'Jesus Rogelio', 'Bañuelos', 'Martinez', '3°', 'A'],
  ['19071801', 'Ana Sofia', 'Ramirez', 'Lopez', '3°', 'A'],
  ['19071845', 'Carlos Daniel', 'Hernandez', 'Garcia', '2°', 'B'],
  ['19071902', 'Maria Fernanda', 'Torres', 'Mendoza', '1°', 'C'],
];

const cuentas = [
  ['demo@sira.app', 'Dirección', 'Demo', 'DIRECTOR'],
  ['admin@sira.app', 'Administración', 'Demo', 'ADMIN'],
  ['enfermeria@sira.app', 'Enfermería', 'Demo', 'NURSE'],
  ['tutor@sira.app', 'Tutor', 'Demo', 'GUARDIAN'],
];

const db = createDb(config.db);
try {
  for (const [m, nombres, ap1, ap2, grado, grupo] of alumnos) {
    await db.query(
      `IF NOT EXISTS (SELECT 1 FROM dbo.Students WHERE EnrollmentCode = @m)
         INSERT dbo.Students(EnrollmentCode, GivenNames, FirstSurname, SecondSurname, GradeLevel, GroupCode)
         VALUES (@m, @n, @a1, @a2, @g, @gr)`,
      { m, n: nombres, a1: ap1, a2: ap2, g: grado, gr: grupo },
    );
  }

  const hash = await hashPassword(clave);
  for (const [email, nombres, apellidos, rol] of cuentas) {
    await db.query(
      `IF NOT EXISTS (SELECT 1 FROM dbo.Users WHERE EmailNormalized = @e)
         INSERT dbo.Users(Email, GivenNames, Surnames, Phone, PasswordHash, PasswordHashAlgorithm, AccountStatus, EmailConfirmedAtUtc)
         VALUES (@e, @n, @a, N'3312345678', @h, 'BCRYPT', 'ACTIVE', SYSUTCDATETIME())
       ELSE
         UPDATE dbo.Users SET PasswordHash = @h, PasswordHashAlgorithm = 'BCRYPT', AccountStatus = 'ACTIVE',
                FailedLoginCount = 0, LockedUntilUtc = NULL
          WHERE EmailNormalized = @e;
       INSERT dbo.UserRoles(UserId, RoleId)
       SELECT u.UserId, r.RoleId FROM dbo.Users u CROSS JOIN dbo.Roles r
        WHERE u.EmailNormalized = @e AND r.RoleCode = @rol
          AND NOT EXISTS (SELECT 1 FROM dbo.UserRoles x WHERE x.UserId = u.UserId AND x.RoleId = r.RoleId);`,
      { e: email, n: nombres, a: apellidos, h: hash, rol },
    );
  }

  // tutor@sira.app es tutor del primer alumno, para probar "Mis hijos e incidencias".
  await db.query(
    `INSERT dbo.StudentGuardians(StudentId, GuardianUserId, RelationshipCode, IsPrimaryContact)
     SELECT s.StudentId, u.UserId, 'OTHER', 1
       FROM dbo.Students s CROSS JOIN dbo.Users u
      WHERE s.EnrollmentCode = '19071726' AND u.EmailNormalized = 'tutor@sira.app'
        AND NOT EXISTS (SELECT 1 FROM dbo.StudentGuardians g
                         WHERE g.StudentId = s.StudentId AND g.GuardianUserId = u.UserId AND g.IsActive = 1)`,
  );

  console.log(`Listo: ${alumnos.length} alumnos y ${cuentas.length} cuentas demo (contraseña = SEED_DEMO_PASSWORD).`);
} catch (err) {
  console.error(`Falló el seed: ${err.number ? `SQL ${err.number}: ` : ''}${err.message}`);
  process.exitCode = 1;
} finally {
  await db.close();
}
