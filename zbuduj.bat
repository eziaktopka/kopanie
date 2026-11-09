@echo off
echo Budowanie moda AutoKopanie... (pierwszy raz moze potrwac kilka minut, potrzebny jest internet i Java 21)
call gradlew.bat build
if errorlevel 1 (
  echo.
  echo BLAD budowania - przepisz co wyswietlilo sie powyzej.
  pause
  exit /b 1
)
echo.
echo GOTOWE! Plik moda: build\libs\autokopanie-1.0.0.jar
echo Wrzuc go do folderu .minecraft\mods (razem z Fabric API).
start "" build\libs
pause
