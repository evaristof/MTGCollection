MTGCollection - distribuição portátil
=====================================

Conteúdo do zip
---------------
  mtgcollection.jar   Fat jar Spring Boot com o backend + frontend (React)
                      já embutido. A API fica em /api/** e o SPA é servido
                      em /.
  start.bat           Launcher Windows. Basta dar duplo clique.
  start.sh            Launcher Linux / macOS (chmod +x).
  README-dist.txt     Este arquivo.

Pré-requisitos
--------------
  * JDK 21 ou superior no PATH
      Windows: https://adoptium.net/temurin/releases/?version=21
      macOS:   brew install --cask temurin21
      Linux:   sudo apt-get install openjdk-21-jdk
  * PostgreSQL acessível em localhost:5432 com o banco `mtgdb`
      Docker (jeito rápido):
          docker run -d --name mtg-postgres \
              -e POSTGRES_DB=mtgdb \
              -e POSTGRES_USER=admin \
              -e POSTGRES_PASSWORD=admin \
              -p 5432:5432 \
              postgres:16

Uso básico
----------
  Windows: dois cliques em start.bat
  Linux / macOS: ./start.sh

  A aplicação sobe em http://localhost:8080
  O frontend React fica em http://localhost:8080/
  A API REST fica em http://localhost:8080/api/...

Configurações flexíveis
-----------------------
Por padrão a aplicação usa o perfil `prod` (PostgreSQL em localhost:5432,
database `mtgdb`, usuário `admin`, senha `admin`).

Você pode trocar qualquer coisa via variáveis de ambiente — elas sempre
têm prioridade sobre o que está gravado dentro do jar:

  SPRING_DATASOURCE_URL=jdbc:postgresql://192.168.0.10:5432/mtgdb
  SPRING_DATASOURCE_USERNAME=meu_user
  SPRING_DATASOURCE_PASSWORD=minha_senha
  SERVER_PORT=9090

Ou via argumentos de linha de comando passados pro launcher, por exemplo:

  start.bat --spring.datasource.url=jdbc:postgresql://db:5432/outro

Rodar sem PostgreSQL (modo H2 em memória)
-----------------------------------------
Útil para demo / smoke test. Os dados somem quando o processo encerra.

  Windows:
      set SPRING_PROFILES_ACTIVE=h2
      start.bat

  Linux / macOS:
      SPRING_PROFILES_ACTIVE=h2 ./start.sh

Logs
----
O processo loga diretamente no terminal. Se quiser redirecionar:

  Windows: start.bat > mtg.log 2>&1
  Linux/macOS: ./start.sh > mtg.log 2>&1
