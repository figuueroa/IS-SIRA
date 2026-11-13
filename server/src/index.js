import { loadConfig } from './config.js';
import { createDb } from './db.js';
import { createApp } from './app.js';

const config = loadConfig();
if (!config.db.user || !config.db.password) {
  console.error('Faltan DB_USER / DB_PASSWORD. Copia .env.example como .env y complétalo.');
  process.exit(1);
}

const db = createDb(config.db);
const app = createApp({ db, config });

// 0.0.0.0 para que el emulador (10.0.2.2) o un teléfono en la misma red puedan llegar.
const server = app.listen(config.port, '0.0.0.0', async () => {
  console.log(`SIRA API escuchando en http://0.0.0.0:${config.port}/api`);
  try {
    await db.ping();
    console.log(`Conectado a SQL Server ${config.db.server}${config.db.options.instanceName ? `\\${config.db.options.instanceName}` : `:${config.db.port}`} / ${config.db.database}`);
  } catch (err) {
    console.error(`No se pudo conectar a SQL Server: ${err.message}`);
    console.error('Revisa .env, que TCP/IP esté habilitado y que el login exista (sql/003_login_api.sql).');
  }
});

const cerrar = () => server.close(() => db.close().finally(() => process.exit(0)));
process.on('SIGINT', cerrar);
process.on('SIGTERM', cerrar);
