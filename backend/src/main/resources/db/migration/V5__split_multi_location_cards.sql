-- ---------------------------------------------------------------------------
-- Divide as cartas cuja "localização" era, na verdade, DUAS localizações com a
-- quantidade de cada uma entre parênteses — herança da planilha em Excel:
--
--   "Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)"
--        -> 3 cópias em "Blue Pasta GameGenic"
--        -> 5 cópias em "Dragon Pasta Troca"
--
-- Cada carta nessa situação vira duas linhas (uma por localização real, com a
-- quantidade que estava entre parênteses) e as localizações compostas somem do
-- catálogo LOCATION.
--
-- Rodar UMA vez, depois da V4:
--
--   psql -h localhost -U admin -d mtgdb -f V5__split_multi_location_cards.sql
--
-- É idempotente: rodada de novo não encontra mais nenhuma localização composta
-- e não faz nada. As quantidades entre parênteses são a fonte da verdade — se
-- a soma delas for diferente da QUANTITY que a carta tinha, o script avisa
-- (NOTICE) linha a linha antes de aplicar.
-- ---------------------------------------------------------------------------

BEGIN;

DO $$
DECLARE
    r          RECORD;
    v_afetadas INT := 0;
    v_criadas  INT := 0;
    v_merged   INT := 0;
    v_removed  INT := 0;
    v_mismatch INT := 0;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'collection_card'
           AND column_name = 'location_id'
    ) THEN
        RAISE EXCEPTION 'collection_card.location_id não existe — rode antes a migração V4.';
    END IF;

    -- 1. Mapa: localização composta -> (localização real, quantidade, ordem).
    --    Para incluir novos casos, basta acrescentar duas linhas aqui.
    CREATE TEMP TABLE tmp_split (
        compound TEXT NOT NULL,
        target   TEXT NOT NULL,
        qty      INT  NOT NULL,
        part     INT  NOT NULL
    ) ON COMMIT DROP;

    INSERT INTO tmp_split (compound, target, qty, part) VALUES
        ('Blue Pasta GameGenic (1) e Dragon Pasta Troca (1)', 'Blue Pasta GameGenic', 1, 1),
        ('Blue Pasta GameGenic (1) e Dragon Pasta Troca (1)', 'Dragon Pasta Troca',   1, 2),
        ('Blue Pasta GameGenic (1) e Dragon Pasta Troca (2)', 'Blue Pasta GameGenic', 1, 1),
        ('Blue Pasta GameGenic (1) e Dragon Pasta Troca (2)', 'Dragon Pasta Troca',   2, 2),
        ('Blue Pasta GameGenic (2) e Dragon Pasta Troca(1)',  'Blue Pasta GameGenic', 2, 1),
        ('Blue Pasta GameGenic (2) e Dragon Pasta Troca(1)',  'Dragon Pasta Troca',   1, 2),
        ('Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)', 'Blue Pasta GameGenic', 3, 1),
        ('Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)', 'Dragon Pasta Troca',   5, 2);

    -- 2. As localizações reais precisam existir no catálogo.
    INSERT INTO location (name)
    SELECT DISTINCT s.target
      FROM tmp_split s
     WHERE NOT EXISTS (
            SELECT 1 FROM location l WHERE lower(btrim(l.name)) = lower(s.target)
     );

    -- 3. Fotografia das cartas afetadas: identidade (para o merge lá embaixo) e
    --    comparação entre a quantidade atual e a soma das partes.
    CREATE TEMP TABLE tmp_afetadas ON COMMIT DROP AS
    SELECT cc.id,
           cc.card_name,
           cc.set_code,
           cc.quantity AS qtd_atual,
           (SELECT SUM(s.qty) FROM tmp_split s
             WHERE lower(s.compound) = lower(btrim(l.name)))::INT AS qtd_partes,
           btrim(l.name) AS composta,
           (coalesce(cc.set_code, '') || '|' || coalesce(cc.card_number, '') || '|'
             || lower(coalesce(cc.card_name, '')) || '|' || cc.foil::TEXT || '|'
             || lower(coalesce(cc.language, ''))) AS ident
      FROM collection_card cc
      JOIN location l ON l.id = cc.location_id
     WHERE lower(btrim(l.name)) IN (SELECT DISTINCT lower(compound) FROM tmp_split);

    SELECT COUNT(*) INTO v_afetadas FROM tmp_afetadas;
    SELECT COUNT(*) INTO v_mismatch FROM tmp_afetadas WHERE qtd_atual IS DISTINCT FROM qtd_partes;

    FOR r IN SELECT * FROM tmp_afetadas
              WHERE qtd_atual IS DISTINCT FROM qtd_partes
              ORDER BY id
    LOOP
        RAISE NOTICE 'Quantidade ajustada: carta #% "%" (%) tinha % e as partes somam % — "%"',
            r.id, r.card_name, r.set_code, r.qtd_atual, r.qtd_partes, r.composta;
    END LOOP;

    -- 4. Cria as linhas das partes 2..n (ainda com as cartas apontando para a
    --    localização composta, por isso este passo vem antes do UPDATE).
    INSERT INTO collection_card (card_number, card_name, set_code, set_name_raw, foil,
                                 card_type, language, quantity, price, comentario, location_id)
    SELECT cc.card_number, cc.card_name, cc.set_code, cc.set_name_raw, cc.foil,
           cc.card_type, cc.language, s.qty, cc.price, cc.comentario, tgt.id
      FROM collection_card cc
      JOIN location l    ON l.id = cc.location_id
      JOIN tmp_split s   ON lower(s.compound) = lower(btrim(l.name)) AND s.part > 1
      JOIN location tgt  ON lower(btrim(tgt.name)) = lower(s.target);
    GET DIAGNOSTICS v_criadas = ROW_COUNT;

    -- 5. A linha original fica com a primeira parte.
    UPDATE collection_card cc
       SET location_id = tgt.id,
           quantity    = s.qty
      FROM location l, tmp_split s, location tgt
     WHERE l.id = cc.location_id
       AND lower(s.compound) = lower(btrim(l.name))
       AND s.part = 1
       AND lower(btrim(tgt.name)) = lower(s.target);

    -- 6. Se a divisão gerou uma linha igual a outra que já existia (mesma carta,
    --    set, número, foil, linguagem e localização), soma as quantidades e
    --    mantém uma linha só — o mesmo empilhamento que a tela faz. Restrito às
    --    cartas tocadas por esta migração.
    CREATE TEMP TABLE tmp_merge ON COMMIT DROP AS
    SELECT MIN(cc.id) AS keep_id, SUM(cc.quantity)::INT AS total, array_agg(cc.id) AS ids
      FROM collection_card cc
     WHERE cc.location_id IN (
            SELECT l.id FROM location l
             WHERE lower(btrim(l.name)) IN (SELECT DISTINCT lower(target) FROM tmp_split)
     )
       AND (coalesce(cc.set_code, '') || '|' || coalesce(cc.card_number, '') || '|'
             || lower(coalesce(cc.card_name, '')) || '|' || cc.foil::TEXT || '|'
             || lower(coalesce(cc.language, ''))) IN (SELECT ident FROM tmp_afetadas)
     GROUP BY cc.location_id,
              coalesce(cc.set_code, ''),
              coalesce(cc.card_number, ''),
              lower(coalesce(cc.card_name, '')),
              cc.foil,
              lower(coalesce(cc.language, ''))
    HAVING COUNT(*) > 1;

    UPDATE collection_card cc
       SET quantity = m.total
      FROM tmp_merge m
     WHERE cc.id = m.keep_id;

    DELETE FROM collection_card cc
     USING tmp_merge m
     WHERE cc.id = ANY(m.ids)
       AND cc.id <> m.keep_id;
    GET DIAGNOSTICS v_merged = ROW_COUNT;

    -- 7. Localizações compostas saem do catálogo (nenhuma carta aponta mais
    --    para elas — se ainda apontasse, a migração aborta em vez de deixar
    --    dado órfão).
    IF EXISTS (
        SELECT 1 FROM collection_card cc
          JOIN location l ON l.id = cc.location_id
         WHERE lower(btrim(l.name)) IN (SELECT DISTINCT lower(compound) FROM tmp_split)
    ) THEN
        RAISE EXCEPTION 'Ainda há cartas apontando para localizações compostas — migração abortada.';
    END IF;

    DELETE FROM location l
     WHERE lower(btrim(l.name)) IN (SELECT DISTINCT lower(compound) FROM tmp_split);
    GET DIAGNOSTICS v_removed = ROW_COUNT;

    RAISE NOTICE 'Divisão concluída: % carta(s) afetada(s), % linha(s) criada(s), % linha(s) somada(s) em outra, % localização(ões) composta(s) removida(s), % ajuste(s) de quantidade.',
        v_afetadas, v_criadas, v_merged, v_removed, v_mismatch;

    -- 8. Aviso sobre outros nomes que ainda carregam quantidade entre
    --    parênteses e não estavam na lista acima.
    FOR r IN SELECT l.name, COUNT(cc.id) AS cartas
               FROM location l
               LEFT JOIN collection_card cc ON cc.location_id = l.id
              WHERE l.name ~ '\(\s*\d+\s*\)'
              GROUP BY l.name
              ORDER BY l.name
    LOOP
        RAISE NOTICE 'Atenção: localização ainda com quantidade no nome: "%" (% carta(s))',
            r.name, r.cartas;
    END LOOP;
END $$;

COMMIT;
