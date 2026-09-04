# MTGCollection

Gestão de uma coleção pessoal de Magic: The Gathering, com back-end Spring Boot + H2 e front-end React + Vite. Integra com a [Scryfall API](https://scryfall.com/docs/api).

## Stack

**Back-end**
- Java 21
- Spring Boot 3.2 (Web + Data JPA + Validation)
- Hibernate
- H2 para Testes (banco em memória)
- Posgresql para Produção
- MinIO para armazenar imagens
- GSON (parsing das respostas do Scryfall)
- JUnit 5 + Mockito + AssertJ

**Front-end**
- React 19 + Vite + TypeScript
- React Router DOM
- CSS minimalista (sem libs de UI — podemos trocar por Mantine/Chakra depois)

## Estrutura do repositório

```
.
├── backend/     # Spring Boot (pom.xml + src/)
└── frontend/    # React + Vite + TypeScript
```

## Como rodar

```bash
# backend
cd backend
mvn spring-boot:run

# frontend (em outro terminal)
cd frontend
npm install
npm run dev     # http://localhost:5173 (com proxy /api -> http://localhost:8080)
```

A aplicação Java sobe em `http://localhost:8080`. O console H2 fica em `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:mtgcollection`).

Em produção do front, defina `VITE_API_BASE_URL` apontando para a URL absoluta do backend.

## Troubleshooting — `Cannot assign requested address: getsockopt`

Em algumas máquinas (principalmente Windows) o JVM falha ao abrir sockets TCP com:

```
Cannot assign requested address: getsockopt
```

É sintoma de IPv6 quebrado. Aparece em dois momentos diferentes:

1. **Durante `mvn …`** (resolvendo dependências) — resolve via `MAVEN_OPTS` (ver abaixo).
2. **Durante a aplicação rodando** (ex.: ao clicar "Sincronizar do Scryfall") — a aplicação já força IPv4 no `main()` e no plugin `spring-boot-maven-plugin`, então `mvn spring-boot:run` e `java -jar target/…` já sobem com IPv4. Se você rodar pela **IDE** (IntelliJ / Eclipse), adicione os mesmos flags em *VM options* da Run Configuration:
   ```
   -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true
   ```
   No IntelliJ: `Run → Edit Configurations… → MtgCollectionApplication → VM options`.

### `MAVEN_OPTS` (para builds no Windows)

**Via GUI (Variáveis de Ambiente):**
1. Menu Iniciar → *"Editar as variáveis de ambiente do sistema"*
2. Botão **"Variáveis de Ambiente…"**
3. Em *"Variáveis de usuário"* → **Novo…**
   - **Nome:** `MAVEN_OPTS`
   - **Valor:** `-Djava.net.preferIPv4Stack=true`
4. OK em tudo → **feche e reabra** o terminal / a IDE
5. Confirmar: `echo %MAVEN_OPTS%` deve imprimir `-Djava.net.preferIPv4Stack=true`

**Via PowerShell (equivalente, persistente):**
```powershell
setx MAVEN_OPTS "-Djava.net.preferIPv4Stack=true"
```

> `MAVEN_OPTS` não precisa ir no `PATH` — são variáveis independentes. Se você já tem um `MAVEN_OPTS` (ex.: `-Xmx2g`), concatene: `-Xmx2g -Djava.net.preferIPv4Stack=true`.

### `MAVEN_OPTS` (Linux / macOS)

```bash
export MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"
# adicione a linha acima ao seu ~/.bashrc / ~/.zshrc pra ficar permanente
```

Depois rode novamente:

```bash
mvn -U clean install
```

O `-U` força o Maven a tentar baixar as dependências que falharam antes.

## Como testar

```bash
cd backend && mvn test                         # back-end
cd frontend && npm run lint && npm run build   # front-end
```

## Endpoints

