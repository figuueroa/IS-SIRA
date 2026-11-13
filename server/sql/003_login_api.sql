/*
  SIRA — 003: cuenta con la que el servidor se conecta a SQL Server.
  ANTES DE EJECUTAR: cambia la contraseña de abajo y ponla también en server/.env (DB_PASSWORD).
  Requiere que SQL Server tenga activada la autenticación "SQL Server y Windows" (modo mixto).
  No subas este archivo a git con la contraseña real.
*/
USE master;
GO
IF SUSER_ID(N'sira_api_login') IS NULL
    CREATE LOGIN sira_api_login WITH PASSWORD = N'CAMBIA-ESTA-CLAVE-2026!', CHECK_POLICY = ON, DEFAULT_DATABASE = SIRA;
GO

USE SIRA;
GO
IF DATABASE_PRINCIPAL_ID(N'sira_api_user') IS NULL
    CREATE USER sira_api_user FOR LOGIN sira_api_login;
GO
ALTER ROLE sira_api ADD MEMBER sira_api_user;
-- Necesario solo para el botón "Generar respaldo" (BACKUP DATABASE).
ALTER ROLE db_backupoperator ADD MEMBER sira_api_user;
GO
