# Planificación: Mod estilo Balatro para Cobblemon (Fabric 1.21.1)

## 1. Idea general
Crear un mod en **Kotlin para Fabric 1.21.1** (`desgraciamon-balatro`) que porte las mecánicas, fórmulas y bucle jugable de *Balatro* directamente dentro de Minecraft, utilizando **assets propios** (texturas, sprites de cartas/comodines, sonidos y UI) e integración opcional con el servidor de Cobblemon.

- **Referencia de mecánicas y fórmulas (Lua/LÖVE2D):** `https://github.com/balatro-src/balatro-src-reverse-engineering`
- **Enfoque:** Portar la lógica del juego a Kotlin nativo (sin emular LÖVE2D), corriendo las reglas y validación en el **servidor** para evitar trampas, y renderizando una **GUI interactiva** propia en el **cliente**.

---

## 2. Arquitectura técnica recomendada

### Servidor (lógica autoritativa + persistencia)
- **Motor de partida (`BalatroGameEngine`):**
  - Mazo de 52 cartas (ampliable/modificable por partida), mano de 8 cartas, descartes y manos restantes por ronda.
  - Evaluador exacto de manos de póker (Carta Alta, Pareja, Doble Pareja, Trío, Escalera, Color, Full House, Póker, Escalera de Color, Five of a Kind, Flush House, Flush Five).
  - Pipeline de puntuación `Chips × Mult` respetando el orden:
    1. Puntuación base de la mano (+ nivel de Planeta).
    2. Evaluación carta por carta jugada (Chips base, Mejoras, Ediciones, Sellos, triggers de Jokers).
    3. Cartas que quedan en mano (ej. Acero / Reina en mano).
    4. Evaluación izquierda → derecha de los **Jokers** activos (Chips, `+Mult`, `×Mult`).
- **Progresión y Blinds:**
  - *Antes* 1 a 8 (y modo *Endless* opcional), cada uno con *Small Blind*, *Big Blind* y *Boss Blind* (con modificadores de jefe).
  - Economía: recompensa por blind, manos sobrantes, interés ($1 por cada $5, hasta $25) y opción de *Skip Blind* con etiquetas (*Tags*).
- **Tienda entre rondas:**
  - 2 ranuras de ítems (Jokers / Tarots / Planetas), 1–2 Booster Packs y 1 Voucher por Ante, más botón de *Reroll* con costo incremental.
- **Sincronización y guardado:**
  - Payloads S2C/C2S (`BalatroStatePayload`, `BalatroActionPayload`) para enviar el estado actual al cliente cada vez que el jugador juega una mano, descarta, compra o reordena Jokers.
  - Guardado en `data/desgraciamon_balatro/` (partida activa por UUID + récords históricos).

### Cliente (GUI + Assets propios)
- **Pantalla `BalatroScreen`:**
  - Mesa estilo tapete oscuro/neón, cartas clicables (hasta 5 seleccionadas) con elevación al seleccionar/hover, botones de **Jugar Mano** y **Descartar**, y ordenamiento por Rango o Palo.
  - Barra superior de **Jokers** (arrastrables/reordenables para optimizar el orden de `×Mult`) y consumibles (Tarot/Planeta/Espectral).
  - Panel izquierdo con Blind actual, puntaje objetivo vs puntaje acumulado, `Chips × Mult` animado, manos/descartes restantes, dinero y Ante/Ronda.
- **Assets propios (sin usar archivos originales del juego):**
  - Sprites pixel-art propios para palos, figuras, dorsos de mazo, Jokers, Tarots, Planetas, sellos y fondos de cartas.
  - Opcional: temática clásica de póker o temática **Pokémon / Desgraciamon** (por ejemplo, Jokers basados en Pokémon y objetos de Cobblemon).

---

## 3. Fases de desarrollo

### Fase 1 — Base jugable completa (MVP sólido)
- [ ] Estructura del módulo Gradle (`E:\Desgraciamon 3 Mods (1.8.4)\desgraciamon-balatro`).
- [ ] Evaluador de manos de póker + cálculo exacto de `Chips × Mult`.
- [ ] Flujo completo de ronda: *Small Blind* → *Big Blind* → *Boss Blind* (3–5 Boss Blinds iniciales) → *Cash Out* → Tienda → siguiente Ante.
- [ ] **20–25 Jokers iniciales** representativos (Chips, `+Mult`, `×Mult`, economía y triggers por palo/rango).
- [ ] Primeras cartas de **Planeta** (subir nivel de manos) y **Tarot** básicas (Bonus, Mult, Cristal, Acero, cambio de palo).
- [ ] 3–4 Mazos iniciales (Rojo, Azul, Amarillo, Negro).
- [ ] GUI jugable completa en cliente + assets propios básicos + comando `/balatro` para abrir partida.

### Fase 2 — Expansión de contenido (paridad completa)
- [ ] Ampliar hasta los **150 Jokers** en módulos organizados por categoría (`ChipsJokers`, `MultJokers`, `XMultJokers`, `EconomyJokers`, `RetriggerJokers`).
- [ ] Completar **22 Tarots**, **12 Planetas**, **18 Espectrales**, **32 Vouchers** y **Ediciones** (*Foil*, *Holographic*, *Polychrome*, *Negative*).
- [ ] Completar **Boss Blinds** especiales, *Skip Tags* y **15 Mazos** + niveles de dificultad (*Stakes*).

### Fase 3 — Integración con el servidor Cobblemon
- [ ] Bloque interactivo (mesa/máquina arcade en el lobby) además de comando `/balatro`.
- [ ] Modo apuestas / recompensas conectado a **CobbleDollars** o ítems/misiones del servidor (con límites diarios para no romper la economía).
- [ ] Ranking del servidor (`/balatro top`): mejor puntuación en una mano, Ante máximo alcanzado y victorias.
- [ ] Efectos visuales y sonoros propios al hacer manos grandes o superar un *Ante*.

---

## 4. Estructura de carpetas sugerida

```text
desgraciamon-balatro/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/desgraciamon/balatro/
    │   ├── BalatroMod.kt
    │   ├── engine/
    │   │   ├── CardModels.kt        (Card, Rank, Suit, Enhancement, Edition, Seal)
    │   │   ├── HandEvaluator.kt     (Evaluación de manos de póker)
    │   │   ├── ScoreCalculator.kt   (Pipeline Chips x Mult)
    │   │   ├── JokerRegistry.kt     (Definición y hooks de Jokers)
    │   │   ├── ConsumableRegistry.kt(Tarots, Planetas, Espectrales)
    │   │   ├── BlindManager.kt      (Small, Big, Boss Blinds y Tags)
    │   │   ├── ShopManager.kt       (Tienda, Booster Packs, Vouchers, Reroll)
    │   │   └── BalatroRunState.kt   (Estado completo de la partida)
    │   ├── net/
    │   │   └── BalatroNetworking.kt (Payloads S2C / C2S)
    │   ├── storage/
    │   │   └── BalatroStorage.kt    (JSON por jugador + ranking)
    │   └── client/
    │       ├── BalatroClient.kt
    │       ├── BalatroScreen.kt     (Mesa interactiva + tienda)
    │       └── CardRenderer.kt      (Dibujo de cartas, hover, tooltips)
    └── resources/
        ├── fabric.mod.json
        └── assets/desgraciamon_balatro/
            └── textures/gui/...     (Assets propios)
```
