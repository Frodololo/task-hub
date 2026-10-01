---
workdir: /home/liberto/task-hub
max_turns: 500
allowed_tools: Read,Edit,Write,Bash,Grep,Glob,Task
---

# Comisión: decisiones técnicas panel v24 + Settings expandible

HEAD: 28be1d4 (v0.7.58). Aplicar 3 cambios + refactor grande de Settings.
Compilar y testear TRAS CADA BLOQUE.

## Bloque 1 — CalendarSyncManager Option B: resetear al recuperar red

Archivo: ui/models/CalendarSyncManager.kt (buscar el que tenga consecutiveTokenFailures)

El fix de v24 aplicó Mutex + isOnline(). Ahora hay que aplicar la lógica:
- Si `ensureCalendarAccessToken()` falla por red (no hay isOnline()), NO incrementar consecutiveTokenFailures
- Si falla estando online, incrementar
- Si el contador llega al límite y el fallo era de red, al recuperar red se resetea automáticamente (nuevo reconcile lo resetea porque el contador se resetea al obtener token con éxito)

Ya debería estar implementado parcialmente. Verificar que el comportamiento actual coincide con:
1. Sin red → no incrementa contador
2. Con red y token revocado → incrementa y desvincula

Si ya está OK, no tocar nada y pasar al bloque 2.

Compilar: cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

---

## Bloque 2 — release.yml: añadir compile + test antes de bundleRelease

Archivo: .github/workflows/release.yml

Añadir DESPUÉS de "Setup Gradle" y ANTES de "Decode upload keystore" dos pasos:

```yaml
      - name: Compile Android debug
        run: ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

      - name: Run JVM tests
        run: ./gradlew :composeApp:jvmTest --console=plain
```

Si alguno falla, el job se detiene antes de firmar nada.

Compilar: cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

---

## Bloque 3 — SettingsSheet con secciones expandibles (acordeón)

Archivo: composeApp/src/commonMain/kotlin/org/taskhub/ui/components/SettingsSheet.kt

Actualmente SettingsSheet muestra todas las secciones en una sola columna vertical.
El usuario quiere secciones que se desplieguen/contraigan (acordeón).

### Estructura actual de SettingsSheet (lineas aproximadas):

Línea 124-212: Sección "Cuenta" (SettingsSection)
Línea 219-414: Sección "Google Calendar" (SettingsSection, condicional hasCalendarSupport)
Línea 415-490: Sección "Notificaciones" (SettingsSection, condicional hasNotificationSupport)
Línea 491-580: Sección "Widget tema" (SettingsSection, condicional hasHomeScreenWidget)  
Línea 581-680: Sección "Tema" (con RadioOptionRow) + "Idioma" (con RadioOptionRow)
Línea 681-740: Sección "Sonido/Vibración" + "Exportar CSV"
Línea 741-790: Sección "Privacidad" (analytics opt-out, política privacidad)
Línea 791-849: Sección "Eliminar cuenta" (botón con confirmación)

### Nuevo diseño con acordeón

Cada sección es un header clickeable que al pulsarlo expande/contrae su contenido.

Grupos propuestos (a mi criterio):
1. **Cuenta** — email, sign out/in, editar perfil, eliminar cuenta
   (el botón eliminar cuenta se mueve aquí desde abajo)
2. **Calendario** — Google Calendar sync (solo si hasCalendarSupport)
3. **Personalización** — Tema, idioma, widget tema (si hasHomeScreenWidget)
4. **Notificaciones y sonido** — Notificaciones, sonido/vibración
5. **Privacidad y datos** — Analytics opt-out, exportar CSV, política de privacidad

### Cómo implementar (patrón Compose)

Se puede hacer con un componente reutilizable:

```kotlin
@Composable
private fun ExpandableSection(
    title: String,
    icon: ImageVector,  // opcional
    defaultExpanded: Boolean = false,
    isVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!isVisible) return
    
    var isExpanded by remember { mutableStateOf(defaultExpanded) }
    
    // Header clickeable
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Contraer" else ns("expandir")
            )
        }
    }
    
    if (isExpanded) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            content()
        }
    }
    
    Spacer(Modifier.height(4.dp))
}
```

Ideas de iconos para cada sección:
- Cuenta: Icons.Filled.Person
- Calendario: Icons.Filled.DateRange  
- Personalización: Icons.Filled.Palette (o Settings)
- Notificaciones: Icons.Filled.Notifications
- Privacidad: Icons.Filled.Lock

Necesitas verificar qué iconos están disponibles en material-icons-core (NO extended).
Disponibles: Person, Settings, Notifications, Lock, DateRange, Home, Add, Search, etc.
Si el icono no existe, NO inventarlo — usar solo texto.

### Consideraciones importantes:

1. **Importar los iconos correctos**: solo Icons.Filled.* (Icons.Default.* deprecado, ya corregido en v24).
   Iconos disponibles confirmados: Person, Settings, Notifications, Lock, DateRange, Close, Refresh, Edit, Delete, Search, Add, AddCircle, Home, Email, KeyboardArrowLeft, KeyboardArrowRight.

2. **Animaciones**: NO usar AnimatedVisibility ni transiciones. El cambio de expandido/contraído debe ser instantáneo (Modo Simple compatible, no requiere toggle maestro).

3. **Estado persistente**: NO guardar qué secciones están expandidas/contraídas. Cada vez que se abre SettingsSheet, todas empiezan contraídas excepto la primera (Cuenta) que empieza expandida.

4. **contentDescription**: el icono de expandir/contraer debe tener contentDescription para accesibilidad.

5. **Scaffold y scroll**: mantener el `verticalScroll(rememberScrollState())` en la Column principal.

6. **Mantener toda la funcionalidad**: no cambiar ni una línea de la lógica existente dentro de cada sección. Solo envolver en el acordeón.

### Pasos de implementación:

1. Crear el componente `ExpandableSection` como función privada en el mismo SettingsSheet.kt
2. Envolver cada SettingsSection existente dentro de un ExpandableSection
3. Unificar el botón de "Eliminar cuenta" dentro de la sección Cuenta (moverlo desde el final al grupo 1)
4. Eliminar `SettingsSection()` si ya no se usa

Para las claves i18n:
- `settings_privacy_section` → ES: "Privacidad y datos", EN: "Privacy & data"
- `settings_personalization_section` → ES: "Personalización", EN: "Personalization"  
- `settings_notifications_section` → ES: "Notificaciones y sonido", EN: "Notifications & sound"

Añadirlas en AppStrings.kt en ES y EN.

Compilar: cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

---

## Bloque 4 — Cerrar tarjetas técnicas en kanban

Tras compilar, marcar como "Completado" en el project board las tarjetas:
- "[Decisión] CalendarSyncManager: desvincula Calendar por fallos de red"
- "[Decisión] release.yml publica sin ejecutar tests"

Usar: gh project item-edit --project-id PVT_kwHOCXo7m84BjLGt --id <item_id> --field-id PVTSSF_lAHOCXo7m84BjLGtzhiAdBs --single-select-option-id 43ad04c4
(donde 43ad04c4 es "Completado")