/*
  SIRA — migración 002: soporte para la API.
  Ejecutar DESPUÉS de 001_schema.sql, con la misma cuenta de desarrollo/admin.
  Es idempotente: se puede volver a ejecutar sin romper nada.

  Qué agrega:
    1. Secuencia para generar folios legibles (INC-2026-000001).
    2. TR_Incidents_Audit guarda en AuditEvents.MetadataJson una "foto" de la
       incidencia (alumno, tipo, gravedad, fecha y resumen de cambios). Así la
       bitácora sigue mostrando los datos aunque la incidencia se elimine.
    3. usp_DeleteIncident guarda la misma foto antes de borrar.
    4. Rol de base de datos sira_api con los permisos mínimos para el servidor.
*/
USE SIRA;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* Corrección del 001: BloodType era VARCHAR(3) pero el CHECK admite 'UNKNOWN' (7 caracteres),
   así que guardar "No identificado" fallaba con "String or binary data would be truncated". */
IF COL_LENGTH(N'dbo.MedicalRecords', N'BloodType') < 7
BEGIN
    ALTER TABLE dbo.MedicalRecords DROP CONSTRAINT CK_MedicalRecords_BloodType;
    ALTER TABLE dbo.MedicalRecords ALTER COLUMN BloodType VARCHAR(7) NULL;
    ALTER TABLE dbo.MedicalRecords ADD CONSTRAINT CK_MedicalRecords_BloodType
        CHECK (BloodType IS NULL OR BloodType IN ('A+','A-','B+','B-','AB+','AB-','O+','O-','UNKNOWN'));
END;
GO

IF OBJECT_ID(N'dbo.IncidentFolioSeq', N'SO') IS NULL
    CREATE SEQUENCE dbo.IncidentFolioSeq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
GO

CREATE OR ALTER TRIGGER dbo.TR_Incidents_Audit
ON dbo.Incidents
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF NOT EXISTS (SELECT 1 FROM inserted) RETURN;

    DECLARE @Actor UNIQUEIDENTIFIER = TRY_CONVERT(UNIQUEIDENTIFIER, SESSION_CONTEXT(N'ActorUserId'));

    INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, OccurredAtUtc, MetadataJson)
    SELECT
        @Actor,
        CASE WHEN d.IncidentId IS NULL THEN 'CREATE' ELSE 'UPDATE' END,
        'INCIDENT', i.IncidentId, i.Folio, SYSUTCDATETIME(),
        (
            SELECT
                CONVERT(VARCHAR(36), s.StudentId) AS studentId,
                s.EnrollmentCode AS matricula,
                CONCAT_WS(N' ', s.FirstSurname, s.SecondSurname, s.GivenNames) AS alumno,
                t.DisplayName AS tipo,
                sv.DisplayName AS gravedad,
                i.OccurredAt AS fechaIncidente,
                CASE
                    WHEN d.IncidentId IS NULL THEN CONCAT(N'Tipo: ', t.DisplayName, N' · Gravedad: ', sv.DisplayName)
                    ELSE COALESCE(NULLIF(CONCAT_WS(N' · ',
                        CASE WHEN d.IncidentTypeCode <> i.IncidentTypeCode THEN CONCAT(N'Tipo: ', dt.DisplayName, N' → ', t.DisplayName) END,
                        CASE WHEN d.SeverityCode <> i.SeverityCode THEN CONCAT(N'Gravedad: ', dsv.DisplayName, N' → ', sv.DisplayName) END,
                        CASE WHEN d.Description <> i.Description THEN N'Descripción actualizada' END,
                        CASE WHEN d.StatusCode <> i.StatusCode THEN CONCAT(N'Estado: ', d.StatusCode, N' → ', i.StatusCode) END
                    ), N''), N'Se guardó la edición sin cambios en los campos.')
                END AS resumen
            FOR JSON PATH, WITHOUT_ARRAY_WRAPPER
        )
    FROM inserted AS i
    INNER JOIN dbo.Students AS s ON s.StudentId = i.StudentId
    INNER JOIN dbo.IncidentTypes AS t ON t.IncidentTypeCode = i.IncidentTypeCode
    INNER JOIN dbo.IncidentSeverities AS sv ON sv.SeverityCode = i.SeverityCode
    LEFT JOIN deleted AS d ON d.IncidentId = i.IncidentId
    LEFT JOIN dbo.IncidentTypes AS dt ON dt.IncidentTypeCode = d.IncidentTypeCode
    LEFT JOIN dbo.IncidentSeverities AS dsv ON dsv.SeverityCode = d.SeverityCode;

    INSERT dbo.IncidentStatusHistory(IncidentId, PreviousStatusCode, NewStatusCode, ChangedByUserId, ChangedAtUtc)
    SELECT i.IncidentId, NULL, i.StatusCode, @Actor, SYSUTCDATETIME()
    FROM inserted AS i
    LEFT JOIN deleted AS d ON d.IncidentId = i.IncidentId
    WHERE d.IncidentId IS NULL;

    INSERT dbo.IncidentStatusHistory(IncidentId, PreviousStatusCode, NewStatusCode, ChangedByUserId, ChangedAtUtc)
    SELECT i.IncidentId, d.StatusCode, i.StatusCode, @Actor, SYSUTCDATETIME()
    FROM inserted AS i
    INNER JOIN deleted AS d ON d.IncidentId = i.IncidentId
    WHERE d.StatusCode <> i.StatusCode;