### Sets

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| GET    | `/api/sets`                                                | Lista todas as coleções (direto do Scryfall)                               |
| POST   | `/api/sets/sync`                                           | Busca as coleções no Scryfall e grava no H2                                |
| GET    | `/api/sets/db`                                             | Lista os sets persistidos no H2                                            |
| GET    | `/api/sets/db/{code}`                                      | Busca um set pelo código                                                   |
| POST   | `/api/sets/db`                                             | Cria um set manualmente (409 se o código já existir)                       |
| PUT    | `/api/sets/db/{code}`                                      | Atualiza um set existente                                                  |
| DELETE | `/api/sets/db/{code}`                                      | Remove um set                                                              |

### Preços & Busca

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| GET    | `/api/prices/by-name?name=...&set=...&foil=true\|false`    | Preço pelo nome exato e código da coleção (resposta traz `price` e `currency`) |
| GET    | `/api/prices/by-number?set=...&number=...&foil=true\|false`| Preço pelo código da coleção e número do collector (idem)                  |

**Preço de foil sem `usd_foil`:** nem toda carta foil tem cotação na chave "normal" do Scryfall, então o preço do foil segue uma cadeia:

1. `usd_foil` — o caso comum, sem marca nenhuma;
2. `usd_etched` — foil etched, cotada só nessa chave: usa o valor (em dólar) e marca `Carta Foil Etched` no comentário;
3. `eur_foil` — sem dólar nenhum: usa o euro **sem conversão** e marca `Preço Foil em EUR`, para o número nunca ser lido como dólar sem aviso.

As marcas são gerenciadas: entram no fim do comentário (preservando o que você escreveu), não duplicam, trocam entre si e somem sozinhas conforme a fonte do preço muda de uma sincronização para outra. Vale em toda resolução de preço — adicionar carta, sincronizar, importar planilha e a consulta do Cadastro Cartas, cuja resposta traz `price`, `currency` e `note`. Cartas não-foil sem `usd` continuam sem preço (o buraco é só do foil).
| GET    | `/api/cards/by-name?name=...&set=...`                      | Objeto completo da carta no Scryfall (inclui `collector_number`, `type_line`, `prices`...) |

### Coleção

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| POST   | `/api/collection/cards`                                    | Adiciona carta à coleção (corpo: `card_name`, `set_code`, `foil`, `language`, `quantity`, `localizacao`/`location_id`, `card_number`). Se já existir uma linha com o mesmo set + número (ou nome) + foil + linguagem + localização, **soma na quantidade** em vez de criar outra |
| GET    | `/api/collection/cards[?set=...]`                          | Lista as cartas da coleção (opcional filtro por set)                       |
| GET    | `/api/collection/cards/{id}`                               | Busca uma carta da coleção pelo id                                         |
| PUT    | `/api/collection/cards/{id}`                               | Atualiza qty / foil / language (e opcionalmente name / set)                |
| DELETE | `/api/collection/cards/{id}`                               | Remove a carta da coleção                                                  |
| GET    | `/api/collection/export`                                    | Baixa a coleção inteira como .xlsx no mesmo layout que o importador lê (set pelo nome, foil como Sim/Não, coluna H com a fórmula preço × quantidade) |

### Reconciliação

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|------------------------------------------------------------------------------|
| POST   | `/api/collection/reconcile`                                 | Recebe a planilha (multipart, campo `file`) e devolve as diferenças contra a coleção: `only_in_excel`, `only_in_collection`, `quantity_mismatch`, `ignored` e um `summary`. Somente leitura — nada é gravado |

Comparação por **set + nome + foil + idioma + localização**. O número do coletor fica fora da chave porque a coluna é opcional na planilha (incluí-lo faria a mesma carta aparecer como faltando dos dois lados). Idiomas passam por um normalizador (`en` = `English`, `Espanhol` = `Spanish`…), sets são casados pelo código (caindo para o nome quando o set não está em `MAGIC_SET`), linhas repetidas da mesma chave são somadas dos dois lados, e a localização no formato `Pasta A (3) e Pasta B (5)` é expandida em uma entrada por pasta. Só as localizações citadas na planilha entram na comparação, para uma planilha parcial não listar o resto da coleção como sobra.

