export function loadConfig(env = process.env) {
  const instanceName = env.DB_INSTANCE?.trim() || undefined;
  return {
    port: Number(env.PORT || 8080),
    production: env.NODE_ENV === 'production',
    sessionHours: Number(env.SESSION_HOURS || 12),
    maxFailedLogins: Number(env.MAX_FAILED_LOGINS || 3),
    lockMinutes: Number(env.LOCK_MINUTES || 30),
    backupDir: env.BACKUP_DIR?.trim() || null,
    db: {
      server: env.DB_SERVER || 'localhost',
      // Con instancia con nombre, el puerto lo resuelve SQL Server Browser.
      port: instanceName ? undefined : Number(env.DB_PORT || 1433),
      database: env.DB_NAME || 'SIRA',
      user: env.DB_USER,
      password: env.DB_PASSWORD,
      options: {
        encrypt: env.DB_ENCRYPT !== 'false',
        trustServerCertificate: env.DB_TRUST_SERVER_CERTIFICATE === 'true',
        instanceName,
        useUTC: true,
      },
      // 2 min: BACKUP DATABASE puede tardar más que los 15 s por defecto.
      requestTimeout: 120000,
      pool: { max: 10, min: 0, idleTimeoutMillis: 30000 },
    },
  };
}
