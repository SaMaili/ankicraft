# AnkiCraft

A **Minecraft Fabric mod (MC 26.3)** that syncs live with your **Anki decks** and quizzes you while you play — inspired by LinguaCraft, built for **computer science, math, and language flashcards** (including **LaTeX**, **code blocks**, **images**, and **Furigana**).

Works in **Singleplayer**, on **modded servers** (with XP & potion buff rewards), and as a **client-side-only mod on third-party Vanilla servers**.

---

## Features

- **Live Anki scheduling (via AnkiConnect):** Uses Anki’s review queue, including new-card limits, card order, and sibling burying. Each rating is confirmed by Anki before the quiz advances.
- **Local cache:** Deck listings and media are cached in `config/ankicraft/`. Reviews require a live connection to Anki. Pending ratings from older versions stay in the cache for manual reconciliation.
- **Offline LaTeX Rendering:** Renders inline and display LaTeX (`$...$`, `$$...$$`, `\(...\)`, `\[...\]`, `[latex]...[/latex]`, `\R`, `\N`, `\Z`, `\Q`, `\C`, `align`, etc.) directly via the bundled **JLaTeXMath** library.
- **Rich Card Content:**
  - Syntax-styled boxes for `<pre>` / `<code>` blocks
  - Images (`PNG`, `JPG`, `GIF`, `WebP`, `data:image/...;base64`)
  - Cloze deletions
  - **Furigana** (`<ruby>...<rt>...</rt></ruby>` and `漢字[かな]`) rendered directly above Kanji, including JS-injected card templates
- **In-Game Deck Selector:** Searchable, collapsible deck tree with multi-select, parent-to-subdeck cascade selection, due counts, and full mouse & keyboard navigation.
- **Flexible Triggers & Gamification:**
  - Configurable timer, block-break trigger, and bed/sleep trigger
  - Voluntary study via **`K`** (continuous quiz until you press `K` again; does **not** reset your timer by default)
  - Optional XP & potion buff rewards (Haste, Speed, Regeneration), optional penalties, and optional gatekeeping mode

---

## Installation & Setup

### 1. Requirements
- **Minecraft:** `26.3`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** `0.165.2+26.3` (or newer for `26.3`)
- **Java:** `25`
- **Anki Desktop** with an **AnkiConnect** version that supports the `/api/v1` scheduler API:
  1. In Anki: `Tools` → `Add-ons` → `Get Add-ons...`
  2. Enter code **`2055492159`** and install.
  3. **Restart Anki Desktop once** so AnkiConnect starts listening on `http://127.0.0.1:8765`.

### 2. Install the Mod
1. Place `ankicraft-1.0.0.jar` into your `.minecraft/mods/` folder (or PrismLauncher *Mods* tab).
2. Launch Minecraft while Anki Desktop is running in the background. AnkiCraft selects the study deck in Anki Desktop. If you select multiple deck trees, AnkiCraft checks them in deck-name order and follows Anki’s queue within each tree.

---

## Controls & Keybindings

| Key | Action |
| :--- | :--- |
| **`K`** | Open / close quiz (continuous mode until `K` or `Esc`) |
| **`J`** | Open deck selector (mouse, `↑`/`↓` to navigate, `←`/`→` to collapse/expand, `Enter`/`Space` to toggle) |
| **`O`** | Open AnkiCraft settings |
| **`Space` / `Enter`** | Reveal answer |
| **`1` / `2` / `3` / `4`** | Rate card: *Again / Hard / Good / Easy* (`Space` also rates *Good*) |
| **`Ctrl+Z` / `Ctrl+Y`** | Undo the last deck selection change in the deck selector |

Undo a recorded rating in Anki Desktop.

---

## Build from Source

```bash
git clone https://github.com/SaMaili/ankicraft.git
cd ankicraft
./gradlew build
```

The compiled mod JAR (with JLaTeXMath and WebP-ImageIO bundled) will be located at:
`build/libs/ankicraft-1.0.0.jar`

---

## License

MIT License
