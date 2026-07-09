@echo off
setlocal

REM 切到仓库根（本脚本位于 deploy\），保证 mvn 反应堆与 compose 相对路径正确
cd /d "%~dp0.."

mvn -pl collection-admin -am clean package -DskipTests
if errorlevel 1 exit /b %errorlevel%

docker compose -f deploy/docker-compose.yml up -d --build --force-recreate
exit /b %errorlevel%
