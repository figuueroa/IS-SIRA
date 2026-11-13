# IS-SIRA

Sistema de Incidencias y Respuestas Auxiliares.

- `app/` — app Android (Kotlin + Jetpack Compose).
- `server/` — API en Node.js que conecta la app con SQL Server. **Instrucciones de instalación: [server/README.md](server/README.md).**
- `database/` — esquema central SQL Server y guía de sus tablas.
- `server/sql/` — migraciones posteriores y creación del login de la API. Ejecutar `database/001_schema.sql` y después `server/sql/002_api_soporte.sql` y `server/sql/003_login_api.sql`.

Para levantar SQL Server en desarrollo, consulta `database/README.md`. Para conectar la app, configura `server/.env` y sigue `server/README.md`.

Para usar la app sin servidor (demo en memoria), pon `SIRA_USAR_SERVIDOR=false` en `gradle.properties`.
