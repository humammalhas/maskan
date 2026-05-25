@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d C:\Users\asus\Desktop\PrivacyAI
call .\gradlew clean assembleRelease > build_log.txt 2>&1
