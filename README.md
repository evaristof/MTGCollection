# MTGCollection

Back-end para gestão de uma coleção de Magic: The Gathering, com integração à [Scryfall API](https://scryfall.com/docs/api).

Esta primeira versão possui apenas o **back-end**, já estruturado para futuramente ser consumido por um front-end em **React** (endpoints sob `/api/**` e CORS liberado para `localhost:3000`/`localhost:5173`).

## Stack

- Java 17
- Spring Boot 3.2 (Web + Data JPA)
- Hibernate
- H2 (banco em memória)
- GSON (parsing das respostas do Scryfall)
- JUnit 5 + Mockito + AssertJ

## Como rodar

```bash
mvn spring-boot:run
```

A aplicação sobe em `http://localhost:8080`. O console H2 fica em `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:mtgcollection`).

## Como testar

```bash
mvn test
```

## Endpoints principais

| Método | Rota                                                       | Descrição                                                                  |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| GET    | `/api/sets`                                                | Lista todas as coleções (direto do Scryfall)                               |
| POST   | `/api/sets/sync`                                           | Busca as coleções no Scryfall e grava no banco em memória                  |
| GET    | `/api/prices/by-name?name=...&set=...&foil=true\|false`    | Preço USD da carta pelo nome exato e código da coleção                     |
| GET    | `/api/prices/by-number?set=...&number=...&foil=true\|false`| Preço USD da carta pelo código da coleção e número do collector            |

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

Tabela `COLLECTION_CARD` (cartas da coleção do usuário):

| Coluna        | Tipo      | Origem                                     |
|---------------|-----------|--------------------------------------------|
| ID            | PK        | auto-gerado                                |
| CARD_NUMBER   | VARCHAR   | `collector_number` do Scryfall             |
| CARD_NAME     | VARCHAR   | parâmetro (`card_name`) / `name` Scryfall  |
| SET_CODE      | VARCHAR   | parâmetro (`set_code`) / `set` Scryfall    |
| FOIL          | BOOLEAN   | parâmetro                                  |
| CARD_TYPE     | VARCHAR   | `type_line` do Scryfall                    |
| LANGUAGE      | VARCHAR   | parâmetro                                  |
| QUANTITY      | INT       | parâmetro                                  |


Scaffolding inicial em **React + Vite + TypeScript** em [`frontend/`](./frontend).

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173 (com proxy /api -> http://localhost:8080)
npm run build
npm run lint
```

- O `vite.config.ts` já tem proxy de `/api` para o backend em `http://localhost:8080`, então no modo dev basta subir os dois processos em paralelo.
- Em produção, defina a variável `VITE_API_BASE_URL` apontando para a URL absoluta do backend.
- A tela inicial consome `GET /api/sets` e lista os sets retornados pelo Scryfall.