### Localizações

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|------------------------------------------------------------------------------|
| GET    | `/api/locations`                                           | Lista as localizações cadastradas (ordenadas por nome)                     |
| POST   | `/api/locations`                                            | Cria uma localização (corpo: `name`, `description`; 409 se o nome já existir) |
| PUT    | `/api/locations/{id}`                                       | Atualiza nome/descrição de uma localização                                 |
| DELETE | `/api/locations/{id}`                                       | Remove uma localização (409 enquanto houver cartas apontando para ela)     |

As telas mandam a localização por **nome** (`localizacao`) tanto ao adicionar quanto ao editar uma carta; o backend resolve no catálogo `LOCATION` e cadastra na primeira vez que um nome aparece — por isso digitar uma localização nova em qualquer tela continua funcionando. `location_id` é aceito quando o cliente já sabe o id (422 se o id não existir), e `localizacao: ""` limpa a localização da carta.

### Catálogo de cartas (autocomplete da tela "Cadastro Cartas")

Sourced from `CARD_IMAGE_HASH` (o catálogo de referência populado pela sincronização do scanner) — não faz chamadas ao Scryfall, então continua rápido ao cadastrar centenas de cartas em sequência.

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|------------------------------------------------------------------------------|
| GET    | `/api/card-catalog/names`                                   | Todos os nomes de carta distintos (autocomplete do campo Nome)             |
| GET    | `/api/card-catalog/sets?name=...`                            | Sets em que a carta foi impressa (filtra o combo de Set)                   |
| GET    | `/api/card-catalog/lookup-number?set=...&number=...`         | Nome da carta impressa em (set, número) — preenche o campo Nome pelo número |
| GET    | `/api/card-catalog/resolve-number?set=...&name=...`          | Um número de coleção para (set, nome) — usado na prévia da imagem          |

## Estrutura do banco

Tabela `MAGIC_SET`:

| Coluna        | Tipo      | Origem no JSON do Scryfall |
|---------------|-----------|----------------------------|
| SET_CODE      | PK        | `code`                     |
| SET_NAME      | VARCHAR   | `name`                     |
| RELEASE_DATE  | DATE      | `released_at`              |
| SET_TYPE      | VARCHAR   | `set_type`                 |
| CARD_COUNT    | INT       | `card_count`               |
| PRINTED_SIZE  | INT       | `printed_size`             |
| BLOCK_CODE    | VARCHAR   | `block_code`               |
| BLOCK_NAME    | VARCHAR   | `block`                    |

Tabela `COLLECTION_CARD`:

| Coluna        | Tipo      | Origem                                     |
|---------------|-----------|--------------------------------------------|
| ID            | PK        | auto-gerado                                |
| CARD_NUMBER   | VARCHAR   | `collector_number` do Scryfall             |
| CARD_NAME     | VARCHAR   | parâmetro / `name` do Scryfall             |
| SET_CODE      | VARCHAR   | parâmetro / `set` do Scryfall              |
| FOIL          | BOOLEAN   | parâmetro                                  |
| CARD_TYPE     | VARCHAR   | `type_line` do Scryfall                    |
| LANGUAGE      | VARCHAR   | parâmetro                                  |
| QUANTITY      | INT       | parâmetro                                  |
| PRICE         | NUMERIC   | `prices.usd` / `prices.usd_foil`           |
| COMENTARIO    | VARCHAR   | parâmetro                                  |
| LOCATION_ID   | FK        | → `LOCATION.ID` (era a coluna livre `LOCALIZACAO`) |

Tabela `LOCATION`:

