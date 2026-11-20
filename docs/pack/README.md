# Matura — pakiet grafiki pixel-art

Kompletny zestaw grafik do gry **Matura** — nauki angielskich słówek na maturę podstawową: potworki, działko, pociski, HUD, ikony, kafle tła i font bitmapowy.

- repozytorium: [github.com/kubakozub/Matura](https://github.com/kubakozub/Matura)
- demo: [kubakozub.github.io/Matura](https://kubakozub.github.io/Matura)
- pakiet: `com.matura.app`

## Co wchodzi do gry

| Plik | Rozmiar | Do czego |
|---|---|---|
| `atlas-8bit.png` | 4,9 kB | **wszystkie 125 sprite'ów w jednym pliku** — to ładuj w grze |
| `atlas.json` | — | mapa `frames[nazwa] = [x, y, w, h]` |
| `font_8x8-8bit.png` | 1.2 kB | arkusz fontu (139 glifów) |
| `font-map.json` | — | geometria i kolejność glifów |
| `9-slice.json` | — | szerokości ramek dla paneli, przycisków, pasków |
| `palette.hex` / `.gpl` / `.json` | — | paleta 32 kolorów (Aseprite / GIMP / kod) |

Razem **6.0 kB** i **2 requesty** zamiast 126.

## Czego NIE wysyłać do produkcji

- `sprites/` — 126 pojedynczych PNG-ów 1×. To źródło: wygodne do podmiany jednego sprite'a, ale w grze zastępuje je atlas.
- `sprites/x4/` — 126 plików 4×, wyłącznie do podglądu w przeglądarce i w dokumentacji.
- `atlas.png` — wersja RGBA atlasu. Identyczna treść jak `atlas-8bit.png`, tylko 8× cięższa.

## Zasady

- **Siatka 16×16 px.** Wyjątki: boss i nakładki celownika 20×20, `armor_plate_large` 18×11, ikony UI i menu 12×12, klawisze i paski wg `9-slice.json`.
- **Paleta 32 kolorów**, kontur zawsze `#101322`. Jedyny kolor spoza palety to cień logotypu `#8FBE51` (przyjęty z aplikacji). Każdy inny piksel w atlasie należy do palety — nie ma antyaliasingu ani półprzejrzystości.
- **Skalowanie tylko nearest-neighbour**: `ctx.imageSmoothingEnabled = false` / `image-rendering: pixelated`. Skala musi być całkowitą krotnością **własnej** wysokości sprite'a (16 px → 32/48/64, 12 px → 24/36/48) — inaczej piksele się rozjadą. Nigdy `width: 100%` na sprite'cie.
- **Nazwy**: `nazwa` = klatka bazowa, `nazwa_f2` = druga klatka chodu, `nazwa_death` = klatka zgonu, `nazwa_2`/`_3` = warianty tego samego kafla, `turret_barrel_00..15` = kierunki lufy.

## Animacje

| Stan | Klatki | Czas |
|---|---|---|
| chód | `X` ↔ `X_f2` | ~200 ms na klatkę |
| pojawienie się | `spawn_f1` → `spawn_f2` → `spawn_f3` → sprite potworka | 80 ms na klatkę |
| trafienie | `hit_flash` na 1 klatkę, potem sprite bazowy | ~60 ms |
| zgon | `X_death` | ~350 ms, potem znika |
| wybuch | `explosion` | ~200 ms |
| działko trafione | `turret_base_hit` | ~80 ms |
| działko zniszczone | `turret_base_wreck` | stałe |

## Lufa działka

16 pre-renderowanych kierunków, `turret_barrel_00` – `turret_barrel_15`, co 22,5°. Kanwa 24×24, oś obrotu w środku kanwy — rysuj klatkę wyśrodkowaną na środku `turret_base`.

```js
const i = Math.round(angleDeg / 22.5) % 16;   // angleDeg: 0 = w prawo, rośnie w dół
const frame = 'turret_barrel_' + String(i).padStart(2, '0');
```

Kierunki w `barrel-dirs.json`. **Nie obracaj jednego sprite'a transformacją** — piksele się rozsypią.

## Font

Glif 5×7 w komórce 16×16, offset (5, 4). Arkusz jest jednokolorowy (`#f4f1e4`), żeby dało się go przebarwiać.

```js
const i = font.order.indexOf(char);
const sx = (i % font.cols) * 16, sy = Math.floor(i / font.cols) * 16;
// kontur: ten sam glif w #101322 przesunięty o (+1, +1), rysowany PRZED literą
```

Zakres: A–Z, a–z, 0–9, polskie `ĄĆĘŁŃÓŚŹŻ ąćęłńóśźż`, niemieckie `ÄÖÜ äöü ß`, hiszpańskie `ÁÉÍÓÚÑ áéíóúñ ¿ ¡`, francuskie `ÀÈÊÎÔÙÛÇ àâçèéêëîïôùû`, interpunkcja `. , ! ? : ; / - _ ' " ( ) + = % < >` oraz `×` i `∞`.

## Rysowanie z atlasu

```js
const atlas = await (await fetch('atlas.json')).json();
const sheet = new Image(); sheet.src = 'atlas-8bit.png';

function drawSprite(ctx, name, dx, dy, scale = 4) {
  const [sx, sy, w, h] = atlas.frames[name];
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(sheet, sx, sy, w, h, dx, dy, w * scale, h * scale);
}
```

## Dostępność

Tryb TRUE/FALSE opiera się na zieleni i czerwieni — dla graczy z deuteranopią te dwa kolory są nierozróżnialne. Zawsze dubluj je kształtem: `checkmark` i `cross` obok koloru, nigdy sam kolor.
