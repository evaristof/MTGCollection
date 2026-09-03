-- ---------------------------------------------------------------------------
-- Migrates COLLECTION_CARD.LOCALIZACAO (free text) to COLLECTION_CARD.LOCATION_ID
-- (FK -> LOCATION), introduced with the "Cadastro Cartas" / "Cadastro de
-- Localização" feature.
--
-- Run this ONCE against PostgreSQL:
--
--   psql -h localhost -U admin -d mtgdb -f V4__migrate_localizacao_to_location_fk.sql
--
-- Hibernate (ddl-auto=update) creates the LOCATION table and adds the
-- LOCATION_ID column by itself when the app boots, but it never backfills data
-- and never drops the old column — which is exactly what this script does. It
-- works before OR after the first boot with the new code, and is idempotent:
-- once LOCALIZACAO is gone the data steps are skipped.
--
-- Postgres folds unquoted identifiers to lower case, so the JPA columns
-- (LOCALIZACAO, LOCATION_ID, ...) are the lower-case names used below.
-- ---------------------------------------------------------------------------

BEGIN;

-- 1. Catalog table + FK column, in case the app hasn't booted with the new
--    code yet.
CREATE TABLE IF NOT EXISTS location (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1024)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_location_name ON location (name);

ALTER TABLE collection_card ADD COLUMN IF NOT EXISTS location_id BIGINT;

-- 2..4. Data migration — only while the old column still exists.
DO $$
DECLARE
    orphans BIGINT;
    migrated BIGINT;
    created BIGINT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'collection_card'
           AND column_name = 'localizacao'
    ) THEN
        RAISE NOTICE 'collection_card.localizacao não existe mais — migração já aplicada, nada a fazer.';
        RETURN;
    END IF;

    -- 2. One catalog row per distinct location already in use. Values are
    --    trimmed and de-duplicated case-insensitively ("caixa 3" and "Caixa 3"
    --    collapse into one row), keeping the alphabetically first spelling as
    --    the canonical name.
    EXECUTE $sql$
        WITH distinct_locs AS (
            SELECT MIN(TRIM(localizacao))   AS name,
                   LOWER(TRIM(localizacao)) AS match_key
              FROM collection_card
             WHERE localizacao IS NOT NULL
               AND TRIM(localizacao) <> ''
             GROUP BY LOWER(TRIM(localizacao))
        )
        INSERT INTO location (name)
        SELECT d.name
          FROM distinct_locs d
         WHERE NOT EXISTS (
                SELECT 1 FROM location l WHERE LOWER(l.name) = d.match_key
         )
    $sql$;
    GET DIAGNOSTICS created = ROW_COUNT;

    -- 3. Point every card at its catalog row.
    EXECUTE $sql$
        UPDATE collection_card cc
           SET location_id = l.id
          FROM location l
         WHERE cc.location_id IS NULL
           AND cc.localizacao IS NOT NULL
           AND TRIM(cc.localizacao) <> ''
           AND LOWER(TRIM(cc.localizacao)) = LOWER(l.name)
    $sql$;
    GET DIAGNOSTICS migrated = ROW_COUNT;

    -- Safety net: never drop the column while a non-empty value is still
    -- unmapped, that would silently lose data.
    EXECUTE $sql$
        SELECT COUNT(*)
          FROM collection_card
         WHERE localizacao IS NOT NULL
           AND TRIM(localizacao) <> ''
           AND location_id IS NULL
    $sql$ INTO orphans;

    IF orphans > 0 THEN
        RAISE EXCEPTION 'Migração abortada: % carta(s) com localizacao não migrada', orphans;
    END IF;

    -- 4. Old column is now redundant.
    EXECUTE 'ALTER TABLE collection_card DROP COLUMN localizacao';

    RAISE NOTICE 'Migração concluída: % localização(ões) criada(s), % carta(s) vinculada(s).',
        created, migrated;
END $$;

-- 5. Constraint + index for the new FK (idempotent). Hibernate creates its own
--    FK (with a generated name) when it adds the column, so the check looks for
--    ANY collection_card -> location foreign key instead of just this name.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint con
          JOIN pg_class rel  ON rel.oid  = con.conrelid
          JOIN pg_class frel ON frel.oid = con.confrelid
         WHERE con.contype = 'f'
           AND rel.relname = 'collection_card'
           AND frel.relname = 'location'
    ) THEN
        ALTER TABLE collection_card
            ADD CONSTRAINT fk_collection_card_location
            FOREIGN KEY (location_id) REFERENCES location (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_collection_card_location_id
    ON collection_card (location_id);

COMMIT;

-- ---------------------------------------------------------------------------
-- NOT migrated on purpose: COLLECTION_CARD_DATA_DUMP.LOCALIZACAO. That table is
-- an immutable point-in-time snapshot of the collection, so it keeps the
-- location name it had when the snapshot was taken — renaming or deleting a
-- location later must not rewrite history.
-- ---------------------------------------------------------------------------
