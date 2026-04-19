@echo off
REM ============================================================================
REM MTGCollection launcher (Windows).
REM
REM Runs the packaged fat jar against PostgreSQL (the "prod" profile, which is
REM the default inside MtgCollectionApplication). To use the bundled H2
REM in-memory database instead, either:
REM
REM   1. Set SPRING_PROFILES_ACTIVE=h2 before calling this script, or
REM   2. Launch the H2-specific main class directly, e.g.:
REM      java -cp mtgcollection.jar -Dloader.main=com.evaristof.mtgcollection.MtgCollectionH2Application org.springframework.boot.loader.launch.PropertiesLauncher
REM
REM You can override any individual Spring property via environment variables,
REM for example:
REM   set SPRING_DATASOURCE_URL=jdbc:postgresql://192.168.0.10:5432/mtgdb
REM   set SPRING_DATASOURCE_USERNAME=myuser
REM   set SPRING_DATASOURCE_PASSWORD=mypass
REM   start.bat
REM ============================================================================

setlocal

pushd "%~dp0"

REM Sanity check: Java 21+ must be on PATH.
java -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo [ERROR] Java nao encontrado no PATH. Instale o JDK 21 ^(https://adoptium.net/^) e tente de novo.
    echo.
    pause
    exit /b 1
)

REM Force IPv4 to avoid "Cannot assign requested address" on some Windows
REM setups with broken IPv6 (see README.md troubleshooting section).
java ^
    -Djava.net.preferIPv4Stack=true ^
    -Djava.net.preferIPv4Addresses=true ^
    -jar mtgcollection.jar %*

popd
endlocal
