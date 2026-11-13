/*
  SIRA — esquema relacional inicial para SQL Server 2022.
  Ejecutar con una cuenta de desarrollo/admin en una base nueva.
  No almacenar contraseñas en texto plano ni usar SHA-256 simple para passwords.
*/
IF DB_ID(N'SIRA') IS NULL
BEGIN
    CREATE DATABASE SIRA;
END;
GO

USE SIRA;
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.SchemaMigrations', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SchemaMigrations
    (
        Version       INT            NOT NULL CONSTRAINT PK_SchemaMigrations PRIMARY KEY,
        Description   NVARCHAR(200)  NOT NULL,
        AppliedAtUtc  DATETIME2(0)   NOT NULL CONSTRAINT DF_SchemaMigrations_AppliedAtUtc DEFAULT SYSUTCDATETIME()
    );
END;
GO

CREATE TABLE dbo.Roles
(
    RoleId       SMALLINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Roles PRIMARY KEY,
    RoleCode     VARCHAR(32) NOT NULL,
    DisplayName  NVARCHAR(80) NOT NULL,
    IsSystem     BIT NOT NULL CONSTRAINT DF_Roles_IsSystem DEFAULT (1),
    IsActive     BIT NOT NULL CONSTRAINT DF_Roles_IsActive DEFAULT (1),
    CreatedAtUtc DATETIME2(0) NOT NULL CONSTRAINT DF_Roles_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_Roles_RoleCode UNIQUE (RoleCode)
);
GO

CREATE TABLE dbo.Permissions
(
    PermissionId   SMALLINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Permissions PRIMARY KEY,
    PermissionCode VARCHAR(80) NOT NULL,
    ModuleCode      VARCHAR(40) NOT NULL,
    ActionCode      VARCHAR(40) NOT NULL,
    Description     NVARCHAR(240) NOT NULL,
    CONSTRAINT UQ_Permissions_PermissionCode UNIQUE (PermissionCode)
);
GO

CREATE TABLE dbo.RolePermissions
(
    RoleId       SMALLINT NOT NULL,
    PermissionId SMALLINT NOT NULL,
    GrantedAtUtc DATETIME2(0) NOT NULL CONSTRAINT DF_RolePermissions_GrantedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_RolePermissions PRIMARY KEY (RoleId, PermissionId),
    CONSTRAINT FK_RolePermissions_Roles FOREIGN KEY (RoleId) REFERENCES dbo.Roles(RoleId),
    CONSTRAINT FK_RolePermissions_Permissions FOREIGN KEY (PermissionId) REFERENCES dbo.Permissions(PermissionId)
);
GO

CREATE TABLE dbo.Users
(
    UserId                UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_Users_UserId DEFAULT NEWSEQUENTIALID(),
    Email                 NVARCHAR(254) NOT NULL,
    EmailNormalized       AS LOWER(LTRIM(RTRIM(Email))) PERSISTED,
    GivenNames            NVARCHAR(120) NOT NULL,
    Surnames              NVARCHAR(160) NOT NULL,
    Phone                 NVARCHAR(32) NULL,
    Address               NVARCHAR(320) NULL,
    PasswordHash          NVARCHAR(512) NULL,
    PasswordHashAlgorithm VARCHAR(32) NULL,
    AccountStatus         VARCHAR(24) NOT NULL CONSTRAINT DF_Users_AccountStatus DEFAULT ('PENDING_ACTIVATION'),
    FailedLoginCount      SMALLINT NOT NULL CONSTRAINT DF_Users_FailedLoginCount DEFAULT (0),
    LockedUntilUtc        DATETIME2(0) NULL,
    EmailConfirmedAtUtc   DATETIME2(0) NULL,
    LastLoginAtUtc        DATETIME2(0) NULL,
    CreatedAtUtc          DATETIME2(0) NOT NULL CONSTRAINT DF_Users_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedAtUtc          DATETIME2(0) NOT NULL CONSTRAINT DF_Users_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    RowVersion            ROWVERSION NOT NULL,
    CONSTRAINT PK_Users PRIMARY KEY (UserId),
    CONSTRAINT CK_Users_AccountStatus CHECK (AccountStatus IN ('PENDING_ACTIVATION','ACTIVE','INACTIVE','SUSPENDED')),
    CONSTRAINT CK_Users_FailedLoginCount CHECK (FailedLoginCount >= 0),
    CONSTRAINT CK_Users_PasswordHashPair CHECK
    (
        (PasswordHash IS NULL AND PasswordHashAlgorithm IS NULL) OR
        (PasswordHash IS NOT NULL AND PasswordHashAlgorithm IS NOT NULL AND
         PasswordHashAlgorithm IN ('ARGON2ID','BCRYPT','PBKDF2_SHA256'))
    )
);
GO
CREATE UNIQUE INDEX UX_Users_EmailNormalized ON dbo.Users(EmailNormalized);
CREATE INDEX IX_Users_Status ON dbo.Users(AccountStatus, EmailNormalized);
GO

