# WYT Chester's (wyt_chesters)

Mod para **Minecraft 26.2 · NeoForge 26.2.0.88**. Requiere **GeckoLib 5.5.6** instalado como mod aparte.

## Contenido (pestaña "WYT Chester's" del creativo)
- **Llave Dorada de Chester**: CLICK derecho invoca a Chester (la llave se consume).
- **Chester**: cofre aliado con patas, animado con GeckoLib. Sigue al jugador, guarda objetos y se vacía en tolvas.

## Cómo compilar el .jar
Sube todo este contenido a un repositorio de GitHub. La pestaña **Actions** compila sola
("Compilar mod") y deja el `.jar` en **Artifacts → wyt-chesters-jar**.
También en local con JDK 25 y Gradle: `gradle build` (el `.jar` queda en `build/libs/`).

## Estructura
- `src/main/java/com/wyt/chesters/` código del mod
- `src/main/resources/assets/wyt_chesters/` texturas, modelos y textos
- `src/main/resources/assets/wyt_chesters/geckolib/` modelo y animaciones de Chester
- `src/main/resources/data/` loot de la llave
- `libs/` copia de GeckoLib usada solo para compilar
