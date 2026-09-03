-- Adds the LOCATION table introduced by the "Cadastro Cartas" /
-- "Cadastro de Localização" feature.
-- Run this against PostgreSQL if Hibernate's ddl-auto=update did not
-- create the table automatically.
--
-- Safe to re-run: the IF NOT EXISTS guards prevent errors on databases
-- that already have the table.

CREATE TABLE IF NOT EXISTS location (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1024)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_location_name ON location (name);
