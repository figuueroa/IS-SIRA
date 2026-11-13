# Servidor SIRA (API) — conexión app ↔ SQL Server

```
App Android ──HTTP/JSON──▶ servidor Node (esta carpeta) ──SQL──▶ SQL Server 2022 (base SIRA)
```

La app **no** se conecta directo a SQL Server: si lo hiciera, la contraseña de la base iría dentro del APK
y cualquiera podría leer expedientes médicos. El servidor valida la sesión y los permisos de `dbo.RolePermissions`
en cada petición.

## 1. Preparar SQL Server (una sola vez)

1. **Habilitar TCP/IP**: *SQL Server Configuration Manager* → *Protocolos de red de SQL Server* → *TCP/IP* → Habilitar.
   En *Direcciones IP* → *IPAll* pon `TCP Port = 1433`. Reinicia el servicio de SQL Server.
2. **Autenticación mixta**: en SSMS, clic derecho al servidor → *Propiedades* → *Seguridad* →
   "Modo de autenticación de SQL Server y Windows". Reinicia el servicio.
3. **Ejecutar los scripts en orden** (SSMS o `sqlcmd`), con tu cuenta de administrador:
   | Script | Qué hace |
   |---|---|
   | `sql/001_schema.sql` | El esquema original. Solo en una base **nueva** (sus `CREATE TABLE` no se pueden repetir). |
   | `sql/002_api_soporte.sql` | Folios, bitácora con datos de la incidencia, corrige `BloodType`, crea el rol `sira_api`. Se puede repetir. |
   | `sql/003_login_api.sql` | Crea el login `sira_api_login`. **Cambia la contraseña antes de ejecutarlo.** |

   Con `sqlcmd`:
   ```
   sqlcmd -S localhost -E -i sql\001_schema.sql
   sqlcmd -S localhost -E -i sql\002_api_soporte.sql
   sqlcmd -S localhost -E -i sql\003_login_api.sql
   ```

## 2. Arrancar el servidor

Requiere **Node.js 22 o más nuevo** (https://nodejs.org).

```
cd server
copy .env.example .env        (en macOS/Linux: cp .env.example .env)
```
Abre `.env` y pon en `DB_PASSWORD` la misma contraseña del paso 003. Si usas **SQL Server Express**
(`localhost\SQLEXPRESS`), deja `DB_PORT=` vacío y pon `DB_INSTANCE=SQLEXPRESS`.

```
npm install
npm run seed:dev     # alumnos de prueba + cuentas demo (solo desarrollo)
npm start
```

Comprueba en el navegador: http://localhost:8080/api/salud → `{"ok":true,"baseDeDatos":true}`.

### Cuentas demo (las autocompleta la app en debug)

| Correo | Rol |
|---|---|
| demo@sira.app | Director |
| admin@sira.app | Administrador |
| enfermeria@sira.app | Enfermería |
| tutor@sira.app | Tutor (asociado al alumno 19071726) |

Contraseña de todas: `SiraDemo2026!` (`SEED_DEMO_PASSWORD` en `.env`; debe coincidir con `DEMO_LOGIN_PASSWORD` en `app/build.gradle.kts`).

## 3. Correr la app

- **Emulador**: no cambies nada. La app usa `http://10.0.2.2:8080/api/`, que en el emulador significa "mi computadora".
- **Teléfono físico** (misma red Wi-Fi que la PC):
  1. Averigua la IP de tu PC (`ipconfig` en Windows), p. ej. `192.168.1.50`.
  2. En `gradle.properties` agrega `SIRA_API_URL=http://192.168.1.50:8080/api/`.
  3. Agrega esa IP en `app/src/debug/res/xml/network_security_config.xml`.
  4. Permite el puerto 8080 en el Firewall de Windows.
- **Sin servidor** (demo en memoria como antes): en `gradle.properties` pon `SIRA_USAR_SERVIDOR=false`.

## Cuentas nuevas y contraseñas

Cuando el administrador registra un usuario o un tutor, la cuenta queda `PENDING_ACTIVATION`. Como todavía no se
envían correos, el servidor **imprime en su consola** el token de activación; se usa con
`POST /api/auth/restablecer {"token": "...", "clave": "..."}`. Para desarrollo es más rápido:

```
npm run set-password -- correo@dominio.mx "NuevaClave#2026"
```

Lo mismo con "¿Olvidó su contraseña?": el token de restablecimiento sale en la consola del servidor.

## Problemas comunes

| Síntoma | Causa probable |
|---|---|
| La app dice "No se pudo conectar con el servidor" | El servidor no está corriendo, URL equivocada, o el firewall bloquea el 8080. |
| `/api/salud` responde `baseDeDatos:false` | Revisa la consola: TCP/IP deshabilitado, puerto, `DB_INSTANCE`, o usuario/contraseña. |
| `Login failed for user 'sira_api_login'` | No se activó el modo mixto, o la contraseña de `.env` no coincide con la del 003. |
| `self signed certificate` | Pon `DB_TRUST_SERVER_CERTIFICATE=true` en `.env` (solo en local). |
| "Generar respaldo" falla | `BACKUP_DIR` no existe en la máquina de SQL Server o el servicio de SQL Server no puede escribir ahí. |
| El administrador no puede editar incidencias | Correcto según la matriz del 001: ADMIN no tiene `incidents.update_all`. |

## Endpoints

Todos bajo `/api`, con `Authorization: Bearer <token>` salvo `salud` y `auth/*`.

| Método y ruta | Permiso | Tabla(s) |
|---|---|---|
| `POST auth/login`, `auth/logout`, `auth/recuperar`, `auth/restablecer` | — | Users, UserSessions, UserSecurityTokens |
| `GET alumnos`, `GET alumnos/buscar?q=` | students.read (tutor: solo sus hijos) | Students |
| `GET/POST incidencias`, `PUT incidencias/:id` | incidents.* | Incidents (+ trigger de auditoría) |
| `POST incidencias/:id/eliminar` `{motivo}` | incidents.delete | `usp_DeleteIncident` |
| `GET incidencias/version` | lectura de incidencias | AuditEvents |
| `GET bitacora` | audit.read o notifications.read | AuditEvents |
| `GET/POST expedientes`, `PUT expedientes/:matricula` | medical.read / medical.write | MedicalRecords, MedicalRecordItems, EmergencyContacts |
| `GET/POST alumnos/:matricula/ingresos` | medical.read / medical_visits.create | MedicalVisits |
| `GET/POST usuarios`, `PUT usuarios/rol` | users.manage / roles.manage | Users, UserRoles |
| `GET/POST tutores` | associations.manage (tutor: los suyos) | StudentGuardians |
| `GET/POST respaldos` | backups.manage | BackupRuns + `BACKUP DATABASE` |

Pruebas: `npm test` (usan una base simulada; no necesitan SQL Server).

## Pendiente antes de producción

- Subir la foto de la incidencia (`IncidentAttachments`); hoy la app la toma pero no se envía.
- Enviar correos de activación/recuperación (hoy salen en la consola).
- HTTPS (detrás de IIS/nginx o un servicio con certificado) y límite de intentos por IP.
- Los expedientes que se guardaron antes en el teléfono (Room cifrado) no se migran solos al servidor.