| Coluna        | Tipo      | Origem                                     |
|---------------|-----------|--------------------------------------------|
| ID            | PK        | auto-gerado                                |
| NAME          | VARCHAR   | parâmetro (único)                          |
| DESCRIPTION   | VARCHAR   | parâmetro (opcional)                       |

### Migração da localização (PostgreSQL)

A localização das cartas era a coluna livre `COLLECTION_CARD.LOCALIZACAO` e agora é a FK `LOCATION_ID`. O Hibernate (`ddl-auto=update`) cria a tabela `LOCATION` e a coluna `LOCATION_ID` sozinho ao subir, mas **não** migra os dados nem remove a coluna antiga — para isso rode uma única vez:

```bash
psql -h localhost -U admin -d mtgdb \
     -f backend/src/main/resources/db/migration/V4__migrate_localizacao_to_location_fk.sql
```

O script cria uma localização por valor distinto já usado (com `TRIM`, agrupando maiúsculas/minúsculas), vincula cada carta à sua localização, cria a FK + índice e só então remove `LOCALIZACAO` — abortando se alguma carta ficasse sem vínculo. É idempotente: rodar de novo depois da migração não faz nada. `COLLECTION_CARD_DATA_DUMP.LOCALIZACAO` continua texto livre de propósito, porque snapshot é histórico e não deve mudar quando uma localização é renomeada.

### Cartas divididas em duas pastas (PostgreSQL)

Na planilha antiga, cópias da mesma carta em pastas diferentes eram uma linha só, com a quantidade de cada pasta entre parênteses (`Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)`). Para transformar isso em uma linha por pasta:

```bash
psql -h localhost -U admin -d mtgdb \
     -f backend/src/main/resources/db/migration/V5__split_multi_location_cards.sql
```

Roda depois da V4. Cada carta com localização composta vira duas linhas, com a quantidade que estava entre parênteses; se a divisão bater com uma linha que já existia (mesma carta, set, número, foil, linguagem e pasta), as quantidades são somadas numa linha só; as localizações compostas somem do catálogo. As quantidades entre parênteses são a fonte da verdade — se a soma delas for diferente da `QUANTITY` que a carta tinha, o script avisa linha a linha (`NOTICE`) antes de aplicar. Também é idempotente, e avisa sobre qualquer outra localização que ainda tenha quantidade no nome (para incluir novos casos, basta acrescentar as linhas correspondentes no `INSERT INTO tmp_split` do script).

## Front-end

O front-end fica em [`frontend/`](./frontend) (React + Vite + TS). A UI traz um menu superior e duas telas de CRUD para gerenciar a coleção:

- **Sets** — listar, criar, alterar, deletar + botão para sincronizar do Scryfall
- **Cartas** — listar, adicionar (busca automática no Scryfall pra popular número/tipo), alterar (`foil`/`language`/`quantity`), deletar, filtrar por set, importar e **exportar** a coleção na planilha do template
- **Cadastro Cartas** — cadastro rápido em lote: nome com autocomplete (baseado em `CARD_IMAGE_HASH`), set filtrado pela carta escolhida, número opcional (preenche o nome), linguagem, foil, localização (autocomplete com opção de digitar uma nova) e prévia da imagem ao passar o mouse sobre nome/número, com zoom pelo scroll (mesmo tooltip da tela Cartas). "Adicionar" empilha a carta numa lista local editável — já com o preço consultado no Scryfall — e "Adicionar à coleção" grava tudo de uma vez, somando na quantidade quando a carta já existe na mesma localização
- **Cadastro de Localização** — CRUD simples (nome + descrição) das localizações físicas usadas no autocomplete acima
- **Reconciliação Coleção** — importa a mesma planilha do "Importar coleção" e mostra três painéis de diferenças (só na planilha, só na base, quantidades diferentes), com ação por linha: cadastrar, apagar ou equalizar para a quantidade escolhida. A análise não grava nada; cada diferença é aplicada por você