END;
GO

CREATE OR ALTER PROCEDURE dbo.usp_DeleteIncident
    @IncidentId UNIQUEIDENTIFIER,
    @ActorUserId UNIQUEIDENTIFIER,
    @Reason NVARCHAR(1000)
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    IF @Reason IS NULL OR LEN(LTRIM(RTRIM(@Reason))) = 0
        THROW 51011, 'El motivo de eliminación es obligatorio.', 1;

    IF NOT EXISTS
    (
        SELECT 1
        FROM dbo.UserRoles ur
        INNER JOIN dbo.Roles r ON r.RoleId = ur.RoleId
        INNER JOIN dbo.RolePermissions rp ON rp.RoleId = r.RoleId
        INNER JOIN dbo.Permissions p ON p.PermissionId = rp.PermissionId
        INNER JOIN dbo.Users u ON u.UserId = ur.UserId
        WHERE ur.UserId = @ActorUserId
          AND r.RoleCode = 'ADMIN'
          AND p.PermissionCode = 'incidents.delete'
          AND u.AccountStatus = 'ACTIVE'
    )
        THROW 51012, 'Solo un administrador activo con permiso puede eliminar incidencias.', 1;

    DECLARE @Motivo NVARCHAR(1000) = LTRIM(RTRIM(@Reason));
    DECLARE @Folio VARCHAR(32), @Meta NVARCHAR(2000);

    SELECT
        @Folio = i.Folio,
        @Meta =
        (
            SELECT
                CONVERT(VARCHAR(36), s.StudentId) AS studentId,
                s.EnrollmentCode AS matricula,
                CONCAT_WS(N' ', s.FirstSurname, s.SecondSurname, s.GivenNames) AS alumno,
                t.DisplayName AS tipo,
                sv.DisplayName AS gravedad,
                i.OccurredAt AS fechaIncidente,
                CONCAT(N'Incidencia eliminada. Motivo: ', LEFT(@Motivo, 600)) AS resumen,
                LEFT(@Motivo, 600) AS reason
            FOR JSON PATH, WITHOUT_ARRAY_WRAPPER
        )
    FROM dbo.Incidents i
    INNER JOIN dbo.Students s ON s.StudentId = i.StudentId
    INNER JOIN dbo.IncidentTypes t ON t.IncidentTypeCode = i.IncidentTypeCode
    INNER JOIN dbo.IncidentSeverities sv ON sv.SeverityCode = i.SeverityCode
    WHERE i.IncidentId = @IncidentId;

    IF @Folio IS NULL
        THROW 51013, 'La incidencia no existe o ya fue eliminada.', 1;

    BEGIN TRY
        BEGIN TRANSACTION;

        INSERT dbo.IncidentDeletionAudit(DeletedIncidentId, DeletedFolio, DeletedByUserId, Reason)
        VALUES (@IncidentId, @Folio, @ActorUserId, @Motivo);

        INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, MetadataJson)
        VALUES (@ActorUserId, 'DELETE', 'INCIDENT', @IncidentId, @Folio, @Meta);

        DELETE dbo.Incidents WHERE IncidentId = @IncidentId;
        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH;
END;
GO

/* Rol con lo mínimo que necesita la API. La bitácora queda append-only también por permisos. */
IF DATABASE_PRINCIPAL_ID(N'sira_api') IS NULL
    CREATE ROLE sira_api;
GO
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON SCHEMA::dbo TO sira_api;
DENY UPDATE, DELETE ON dbo.AuditEvents TO sira_api;
DENY UPDATE, DELETE ON dbo.IncidentDeletionAudit TO sira_api;
DENY INSERT, UPDATE, DELETE ON dbo.SchemaMigrations TO sira_api;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.SchemaMigrations WHERE Version = 2)
    INSERT dbo.SchemaMigrations(Version, Description)
    VALUES (2, N'Soporte API: folios, bitácora con snapshot, rol sira_api');
GO
