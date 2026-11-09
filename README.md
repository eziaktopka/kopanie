# AutoKopanie (Fabric 1.21.4)

Mod kliencki: automatycznie kopie 2 generatory (tylko drewno z netheru + brzoza)
i sam wpisuje `/kopanie <kod>`, gdy kod pojawi sie na ekranie (czat, actionbar, title lub subtitle).

## Budowanie jara
1. Zainstaluj Java 21 (np. Temurin 21).
2. Rozpakuj zip, kliknij dwukrotnie `zbuduj.bat`.
3. Gotowy plik: `build\libs\autokopanie-1.0.0.jar` -> wrzuc do `.minecraft\mods` razem z Fabric API.

## Ustawienie generatorow (raz)
Patrzac na blok (celownik) wpisz:
- `/ak gen1 a` - jeden rog pierwszego generatora
- `/ak gen1 b` - przeciwlegly rog pierwszego generatora
- `/ak gen2 a` i `/ak gen2 b` - to samo dla drugiego generatora

Ustawienia zapisuja sie w `config/autokopanie.json`.

## Uzywanie
- Klawisz **8** (do zmiany w Sterowanie) albo `/ak start` / `/ak stop` / `/ak toggle`
- Stan: `/ak status`, wyczyszczenie generatorow: `/ak clear`
- Stan sie obok generatora tak, zeby drewno bylo w zasiegu reki (ok. 4,5 bloku); mod sam patrzy na bloki i je kopie.
- Trzymaj w rece odpowiednie narzedzie (siekiera).

## Config (`config/autokopanie.json`)
- `blocks` - lista kopanych blokow (domyslnie crimson/warped stem, hyphae, planks + brzoza)
- `minDelayMs` / `maxDelayMs` - opoznienie przed wpisaniem `/kopanie <kod>`
- `resumeAfterMs` - po jakim czasie od wpisania komendy kopanie rusza dalej
- `reach` - zasieg kopania
