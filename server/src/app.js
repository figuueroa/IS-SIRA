import express from 'express';
import { HttpError } from './util.js';
import { autenticar, rutasAuth } from './auth.js';
import { rutasAlumnosIncidencias } from './rutas/incidencias.js';
import { rutasSalud } from './rutas/salud.js';
import { rutasAdmin } from './rutas/admin.js';

/** Arma la API. `db` se inyecta para poder probar sin SQL Server. */
export function createApp({ db, config, log = console }) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 'loopback');
  app.use(express.json({ limit: '64kb' }));
  app.use((_req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });

  app.get('/api/salud', async (_req, res) => {
    try {
      await db.ping();
      res.json({ ok: true, baseDeDatos: true });
    } catch (err) {
      log.error(`Sin conexión a SQL Server: ${err.message}`);
      res.status(503).json({ ok: false, baseDeDatos: false });
    }
  });

  app.use('/api/auth', rutasAuth({ db, config, log }));

  // Todo lo demás exige sesión.
  app.use('/api', autenticar(db));
  app.get('/api/yo', (req, res) => {
    const { correo, nombre, rol, roles } = req.user;
    res.json({ correo, nombre, rol, roles, permisos: [...req.user.permisos].sort() });
  });
  app.use('/api', rutasAlumnosIncidencias({ db }));
  app.use('/api', rutasSalud({ db }));
  app.use('/api', rutasAdmin({ db, config, log }));

  app.use('/api', (_req, _res, next) => next(new HttpError(404, 'RUTA_NO_ENCONTRADA')));

  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, _next) => {
    if (err instanceof HttpError) {
      return res.status(err.status).json({ error: err.code, mensaje: err.message !== err.code ? err.message : undefined });
    }
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'JSON_INVALIDO' });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'DEMASIADO_GRANDE' });
    // Errores de SQL: se registran completos en el servidor, al cliente solo un código.
    log.error(`[${req.method} ${req.originalUrl}] ${err.number ? `SQL ${err.number}: ` : ''}${err.message}`);
    res.status(500).json({ error: 'ERROR_INTERNO' });
  });

  return app;
}
