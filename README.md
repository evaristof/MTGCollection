# MTGCollection

Gestão de uma coleção pessoal de Magic: The Gathering, com back-end Spring Boot + H2 e front-end React + Vite. Integra com a [Scryfall API](https://scryfall.com/docs/api).

## Stack

**Back-end**
- Java 21
- Spring Boot 3.2 (Web + Data JPA + Validation)
- Hibernate
- H2 (banco em memória)
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
| GET    | `/api/prices/by-name?name=...&set=...&foil=true\|false`    | Preço USD pelo nome exato e código da coleção                              |
| GET    | `/api/prices/by-number?set=...&number=...&foil=true\|false`| Preço USD pelo código da coleção e número do collector                     |
| GET    | `/api/cards/by-name?name=...&set=...`                      | Objeto completo da carta no Scryfall (inclui `collector_number`, `type_line`, `prices`...) |

### Coleção

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| POST   | `/api/collection/cards`                                    | Adiciona carta à coleção (corpo: `card_name`, `set_code`, `foil`, `language`, `quantity`) |
| GET    | `/api/collection/cards[?set=...]`                          | Lista as cartas da coleção (opcional filtro por set)                       |
| GET    | `/api/collection/cards/{id}`                               | Busca uma carta da coleção pelo id                                         |
| PUT    | `/api/collection/cards/{id}`                               | Atualiza qty / foil / language (e opcionalmente name / set)                |
| DELETE | `/api/collection/cards/{id}`                               | Remove a carta da coleção                                                  |

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

## Front-end

O front-end fica em [`frontend/`](./frontend) (React + Vite + TS). A UI traz um menu superior e duas telas de CRUD para gerenciar a coleção:

- **Sets** — listar, criar, alterar, deletar + botão para sincronizar do Scryfall
- **Cartas** — listar, adicionar (busca automática no Scryfall pra popular número/tipo), alterar (`foil`/`language`/`quantity`), deletar, filtrar por set
