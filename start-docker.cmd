@echo off
setlocal

mvn -pl collection-admin -am clean package -DskipTests
if errorlevel 1 exit /b %errorlevel%

docker compose up -d --build --force-recreate
exit /b %errorlevel%
