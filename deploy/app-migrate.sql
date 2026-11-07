-- Idempotent application-database migrations.
--
-- db/app-schema.sql is executed by MySQL only on an empty data directory. An existing volume
-- therefore keeps its old columns, and a new column referenced by the code makes every
-- SELECT/INSERT fail. deploy/bootstrap.sh runs this file on every deploy to close that gap.
--
-- MySQL 8 has no "ADD COLUMN IF NOT EXISTS", so the statement is built from information_schema.

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.columns
          WHERE table_schema = DATABASE()
            AND table_name = 'message'
            AND column_name = 'citations_json') = 0,
        'ALTER TABLE message ADD COLUMN citations_json TEXT NULL AFTER rows_json',
        'SELECT ''message.citations_json already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;
