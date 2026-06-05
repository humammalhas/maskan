@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
call .\gradlew clean assembleRelease > build_log.txt 2>&1
