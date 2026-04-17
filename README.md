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

## Futuro front-end

O projeto já está preparado para receber um front React em `frontend/` (ignorado no `.gitignore`). Basta criar o projeto com Vite/Next e apontar para `http://localhost:8080/api`.
