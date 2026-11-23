# AnkiCraft

Eine **Minecraft Fabric Mod (MC 26.3)**, die sich live mit deinen **Anki-Decks** synchronisiert und dich beim Spielen abfragt – inspiriert von LinguaCraft, optimiert für **Informatik-, Mathe- und Sprach-Karten** (inklusive **LaTeX**, **Codeblöcken**, **Bildern** und **Furigana**).

Funktioniert sowohl im **Singleplayer**, auf **Mod-Servern** (mit XP- & Buff-Belohnungen) als auch als **reine Client-Mod auf fremden Vanilla-Servern**.

---

## Features

- **Live-Sync mit Anki (via AnkiConnect):** Behält dein FSRS/SM-2-Scheduling exakt bei und speichert alle Bewertungen direkt zurück in Anki.
- **Offline-Cache:** Einmal gesyncte Decks, Karten und Bilder werden lokal unter `config/ankicraft/` zwischengespeichert. Bewertungen werden beim nächsten Start von Anki automatisch nachsynchronisiert.
- **LaTeX-Support (offline):** Rendert Inline- und Display-LaTeX (`$...$`, `$$...$$`, `\(...\)`, `\[...\]`, `[latex]...[/latex]`, `\R`, `\N`, `\Z`, `\Q`, `\C`, `align` usw.) direkt über das gebündelte **JLaTeXMath**.
- **Informatik- & Sprach-Karten:**
  - Syntax-Boxen für `<pre>` / `<code>`
  - Bilder (`PNG`, `JPG`, `GIF`, `WebP`, `data:image/...;base64`)
  - Cloze-Lückentexte
  - **Furigana** (`<ruby>...<rt>...</rt></ruby>` sowie `漢字[かな]`) direkt über den Kanji gerendert
- **Deck-Auswahl im Spiel:** Durchsuchbare Deck-Liste mit Unterdecks, Mehrfachauswahl, Anzeige fälliger Karten sowie Maus- und Tastatursteuerung.
- **Flexible Trigger & Gamification:**
  - Konfigurierbarer Timer, Block-Abbau-Trigger und Bett-/Schlaf-Trigger
  - Freiwilliges Lernen per Taste **`K`** (Endlos-Quiz bis zum erneuten `K`; resettet den Timer standardmäßig **nicht**)
  - Optionale XP- & Potion-Belohnungen (Eile, Tempo, Regeneration), optionale Strafen und optionaler Gatekeeping-Modus

---

## Installation & Einrichtung

### 1. Voraussetzungen
- **Minecraft:** `26.3`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** `0.165.2+26.3` (oder neuer für `26.3`)
- **Java:** `25`
- **Anki Desktop** mit dem Add-on **AnkiConnect**:
  1. In Anki: `Extras` → `Erweiterungen` → `Erweiterungen herunterladen...`
  2. Code **`2055492159`** eingeben und installieren.
  3. **Anki Desktop einmal neu starten**, damit AnkiConnect auf `http://127.0.0.1:8765` startet.

### 2. Mod installieren
1. Lade `ankicraft-1.0.0.jar` (oder baue sie selbst, siehe unten) in deinen `.minecraft/mods/`-Ordner (bzw. in PrismLauncher unter *Mods*).
2. Starte Minecraft und stelle sicher, dass Anki Desktop im Hintergrund läuft.

---

## Steuerung & Tastenbelegung

| Taste | Aktion |
| :--- | :--- |
| **`K`** | Quiz öffnen / schließen (Endlos-Modus bis erneut `K` oder `Esc`) |
| **`J`** | Deck-Auswahl öffnen (Maus oder `↑`/`↓` + `Enter`/`Leertaste`) |
| **`O`** | AnkiCraft-Einstellungen öffnen |
| **`Leertaste` / `Enter`** | Antwort aufdecken |
| **`1` / `2` / `3` / `4`** | Karte bewerten: *Nochmal / Schwer / Gut / Einfach* |

---

## Selbst bauen (Build from Source)

```bash
git clone https://github.com/SaMaili/ankicraft.git
cd ankicraft
./gradlew build
```

Die fertige Mod-JAR (inkl. JLaTeXMath & WebP-ImageIO) liegt danach unter:
`build/libs/ankicraft-1.0.0.jar`

---

## Lizenz

MIT License
