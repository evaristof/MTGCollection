-- Adds the icon_svg_uri column introduced by the set-icons feature.
-- Run this against PostgreSQL if Hibernate's ddl-auto=update did not
-- create the column automatically.
--
-- Safe to re-run: the IF NOT EXISTS guard prevents errors on databases
-- that already have the column.

ALTER TABLE magic_set ADD COLUMN IF NOT EXISTS icon_svg_uri VARCHAR(255);
