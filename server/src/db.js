import sql from 'mssql';

export { sql };

/**
 * Acceso a SQL Server.
 *  - query(text, params): consulta suelta.
 *  - tx(actorUserId, fn): transacción en UNA conexión; antes de llamar a fn fija
 *    SESSION_CONTEXT('ActorUserId') para que TR_Incidents_Audit sepa quién hizo el cambio.
 *
 * Los parámetros se pasan siempre como @nombre (nunca concatenados en el texto):
 *   query('SELECT ... WHERE Email = @correo', { correo })
 * Para fijar el tipo: { fecha: { type: sql.DateTimeOffset, value: new Date() } }
 */
export function createDb(config) {
  const pool = new sql.ConnectionPool(config);
  let ready = null;
  const connect = () => {
    ready ??= pool.connect().catch((err) => { ready = null; throw err; });
    return ready;
  };

  async function run(executor, text, params = {}) {
    const request = executor.request();
    for (const [name, value] of Object.entries(params)) {
      if (value && typeof value === 'object' && 'type' in value && 'value' in value) {
        request.input(name, value.type, value.value);
      } else {
        request.input(name, value);
      }
    }
    return request.query(text);
  }

  return {
    async query(text, params) {
      await connect();
      return run(pool, text, params);
    },

    async tx(actorUserId, fn) {
      await connect();
      const transaction = new sql.Transaction(pool);
      await transaction.begin();
      const q = (text, params) => run(transaction, text, params);
      try {
        await q(
          `DECLARE @a UNIQUEIDENTIFIER = TRY_CONVERT(UNIQUEIDENTIFIER, @actor);
           EXEC sys.sp_set_session_context @key = N'ActorUserId', @value = @a;`,
          { actor: actorUserId ?? null },
        );
        const result = await fn(q);
        await q(`EXEC sys.sp_set_session_context @key = N'ActorUserId', @value = NULL;`);
        await transaction.commit();
        return result;
      } catch (err) {
        try { await transaction.rollback(); } catch { /* la transacción ya terminó */ }
        throw err;
      }
    },

    async ping() {
      await connect();
      await pool.request().query('SELECT 1 AS ok');
      return true;
    },

    close: () => pool.close(),
  };
}