CREATE TABLE dbo.UserRoles
(
    UserId       UNIQUEIDENTIFIER NOT NULL,
    RoleId       SMALLINT NOT NULL,
    AssignedAtUtc DATETIME2(0) NOT NULL CONSTRAINT DF_UserRoles_AssignedAtUtc DEFAULT SYSUTCDATETIME(),
    AssignedByUserId UNIQUEIDENTIFIER NULL,
    CONSTRAINT PK_UserRoles PRIMARY KEY (UserId, RoleId),
    CONSTRAINT FK_UserRoles_Users FOREIGN KEY (UserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_UserRoles_Roles FOREIGN KEY (RoleId) REFERENCES dbo.Roles(RoleId),
    CONSTRAINT FK_UserRoles_AssignedBy FOREIGN KEY (AssignedByUserId) REFERENCES dbo.Users(UserId)
);
GO
CREATE INDEX IX_UserRoles_RoleId ON dbo.UserRoles(RoleId, UserId);
GO

CREATE TABLE dbo.UserSecurityTokens
(
    SecurityTokenId BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_UserSecurityTokens PRIMARY KEY,
    UserId          UNIQUEIDENTIFIER NOT NULL,
    PurposeCode     VARCHAR(24) NOT NULL,
    TokenHash       VARBINARY(32) NOT NULL,
    CreatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_UserSecurityTokens_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    ExpiresAtUtc    DATETIME2(0) NOT NULL,
    ConsumedAtUtc   DATETIME2(0) NULL,
    CONSTRAINT FK_UserSecurityTokens_Users FOREIGN KEY (UserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_UserSecurityTokens_Purpose CHECK (PurposeCode IN ('ACTIVATION','PASSWORD_RESET')),
    CONSTRAINT CK_UserSecurityTokens_Expiry CHECK (ExpiresAtUtc > CreatedAtUtc),
    CONSTRAINT UQ_UserSecurityTokens_TokenHash UNIQUE (TokenHash)
);
GO
CREATE INDEX IX_UserSecurityTokens_UserPurpose ON dbo.UserSecurityTokens(UserId, PurposeCode, ExpiresAtUtc)
    INCLUDE (ConsumedAtUtc);
GO

CREATE TABLE dbo.UserSessions
(
    UserSessionId  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_UserSessions PRIMARY KEY,
    UserId         UNIQUEIDENTIFIER NOT NULL,
    RefreshTokenHash VARBINARY(32) NOT NULL,
    CreatedAtUtc   DATETIME2(0) NOT NULL CONSTRAINT DF_UserSessions_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    ExpiresAtUtc   DATETIME2(0) NOT NULL,
    RevokedAtUtc   DATETIME2(0) NULL,
    LastUsedAtUtc  DATETIME2(0) NULL,
    CONSTRAINT FK_UserSessions_Users FOREIGN KEY (UserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT UQ_UserSessions_RefreshTokenHash UNIQUE (RefreshTokenHash),
    CONSTRAINT CK_UserSessions_Expiry CHECK (ExpiresAtUtc > CreatedAtUtc)
);
GO
CREATE INDEX IX_UserSessions_UserExpiry ON dbo.UserSessions(UserId, ExpiresAtUtc) INCLUDE (RevokedAtUtc);
GO

CREATE TABLE dbo.Students
(
    StudentId       UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_Students_StudentId DEFAULT NEWSEQUENTIALID(),
    EnrollmentCode  VARCHAR(32) NOT NULL,
    GivenNames      NVARCHAR(120) NOT NULL,
    FirstSurname    NVARCHAR(80) NOT NULL,
    SecondSurname   NVARCHAR(80) NULL,
    GradeLevel      NVARCHAR(40) NULL,
    GroupCode       NVARCHAR(24) NULL,
    PhotoStorageKey NVARCHAR(500) NULL,
    IsActive        BIT NOT NULL CONSTRAINT DF_Students_IsActive DEFAULT (1),
    CreatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_Students_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_Students_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    RowVersion      ROWVERSION NOT NULL,
    CONSTRAINT PK_Students PRIMARY KEY (StudentId),
    CONSTRAINT UQ_Students_EnrollmentCode UNIQUE (EnrollmentCode)
);
GO
CREATE INDEX IX_Students_Name ON dbo.Students(FirstSurname, SecondSurname, GivenNames) INCLUDE (EnrollmentCode, GradeLevel, GroupCode, IsActive);
CREATE INDEX IX_Students_GradeGroup ON dbo.Students(GradeLevel, GroupCode, IsActive) INCLUDE (EnrollmentCode, GivenNames, FirstSurname);
GO

CREATE TABLE dbo.StudentGuardians
(
    StudentGuardianId BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_StudentGuardians PRIMARY KEY,
    StudentId         UNIQUEIDENTIFIER NOT NULL,
    GuardianUserId    UNIQUEIDENTIFIER NOT NULL,
    RelationshipCode  VARCHAR(24) NOT NULL,
    IsPrimaryContact  BIT NOT NULL CONSTRAINT DF_StudentGuardians_IsPrimaryContact DEFAULT (0),
    ReceivesAlerts    BIT NOT NULL CONSTRAINT DF_StudentGuardians_ReceivesAlerts DEFAULT (1),
    IsActive          BIT NOT NULL CONSTRAINT DF_StudentGuardians_IsActive DEFAULT (1),
    CreatedAtUtc      DATETIME2(0) NOT NULL CONSTRAINT DF_StudentGuardians_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    CreatedByUserId   UNIQUEIDENTIFIER NULL,
    CONSTRAINT FK_StudentGuardians_Students FOREIGN KEY (StudentId) REFERENCES dbo.Students(StudentId),
    CONSTRAINT FK_StudentGuardians_Guardians FOREIGN KEY (GuardianUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_StudentGuardians_CreatedBy FOREIGN KEY (CreatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_StudentGuardians_Relationship CHECK (RelationshipCode IN ('MOTHER','FATHER','LEGAL_GUARDIAN','OTHER'))
);
GO
CREATE UNIQUE INDEX UX_StudentGuardians_ActivePair ON dbo.StudentGuardians(StudentId, GuardianUserId) WHERE IsActive = 1;
CREATE INDEX IX_StudentGuardians_Guardian ON dbo.StudentGuardians(GuardianUserId, IsActive) INCLUDE (StudentId, ReceivesAlerts);
GO

CREATE TABLE dbo.MedicalRecords
(
    MedicalRecordId UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_MedicalRecords_Id DEFAULT NEWSEQUENTIALID(),
    StudentId       UNIQUEIDENTIFIER NOT NULL,
    BloodType       VARCHAR(3) NULL,
    GeneralNotes    NVARCHAR(2000) NULL,
    CreatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_MedicalRecords_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    CreatedByUserId UNIQUEIDENTIFIER NOT NULL,
    UpdatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_MedicalRecords_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedByUserId UNIQUEIDENTIFIER NOT NULL,
    RowVersion      ROWVERSION NOT NULL,
    CONSTRAINT PK_MedicalRecords PRIMARY KEY (MedicalRecordId),
    CONSTRAINT UQ_MedicalRecords_Student UNIQUE (StudentId),
    CONSTRAINT FK_MedicalRecords_Students FOREIGN KEY (StudentId) REFERENCES dbo.Students(StudentId),
    CONSTRAINT FK_MedicalRecords_CreatedBy FOREIGN KEY (CreatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_MedicalRecords_UpdatedBy FOREIGN KEY (UpdatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_MedicalRecords_BloodType CHECK (BloodType IS NULL OR BloodType IN ('A+','A-','B+','B-','AB+','AB-','O+','O-','UNKNOWN'))
);
GO

CREATE TABLE dbo.MedicalRecordItems
(
    MedicalRecordItemId UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_MedicalRecordItems_Id DEFAULT NEWSEQUENTIALID(),
    MedicalRecordId     UNIQUEIDENTIFIER NOT NULL,
    ItemTypeCode        VARCHAR(24) NOT NULL,
    Name                NVARCHAR(160) NOT NULL,
    Details             NVARCHAR(1000) NULL,
    IsActive            BIT NOT NULL CONSTRAINT DF_MedicalRecordItems_IsActive DEFAULT (1),
    CreatedAtUtc        DATETIME2(0) NOT NULL CONSTRAINT DF_MedicalRecordItems_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    CreatedByUserId     UNIQUEIDENTIFIER NOT NULL,
    UpdatedAtUtc        DATETIME2(0) NOT NULL CONSTRAINT DF_MedicalRecordItems_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedByUserId     UNIQUEIDENTIFIER NOT NULL,
    CONSTRAINT PK_MedicalRecordItems PRIMARY KEY (MedicalRecordItemId),
    CONSTRAINT FK_MedicalRecordItems_Record FOREIGN KEY (MedicalRecordId) REFERENCES dbo.MedicalRecords(MedicalRecordId),
    CONSTRAINT FK_MedicalRecordItems_CreatedBy FOREIGN KEY (CreatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_MedicalRecordItems_UpdatedBy FOREIGN KEY (UpdatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_MedicalRecordItems_Type CHECK (ItemTypeCode IN ('ALLERGY','CHRONIC_CONDITION','MEDICATION','OTHER'))
);
GO
CREATE INDEX IX_MedicalRecordItems_Record ON dbo.MedicalRecordItems(MedicalRecordId, ItemTypeCode, IsActive);
GO

CREATE TABLE dbo.EmergencyContacts
(
    EmergencyContactId BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_EmergencyContacts PRIMARY KEY,
    StudentId          UNIQUEIDENTIFIER NOT NULL,
    ContactUserId      UNIQUEIDENTIFIER NULL,
    FullName           NVARCHAR(200) NOT NULL,
    RelationshipCode   VARCHAR(24) NOT NULL,
    Phone              NVARCHAR(32) NOT NULL,
    PriorityOrder      TINYINT NOT NULL,
    IsActive           BIT NOT NULL CONSTRAINT DF_EmergencyContacts_IsActive DEFAULT (1),
    CONSTRAINT FK_EmergencyContacts_Students FOREIGN KEY (StudentId) REFERENCES dbo.Students(StudentId),
    CONSTRAINT FK_EmergencyContacts_Users FOREIGN KEY (ContactUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_EmergencyContacts_Priority CHECK (PriorityOrder BETWEEN 1 AND 10)
);
GO
CREATE INDEX IX_EmergencyContacts_Student ON dbo.EmergencyContacts(StudentId, IsActive, PriorityOrder);
GO

CREATE TABLE dbo.MedicalVisits
(
    MedicalVisitId        UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_MedicalVisits_Id DEFAULT NEWSEQUENTIALID(),
    StudentId             UNIQUEIDENTIFIER NOT NULL,
    EntryAtUtc            DATETIMEOFFSET(0) NOT NULL,
    ExitAtUtc             DATETIMEOFFSET(0) NULL,
    Reason                NVARCHAR(500) NOT NULL,
    Symptoms              NVARCHAR(2000) NULL,
    PreliminaryDiagnosis  NVARCHAR(1000) NULL,
    Treatment             NVARCHAR(2000) NULL,
    MedicationGiven       NVARCHAR(500) NULL,
    MedicationDose        NVARCHAR(160) NULL,
    ReferredExternally    BIT NOT NULL CONSTRAINT DF_MedicalVisits_Referred DEFAULT (0),
    ReferralDestination   NVARCHAR(240) NULL,
    CreatedByUserId       UNIQUEIDENTIFIER NOT NULL,
    CreatedAtUtc          DATETIME2(0) NOT NULL CONSTRAINT DF_MedicalVisits_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_MedicalVisits PRIMARY KEY (MedicalVisitId),
    CONSTRAINT FK_MedicalVisits_Students FOREIGN KEY (StudentId) REFERENCES dbo.Students(StudentId),
    CONSTRAINT FK_MedicalVisits_CreatedBy FOREIGN KEY (CreatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_MedicalVisits_Times CHECK (ExitAtUtc IS NULL OR ExitAtUtc >= EntryAtUtc),
    CONSTRAINT CK_MedicalVisits_Referral CHECK (ReferredExternally = 1 OR ReferralDestination IS NULL)
);
GO
CREATE INDEX IX_MedicalVisits_StudentEntry ON dbo.MedicalVisits(StudentId, EntryAtUtc DESC);
CREATE INDEX IX_MedicalVisits_Open ON dbo.MedicalVisits(EntryAtUtc) WHERE ExitAtUtc IS NULL;
GO

CREATE TABLE dbo.IncidentTypes
(
    IncidentTypeCode VARCHAR(40) NOT NULL CONSTRAINT PK_IncidentTypes PRIMARY KEY,
    DisplayName      NVARCHAR(100) NOT NULL,
    IsActive         BIT NOT NULL CONSTRAINT DF_IncidentTypes_IsActive DEFAULT (1),
    SortOrder        SMALLINT NOT NULL
);
GO
CREATE TABLE dbo.IncidentSeverities
(
    SeverityCode VARCHAR(16) NOT NULL CONSTRAINT PK_IncidentSeverities PRIMARY KEY,
    DisplayName  NVARCHAR(40) NOT NULL,
    SortOrder    TINYINT NOT NULL
);
GO
CREATE TABLE dbo.IncidentStatuses
(
    StatusCode  VARCHAR(24) NOT NULL CONSTRAINT PK_IncidentStatuses PRIMARY KEY,
    DisplayName NVARCHAR(60) NOT NULL,
    IsFinal     BIT NOT NULL CONSTRAINT DF_IncidentStatuses_IsFinal DEFAULT (0),
    SortOrder   TINYINT NOT NULL
);
GO

CREATE TABLE dbo.Incidents
(
    IncidentId       UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_Incidents_Id DEFAULT NEWSEQUENTIALID(),
    Folio            VARCHAR(32) NOT NULL,
    StudentId        UNIQUEIDENTIFIER NOT NULL,
    IncidentTypeCode VARCHAR(40) NOT NULL,
    SeverityCode     VARCHAR(16) NOT NULL,
    StatusCode       VARCHAR(24) NOT NULL CONSTRAINT DF_Incidents_Status DEFAULT ('PENDING'),
    OccurredAt       DATETIMEOFFSET(0) NOT NULL,
    Description      NVARCHAR(3000) NOT NULL,
    ReportedByUserId UNIQUEIDENTIFIER NOT NULL,
    CreatedAtUtc     DATETIME2(0) NOT NULL CONSTRAINT DF_Incidents_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedAtUtc     DATETIME2(0) NOT NULL CONSTRAINT DF_Incidents_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedByUserId  UNIQUEIDENTIFIER NULL,
    ClosedAtUtc      DATETIME2(0) NULL,
    ClosedByUserId   UNIQUEIDENTIFIER NULL,
    RowVersion       ROWVERSION NOT NULL,
    CONSTRAINT PK_Incidents PRIMARY KEY (IncidentId),
    CONSTRAINT UQ_Incidents_Folio UNIQUE (Folio),
    CONSTRAINT FK_Incidents_Students FOREIGN KEY (StudentId) REFERENCES dbo.Students(StudentId),
    CONSTRAINT FK_Incidents_Types FOREIGN KEY (IncidentTypeCode) REFERENCES dbo.IncidentTypes(IncidentTypeCode),
    CONSTRAINT FK_Incidents_Severities FOREIGN KEY (SeverityCode) REFERENCES dbo.IncidentSeverities(SeverityCode),
    CONSTRAINT FK_Incidents_Statuses FOREIGN KEY (StatusCode) REFERENCES dbo.IncidentStatuses(StatusCode),
    CONSTRAINT FK_Incidents_Reporter FOREIGN KEY (ReportedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_Incidents_UpdatedBy FOREIGN KEY (UpdatedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT FK_Incidents_ClosedBy FOREIGN KEY (ClosedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_Incidents_ClosedState CHECK
    (
        (StatusCode = 'CLOSED' AND ClosedAtUtc IS NOT NULL AND ClosedByUserId IS NOT NULL) OR
        (StatusCode <> 'CLOSED' AND ClosedAtUtc IS NULL AND ClosedByUserId IS NULL)
    )
);
GO
CREATE INDEX IX_Incidents_StudentOccurred ON dbo.Incidents(StudentId, OccurredAt DESC) INCLUDE (Folio, IncidentTypeCode, SeverityCode, StatusCode);
CREATE INDEX IX_Incidents_StatusOccurred ON dbo.Incidents(StatusCode, OccurredAt DESC) INCLUDE (Folio, StudentId, SeverityCode);
CREATE INDEX IX_Incidents_ReporterCreated ON dbo.Incidents(ReportedByUserId, CreatedAtUtc DESC);
GO

CREATE TABLE dbo.IncidentStatusHistory
(
    IncidentStatusHistoryId BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_IncidentStatusHistory PRIMARY KEY,
    IncidentId              UNIQUEIDENTIFIER NOT NULL,
    PreviousStatusCode      VARCHAR(24) NULL,
    NewStatusCode           VARCHAR(24) NOT NULL,
    ChangedByUserId         UNIQUEIDENTIFIER NULL,
    ChangedAtUtc            DATETIME2(0) NOT NULL CONSTRAINT DF_IncidentStatusHistory_ChangedAtUtc DEFAULT SYSUTCDATETIME(),
    Comment                 NVARCHAR(500) NULL,
    CONSTRAINT FK_IncidentStatusHistory_Incident FOREIGN KEY (IncidentId) REFERENCES dbo.Incidents(IncidentId) ON DELETE CASCADE,
    CONSTRAINT FK_IncidentStatusHistory_Previous FOREIGN KEY (PreviousStatusCode) REFERENCES dbo.IncidentStatuses(StatusCode),
    CONSTRAINT FK_IncidentStatusHistory_New FOREIGN KEY (NewStatusCode) REFERENCES dbo.IncidentStatuses(StatusCode),
    CONSTRAINT FK_IncidentStatusHistory_User FOREIGN KEY (ChangedByUserId) REFERENCES dbo.Users(UserId)
);
GO
CREATE INDEX IX_IncidentStatusHistory_Incident ON dbo.IncidentStatusHistory(IncidentId, ChangedAtUtc DESC);
GO

CREATE TABLE dbo.IncidentAttachments
(
    IncidentAttachmentId UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_IncidentAttachments_Id DEFAULT NEWSEQUENTIALID(),
    IncidentId           UNIQUEIDENTIFIER NOT NULL,
    StorageKey           NVARCHAR(500) NOT NULL,
    FileName             NVARCHAR(255) NOT NULL,
    MediaType            VARCHAR(100) NOT NULL,
    SizeBytes            BIGINT NOT NULL,
    Sha256               BINARY(32) NOT NULL,
    UploadedByUserId     UNIQUEIDENTIFIER NOT NULL,
    UploadedAtUtc        DATETIME2(0) NOT NULL CONSTRAINT DF_IncidentAttachments_UploadedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_IncidentAttachments PRIMARY KEY (IncidentAttachmentId),
    CONSTRAINT FK_IncidentAttachments_Incident FOREIGN KEY (IncidentId) REFERENCES dbo.Incidents(IncidentId) ON DELETE CASCADE,
    CONSTRAINT FK_IncidentAttachments_User FOREIGN KEY (UploadedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_IncidentAttachments_Size CHECK (SizeBytes > 0)
);
GO
CREATE INDEX IX_IncidentAttachments_Incident ON dbo.IncidentAttachments(IncidentId);
GO

CREATE TABLE dbo.IncidentDeletionAudit
(
    IncidentDeletionAuditId BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_IncidentDeletionAudit PRIMARY KEY,
    DeletedIncidentId       UNIQUEIDENTIFIER NOT NULL,
    DeletedFolio            VARCHAR(32) NOT NULL,
    DeletedByUserId         UNIQUEIDENTIFIER NOT NULL,
    DeletedAtUtc            DATETIME2(0) NOT NULL CONSTRAINT DF_IncidentDeletionAudit_DeletedAtUtc DEFAULT SYSUTCDATETIME(),
    Reason                  NVARCHAR(1000) NOT NULL,
    CONSTRAINT FK_IncidentDeletionAudit_User FOREIGN KEY (DeletedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT UQ_IncidentDeletionAudit_Incident UNIQUE (DeletedIncidentId),
    CONSTRAINT CK_IncidentDeletionAudit_Reason CHECK (LEN(LTRIM(RTRIM(Reason))) > 0)
);
GO
CREATE INDEX IX_IncidentDeletionAudit_DeletedAt ON dbo.IncidentDeletionAudit(DeletedAtUtc DESC);
GO

CREATE TABLE dbo.NotificationPreferences
(
    UserId          UNIQUEIDENTIFIER NOT NULL,
    EventCode       VARCHAR(40) NOT NULL,
    ChannelCode     VARCHAR(16) NOT NULL,
    IsEnabled       BIT NOT NULL CONSTRAINT DF_NotificationPreferences_IsEnabled DEFAULT (1),
    UpdatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_NotificationPreferences_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_NotificationPreferences PRIMARY KEY (UserId, EventCode, ChannelCode),
    CONSTRAINT FK_NotificationPreferences_User FOREIGN KEY (UserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_NotificationPreferences_Channel CHECK (ChannelCode IN ('PUSH','EMAIL','IN_APP'))
);
GO

CREATE TABLE dbo.NotificationOutbox
(
    NotificationId  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_NotificationOutbox PRIMARY KEY,
    RecipientUserId UNIQUEIDENTIFIER NOT NULL,
    StudentId       UNIQUEIDENTIFIER NULL,
    IncidentId      UNIQUEIDENTIFIER NULL,
    EventCode       VARCHAR(40) NOT NULL,
    ChannelCode     VARCHAR(16) NOT NULL,
    StatusCode      VARCHAR(16) NOT NULL CONSTRAINT DF_NotificationOutbox_Status DEFAULT ('PENDING'),
    DedupeKey       VARCHAR(120) NOT NULL,
    AttemptCount    SMALLINT NOT NULL CONSTRAINT DF_NotificationOutbox_Attempts DEFAULT (0),
    AvailableAtUtc  DATETIME2(0) NOT NULL CONSTRAINT DF_NotificationOutbox_AvailableAtUtc DEFAULT SYSUTCDATETIME(),
    CreatedAtUtc    DATETIME2(0) NOT NULL CONSTRAINT DF_NotificationOutbox_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    SentAtUtc       DATETIME2(0) NULL,
    LastErrorCode   VARCHAR(80) NULL,
    CONSTRAINT FK_NotificationOutbox_Recipient FOREIGN KEY (RecipientUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_NotificationOutbox_Channel CHECK (ChannelCode IN ('PUSH','EMAIL','IN_APP')),
    CONSTRAINT CK_NotificationOutbox_Status CHECK (StatusCode IN ('PENDING','PROCESSING','SENT','FAILED','CANCELLED')),
    CONSTRAINT CK_NotificationOutbox_Attempts CHECK (AttemptCount >= 0),
    CONSTRAINT UQ_NotificationOutbox_Dedupe UNIQUE (DedupeKey)
);
GO
CREATE INDEX IX_NotificationOutbox_WorkQueue ON dbo.NotificationOutbox(StatusCode, AvailableAtUtc, NotificationId)
    INCLUDE (RecipientUserId, ChannelCode, EventCode, AttemptCount);
CREATE INDEX IX_NotificationOutbox_Recipient ON dbo.NotificationOutbox(RecipientUserId, CreatedAtUtc DESC);
GO

CREATE TABLE dbo.AuditEvents
(
    AuditEventId  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_AuditEvents PRIMARY KEY,
    ActorUserId   UNIQUEIDENTIFIER NULL,
    ActionCode    VARCHAR(24) NOT NULL,
    EntityCode    VARCHAR(48) NOT NULL,
    EntityId      UNIQUEIDENTIFIER NULL,
    EntityKey     NVARCHAR(120) NULL,
    OccurredAtUtc DATETIME2(0) NOT NULL CONSTRAINT DF_AuditEvents_OccurredAtUtc DEFAULT SYSUTCDATETIME(),
    IpAddress     VARCHAR(45) NULL,
    MetadataJson  NVARCHAR(2000) NULL,
    CONSTRAINT FK_AuditEvents_Actor FOREIGN KEY (ActorUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_AuditEvents_Action CHECK (ActionCode IN ('CREATE','UPDATE','DELETE','LOGIN','LOGOUT','READ','ROLE_CHANGE','BACKUP','NOTIFY')),
    CONSTRAINT CK_AuditEvents_MetadataJson CHECK (MetadataJson IS NULL OR ISJSON(MetadataJson) = 1)
);
GO
CREATE INDEX IX_AuditEvents_Time ON dbo.AuditEvents(OccurredAtUtc DESC);
CREATE INDEX IX_AuditEvents_ActorTime ON dbo.AuditEvents(ActorUserId, OccurredAtUtc DESC);
CREATE INDEX IX_AuditEvents_Entity ON dbo.AuditEvents(EntityCode, EntityKey, OccurredAtUtc DESC);
GO

CREATE TABLE dbo.BackupSchedules
(
    BackupScheduleId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_BackupSchedules PRIMARY KEY,
    FrequencyCode    VARCHAR(16) NOT NULL,
    TimeUtc          TIME(0) NOT NULL,
    DayOfWeek        TINYINT NULL,
    IsEnabled        BIT NOT NULL CONSTRAINT DF_BackupSchedules_IsEnabled DEFAULT (1),
    CreatedAtUtc     DATETIME2(0) NOT NULL CONSTRAINT DF_BackupSchedules_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedAtUtc     DATETIME2(0) NOT NULL CONSTRAINT DF_BackupSchedules_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_BackupSchedules_Frequency CHECK (FrequencyCode IN ('DAILY','WEEKLY')),
    CONSTRAINT CK_BackupSchedules_Day CHECK
    (
        (FrequencyCode = 'DAILY' AND DayOfWeek IS NULL) OR
        (FrequencyCode = 'WEEKLY' AND DayOfWeek IS NOT NULL AND DayOfWeek BETWEEN 1 AND 7)
    )
);
GO

CREATE TABLE dbo.BackupRuns
(
    BackupRunId      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_BackupRuns PRIMARY KEY,
    BackupScheduleId INT NULL,
    StartedAtUtc     DATETIME2(0) NOT NULL CONSTRAINT DF_BackupRuns_StartedAtUtc DEFAULT SYSUTCDATETIME(),
    CompletedAtUtc   DATETIME2(0) NULL,
    StatusCode       VARCHAR(16) NOT NULL CONSTRAINT DF_BackupRuns_Status DEFAULT ('RUNNING'),
    StorageKey       NVARCHAR(500) NULL,
    SizeBytes        BIGINT NULL,
    Sha256           BINARY(32) NULL,
    RequestedByUserId UNIQUEIDENTIFIER NULL,
    ErrorCode        VARCHAR(80) NULL,
    CONSTRAINT FK_BackupRuns_Schedule FOREIGN KEY (BackupScheduleId) REFERENCES dbo.BackupSchedules(BackupScheduleId),
    CONSTRAINT FK_BackupRuns_User FOREIGN KEY (RequestedByUserId) REFERENCES dbo.Users(UserId),
    CONSTRAINT CK_BackupRuns_Status CHECK (StatusCode IN ('RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT CK_BackupRuns_Size CHECK (SizeBytes IS NULL OR SizeBytes >= 0)
);
GO
CREATE INDEX IX_BackupRuns_Started ON dbo.BackupRuns(StartedAtUtc DESC);
GO

CREATE OR ALTER TRIGGER dbo.TR_Incidents_Audit
ON dbo.Incidents
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;

    INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, OccurredAtUtc)
    SELECT
        TRY_CONVERT(UNIQUEIDENTIFIER, SESSION_CONTEXT(N'ActorUserId')),
        CASE WHEN d.IncidentId IS NULL THEN 'CREATE' ELSE 'UPDATE' END,
        'INCIDENT', i.IncidentId, i.Folio, SYSUTCDATETIME()
    FROM inserted AS i
    LEFT JOIN deleted AS d ON d.IncidentId = i.IncidentId;

    INSERT dbo.IncidentStatusHistory(IncidentId, PreviousStatusCode, NewStatusCode, ChangedByUserId, ChangedAtUtc)
    SELECT
        i.IncidentId, NULL, i.StatusCode,
        TRY_CONVERT(UNIQUEIDENTIFIER, SESSION_CONTEXT(N'ActorUserId')),
        SYSUTCDATETIME()
    FROM inserted AS i
    LEFT JOIN deleted AS d ON d.IncidentId = i.IncidentId
    WHERE d.IncidentId IS NULL;

    INSERT dbo.IncidentStatusHistory(IncidentId, PreviousStatusCode, NewStatusCode, ChangedByUserId, ChangedAtUtc)
    SELECT
        i.IncidentId, d.StatusCode, i.StatusCode,
        TRY_CONVERT(UNIQUEIDENTIFIER, SESSION_CONTEXT(N'ActorUserId')),
        SYSUTCDATETIME()
    FROM inserted AS i
    INNER JOIN deleted AS d ON d.IncidentId = i.IncidentId
    WHERE d.StatusCode <> i.StatusCode;
END;
GO

CREATE OR ALTER TRIGGER dbo.TR_AuditEvents_AppendOnly
ON dbo.AuditEvents
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51010, 'AuditEvents es append-only; no se permiten UPDATE ni DELETE.', 1;
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

    DECLARE @Folio VARCHAR(32);
    SELECT @Folio = Folio FROM dbo.Incidents WHERE IncidentId = @IncidentId;
    IF @Folio IS NULL
        THROW 51013, 'La incidencia no existe o ya fue eliminada.', 1;

    BEGIN TRY
        BEGIN TRANSACTION;

        INSERT dbo.IncidentDeletionAudit(DeletedIncidentId, DeletedFolio, DeletedByUserId, Reason)
        VALUES (@IncidentId, @Folio, @ActorUserId, LTRIM(RTRIM(@Reason)));

        INSERT dbo.AuditEvents(ActorUserId, ActionCode, EntityCode, EntityId, EntityKey, MetadataJson)
        VALUES
        (
            @ActorUserId, 'DELETE', 'INCIDENT', @IncidentId, @Folio,
            (SELECT LTRIM(RTRIM(@Reason)) AS reason FOR JSON PATH, WITHOUT_ARRAY_WRAPPER)
        );

        DELETE dbo.Incidents WHERE IncidentId = @IncidentId;
        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH;
END;
GO

/* Catálogos iniciales. Los IDs se obtienen por código, no se fijan manualmente. */
MERGE dbo.Roles AS target
USING (VALUES
    ('ADMIN', N'Administrador'),
    ('DIRECTOR', N'Director'),
    ('NURSE', N'Enfermería'),
    ('GUARDIAN', N'Madre, padre o tutor legal')
) AS source(RoleCode, DisplayName)
ON target.RoleCode = source.RoleCode
WHEN NOT MATCHED THEN INSERT(RoleCode, DisplayName) VALUES(source.RoleCode, source.DisplayName);
GO

MERGE dbo.Permissions AS target
USING (VALUES
    ('students.read', 'students', 'read', N'Buscar y consultar alumnos'),
    ('medical.read', 'medical', 'read', N'Consultar expedientes médicos'),
    ('medical.write', 'medical', 'write', N'Crear o actualizar expedientes médicos'),
    ('medical_visits.create', 'medical_visits', 'create', N'Registrar ingresos a enfermería'),
    ('incidents.create', 'incidents', 'create', N'Registrar incidencias autorizadas'),
    ('incidents.read_all', 'incidents', 'read', N'Consultar incidencias institucionales'),
    ('incidents.read_linked', 'incidents', 'read_linked', N'Consultar incidencias de alumnos asociados'),
    ('incidents.update_all', 'incidents', 'update', N'Editar incidencias institucionales'),
    ('incidents.update_own', 'incidents', 'update_own', N'Editar incidencias propias no cerradas'),
    ('incidents.delete', 'incidents', 'delete', N'Eliminar incidencias con motivo y bitácora'),
    ('reports.read', 'reports', 'read', N'Consultar y exportar reportes'),
    ('users.manage', 'users', 'manage', N'Gestionar cuentas de usuario'),
    ('guardians.manage', 'guardians', 'manage', N'Gestionar perfiles familiares'),
    ('associations.manage', 'associations', 'manage', N'Gestionar asociaciones alumno-familia'),
    ('roles.manage', 'roles', 'manage', N'Gestionar roles y permisos'),
    ('backups.manage', 'backups', 'manage', N'Gestionar respaldos'),
    ('audit.read', 'audit', 'read', N'Consultar bitácora de auditoría'),
    ('notifications.read', 'notifications', 'read', N'Consultar notificaciones propias'),
    ('notifications.manage', 'notifications', 'manage', N'Gestionar preferencias y envíos')
) AS source(PermissionCode, ModuleCode, ActionCode, Description)
ON target.PermissionCode = source.PermissionCode
WHEN NOT MATCHED THEN
    INSERT(PermissionCode, ModuleCode, ActionCode, Description)
    VALUES(source.PermissionCode, source.ModuleCode, source.ActionCode, source.Description);
GO

/*
  Matriz base guiada por las HU/RF específicos. El backend debe volver a validar
  cada permiso y el alcance Guardian debe comprobar StudentGuardians por alumno.
  El rol para eliminar se toma de HU-18/RF-17; el conflicto con sección 2.1 se
  registra y debe ser ratificado por el equipo antes de producción.
*/
WITH Grants(RoleCode, PermissionCode) AS
(
    SELECT v.RoleCode, v.PermissionCode
    FROM (VALUES
        ('ADMIN','students.read'),('ADMIN','incidents.read_all'),('ADMIN','incidents.delete'),
        ('ADMIN','users.manage'),('ADMIN','guardians.manage'),('ADMIN','associations.manage'),
        ('ADMIN','roles.manage'),('ADMIN','backups.manage'),('ADMIN','audit.read'),('ADMIN','notifications.manage'),
        ('DIRECTOR','students.read'),('DIRECTOR','incidents.create'),('DIRECTOR','incidents.read_all'),
        ('DIRECTOR','incidents.update_all'),('DIRECTOR','reports.read'),('DIRECTOR','notifications.read'),
        ('NURSE','students.read'),('NURSE','medical.read'),('NURSE','medical.write'),('NURSE','medical_visits.create'),
        ('NURSE','incidents.create'),('NURSE','reports.read'),('NURSE','notifications.read'),
        ('GUARDIAN','incidents.create'),('GUARDIAN','incidents.read_linked'),('GUARDIAN','incidents.update_own'),
        ('GUARDIAN','notifications.read')
    ) v(RoleCode, PermissionCode)
)
INSERT dbo.RolePermissions(RoleId, PermissionId)
SELECT r.RoleId, p.PermissionId
FROM Grants g
INNER JOIN dbo.Roles r ON r.RoleCode = g.RoleCode
INNER JOIN dbo.Permissions p ON p.PermissionCode = g.PermissionCode
WHERE NOT EXISTS
(
    SELECT 1 FROM dbo.RolePermissions existing
    WHERE existing.RoleId = r.RoleId AND existing.PermissionId = p.PermissionId
);
GO

MERGE dbo.IncidentTypes AS target
USING (VALUES
    ('GOLPE', N'Golpe', 1),
    ('CAIDA', N'Caída', 2),
    ('HERIDA_CORTE', N'Herida o corte', 3),
    ('AGRESION', N'Pelea o agresión', 4),
    ('MAREO_DESMAYO', N'Mareo o desmayo', 5),
    ('REACCION_ALERGICA', N'Reacción alérgica', 6),
    ('FIEBRE_MALESTAR', N'Fiebre o malestar general', 7),
    ('DANO', N'Daño a pertenencias o instalaciones', 8),
    ('OTRO', N'Otro', 9)
) AS source(IncidentTypeCode, DisplayName, SortOrder)
ON target.IncidentTypeCode = source.IncidentTypeCode
WHEN NOT MATCHED THEN INSERT(IncidentTypeCode, DisplayName, SortOrder)
VALUES(source.IncidentTypeCode, source.DisplayName, source.SortOrder);
GO

MERGE dbo.IncidentSeverities AS target
USING (VALUES ('BAJA',N'Baja',1),('MEDIA',N'Media',2),('FUERTE',N'Fuerte',3))
    AS source(SeverityCode, DisplayName, SortOrder)
ON target.SeverityCode = source.SeverityCode
WHEN NOT MATCHED THEN INSERT(SeverityCode, DisplayName, SortOrder)
VALUES(source.SeverityCode, source.DisplayName, source.SortOrder);
GO

MERGE dbo.IncidentStatuses AS target
USING (VALUES
    ('PENDING',N'Pendiente',0,1),
    ('IN_REVIEW',N'En revisión',0,2),
    ('CLOSED',N'Cerrada',1,3)
) AS source(StatusCode, DisplayName, IsFinal, SortOrder)
ON target.StatusCode = source.StatusCode
WHEN NOT MATCHED THEN INSERT(StatusCode, DisplayName, IsFinal, SortOrder)
VALUES(source.StatusCode, source.DisplayName, source.IsFinal, source.SortOrder);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.SchemaMigrations WHERE Version = 1)
    INSERT dbo.SchemaMigrations(Version, Description) VALUES (1, N'Esquema inicial SIRA: usuarios, alumnos, salud, incidencias, permisos, notificaciones, auditoría y respaldos');
GO
