# SzafkaSkyPvP — Paper 1.21.4

Wersja konfiguracji dla trybu **SkyPvP**.

## Budowanie
Wymagania: Java 21, Maven, Paper 1.21.4.
W folderze projektu:
```bash
mvn clean package
```
JAR: `target/SzafkaSkyPvP-1.0.0.jar` — wrzuć do `plugins/` odpowiedniego serwera.

## GUI i komunikaty
- `/szafka`: 54 sloty; kostiumy na dole po lewej, pety po prawej, sklep/przedłużenia pośrodku.
- Po założeniu: zielony tytuł na środku ekranu, np. `Założono kostium Kostium Koksa` lub `Założono peta Pszczółka`.
- Po zdjęciu: czerwony tytuł na środku ekranu, np. `Zdjąłeś kostium Kostium Koksa` lub `Zdjąłeś peta Pszczółka`.

## Komendy
`/kosmetyki give <gracz> <kostium|pet> <id> <dni>`
`/kosmetyki extend <gracz> <kostium|pet> <id> <dni>`
`/kosmetyki coins <gracz> <kwota>`
`/kosmetyki crate <gracz> <kostium|pet>`
`/kosmetyki reload`

Uwaga: to projekt źródłowy, nie skompilowany JAR. Nie uruchamiałem kompilacji w tym środowisku. Wygląd kostiumu/peta jest obecnie prototypowy i używa wyświetlanego przedmiotu; pełne modele wymagają resource packa i dalszej implementacji.
