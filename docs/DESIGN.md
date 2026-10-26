# ClipHive — Documento de diseño y plan de acción

> **Proyecto**: **ClipHive** (`cliphive`, paquete Java `io.github.raulgl422.cliphive`). Documento vivo con todas las decisiones de diseño, los resultados de las pruebas y el plan de acción. Es la fuente de verdad del proyecto: cualquier cambio de diseño se refleja aquí antes de implementarlo.
>
> **Estado**: todas las fases (1 a 9, §19) implementadas y funcionando: el servidor en Docker en una Raspberry Pi y el worker como instalador MSI de Windows. Quedan los trámites de la fase 0 (auditorías de TikTok y Google) y pulir la aplicación para publicarla; lo aún no implementado va marcado como *Pendiente* en su sección y en §20.2.
> **Última actualización**: 2026-10-25 (documento revisado contra el código: funciones que no existían marcadas como pendientes, reglas del autotest, cola de resultados del worker, motivos de rechazo de la conexión).

---

## Índice

1. Objetivo
2. Principios del proyecto
3. Arquitectura
4. Pipeline completo de un vídeo
5. Estados (trabajo, clip, tarea, publicación)
6. Cola, asignación y tolerancia a fallos
7. Protocolo WebSocket
8. Worker
9. Especificación de procesamiento (transcripción y render)
10. Análisis y metadatos con Claude
11. Publicación en redes
12. Web superadmin
13. Seguridad
14. Persistencia
15. Retención de ficheros
16. Stack, repositorio, CI y empaquetado
17. READMEs: requisitos por sistema operativo y GPU
18. Resultados de las pruebas técnicas
19. Plan de acción
20. Decisiones
21. Riesgos y advertencias
22. Revisión técnica

---

## 1. Objetivo

Sistema self-hosted, **multiplataforma (Linux y Windows)**, que:

1. Recibe un **enlace** a un vídeo (YouTube, Twitch, Kick y cualquier sitio soportado por `yt-dlp`) desde la web superadmin.
2. Valida si se puede descargar y, si no, devuelve el **motivo** legible (privado, bloqueo geográfico, requiere login, directo en curso, DRM, sitio no soportado…).
3. Descarga el vídeo, lo **transcribe**, usa **Claude** para detectar intros, outros y partes sobrantes, lo **corta en clips** (duración decidida por Claude dentro de los límites de cada plataforma) sin cortar frases, lo pasa a **vertical 9:16 con fondo desenfocado** y le **graba subtítulos resaltados palabra a palabra**.
4. Genera con **Claude** un **título y una descripción distintos para cada clip**.
5. **Programa y publica** los clips automáticamente en **TikTok**, **Instagram (Reels)** y **YouTube Shorts**, con el mismo título y descripción. **Claude planifica cada día cuántos clips subir y a qué hora en cada plataforma**, para mantener siempre contenido saliendo y liberar la cola. Para pruebas, modo de **publicación inmediata**.
6. Reparte el trabajo pesado (transcripción y render) entre **workers** (PC, portátiles) que se conectan y desconectan libremente, sin perder trabajo.
7. Ofrece una web **superadmin** para enviar enlaces, supervisar y controlarlo todo.
8. **No deja rastro**: borra los vídeos y clips en cuanto se han publicado.

## 2. Principios del proyecto

### 2.1 Repositorio público
- El repositorio estará **público en GitHub**: **ningún secreto** en el código ni en el historial.
- Configuración sensible por variables de entorno (`.env` en `.gitignore` + `.env.example` documentado).

### 2.2 Multiplataforma: no se da nada por hecho
Tanto el **servidor** como los **workers** funcionan en **Linux** (x86_64 y ARM64, p. ej. Raspberry Pi) y en **Windows**. El código nunca asume el sistema operativo:

- Rutas siempre con `java.nio.file.Path`; nunca separadores escritos a mano (`/` o `\`).
- Rutas de los binarios externos (`yt-dlp`, `ffmpeg`, `ffprobe`, `whisper-cli`, `claude`) **configurables**, con autodetección en el `PATH` (con extensión `.exe` en Windows).
- Procesos externos con `ProcessBuilder` y **lista de argumentos**, nunca a través de `cmd` ni `bash`.
- El filtro `subtitles` de ffmpeg da problemas con las rutas de Windows (hay que escapar la letra de unidad: `C\:/...`). Se ejecuta ffmpeg con el **directorio de trabajo** de la tarea y se le pasan **rutas relativas**.
- Directorios de datos según el SO (`%LOCALAPPDATA%` en Windows; `~/.local/share` o `/var/lib` en Linux), siempre configurables.
- Instalación como servicio: **systemd** en Linux; **WinSW/NSSM** en Windows.
- Toda la documentación cubre **ambos SO** (sección 17).

### 2.3 Diseño modular
Las piezas con varias implementaciones posibles van detrás de una interfaz:
- `Publisher` (TikTok, Instagram, YouTube, o un intermediario).
- `LlmClient` (`claude -p` o API de Anthropic; hoy siempre `claude -p`, §10.1).
- `Encoder` (AMF, NVENC, VAAPI y por software `libopenh264`, o `libx264` si es el único disponible) en el worker.

## 3. Arquitectura

```
                     ┌──────────────────────────────────────────────┐
  Tú (navegador /    │          SERVIDOR (Linux o Windows)           │
  móvil)             │                                               │
     │               │  Puerto ADMIN (solo localhost)                │
     │  Cloudflare   │   - Superadmin (Thymeleaf + HTMX)             │
     └──Tunnel + ───►│                                               │
        Access       │  Núcleo                                       │
                     │   - Validación y descarga (yt-dlp)            │
                     │   - Cola de tareas + asignación               │
                     │   - Análisis con Claude                       │
                     │   - Scheduler de publicaciones                │
                     │   - Publishers TikTok / Instagram / YouTube   │
                     │                                               │
                     │  Puerto MEDIA (localhost, solo /media)        │ ◄── Instagram (túnel,
                     │   - Vídeos con URL firmada y caducidad        │     sin Access)
                     │                                               │
                     │  Puerto WORKERS (solo interfaz Tailscale)     │
                     │   - WebSocket de coordinación                 │
                     │   - HTTP de ficheros (URLs firmadas)          │
                     └───────────────┬──────────────────┬────────────┘
                                     │ Tailscale        │
                                     ▼                  ▼
                        ┌──────────────────────┐  ┌────────────┐
                        │ WORKERS (Linux/Win)  │  │ PostgreSQL │
                        │ PC, portátiles…      │  └────────────┘
                        └──────────────────────┘
```

### 3.1 Componentes

| Componente | Hace | No hace |
|---|---|---|
| **Servidor** | Recibir enlaces, validar y descargar, orquestar la cola, asignar tareas, servir ficheros, analizar con Claude, planificar clips, programar y publicar, recoger estadísticas, limpiar ficheros | Transcribir ni renderizar (sin fallback: si no hay workers, los trabajos esperan) |
| **Worker** | Transcribir, renderizar clips (recorte, 9:16, subtítulos), informar de progreso | Ver cuentas, tokens, logs globales, estadísticas ni nada del superadmin |
| **Superadmin** | Enviar enlaces, supervisar y controlar todo (web responsive, también desde el móvil) | — |

No hay app de usuario aparte: Flutter queda descartado por ahora.

### 3.2 Red
- **Superadmin**: puerto que escucha **solo en `localhost`**; se publica con **Cloudflare Tunnel** protegido por **Cloudflare Access**.
- **Workers**: puerto que escucha **solo en la interfaz de Tailscale**. Un worker no puede ni alcanzar las rutas de administración.
- **Medios públicos para Instagram** (*revisión R1*): Instagram descarga el vídeo desde una URL pública, pero el túnel del superadmin está detrás de Cloudflare Access (Instagram no puede pasarlo) y el puerto de workers solo existe en Tailscale. Hace falta una **tercera ruta**: un hostname público del mismo túnel (p. ej. `media.tudominio`) **sin Access**, que solo sirva `GET /media/{token}` con **URLs firmadas de un solo clip y caducidad corta** (p. ej. 1 hora), apuntando a un tercer conector en `localhost` que no expone nada más.
- **Dominio**: uno propio, gestionado en Cloudflare (en los ejemplos, `example.com`). Hostnames del túnel:
  - `cliphive.example.com` → superadmin (con Cloudflare Access).
  - `cliphive-media.example.com` → ruta de medios para Instagram (sin Access).
  - Se usan subdominios de **un solo nivel** porque el certificado gratuito de Cloudflare cubre `*.example.com` pero no `*.cliphive.example.com`.
- Spring Boot con **tres conectores** en la misma aplicación: admin (`localhost`), media (`localhost`, solo `/media/**`) y workers (Tailscale).

## 4. Pipeline completo de un vídeo

| # | Paso | Dónde | Detalle |
|---|---|---|---|
| 1 | **Envío** | Superadmin | Pegas el enlace. Opciones por trabajo: idioma, modo de fps, modo revisión (heredan la configuración global) y **modo de clips**: *automático* (Claude los propone, pasos 6–8) o *manual* (los marcas tú, ver §4.1). Opción **audio**: *original* (por defecto) o un **doblaje** en un idioma concreto; si el vídeo trae esa pista (doblaje del creador o automático de YouTube) se usa, y si no, el audio original. Con un doblaje elegido, el idioma del trabajo (transcripción, subtítulos, títulos) pasa a ser el del doblaje. |
| 2 | **Validación** | Servidor | `yt-dlp --dump-json --simulate`: detecta la plataforma, duración, fps, resolución, título, categoría/juego (Twitch/Kick), y si se puede descargar. Si no, o si dura más de **2 h**, → `REJECTED` con motivo legible.  Si se eligió un doblaje, se mira si alguna pista de audio está en ese idioma y se anota (`dubbed_audio_available`). |
| 3 | **Descarga** | Servidor | `yt-dlp` con resolución máxima 1080p. Antes de descargar se comprueba la **duración máxima** permitida y el **espacio libre** en disco (*R6*); como mucho 1–2 descargas a la vez. En YouTube se consultan los segmentos de **SponsorBlock** como **datos** (no se eliminan al descargar: los decide el paso 6). El fichero se identifica por hash.  Con un doblaje elegido y disponible, `yt-dlp` descarga `bestaudio[language^=<idioma>]`; si no lo hay, el audio original. |
| 4 | **Extracción de audio** | Servidor | `ffmpeg` extrae el audio a WAV 16 kHz mono. Es barato (no recodifica vídeo) y evita que el worker tenga que descargar el vídeo entero solo para transcribir. |
| 5 | **TRANSCRIBE** | Worker | whisper.cpp con timestamps **por palabra** (JSON completo). El audio se divide en **trozos de ~10 min con un pequeño solape** (*R5*): una tarea por trozo, paralelizables, y el servidor une los resultados descartando el solape. Así un directo de 4 h no depende de una sola tarea de horas.  Al unir los trozos, el servidor pide a Claude que **corrija las palabras mal reconocidas** sin tocar los tiempos (§10.5). |
| 6 | **Análisis con Claude** | Servidor | Detecta inicio/fin útiles, segmentos a eliminar, propuesta de clips y tipo de contenido (sección 10). Si Claude falla tras los reintentos, se sigue **sin recortes** (solo SponsorBlock y tramos sin voz) y el trabajo pasa a revisión (*R12*). |
| 7 | **Revisión** (opcional) | Superadmin | En modo revisión, los cortes propuestos esperan tu aprobación antes de renderizar; se pueden editar los títulos y descripciones, no los cortes (§12.2). |
| 8 | **Planificación de clips** | Servidor | A partir de los `clips_propuestos` de Claude (inicio, fin y por tanto **duración de cada clip**), ajustados a **pausas naturales** (nunca a mitad de frase) y aplicando los recortes. El servidor valida los límites de duración de cada plataforma (sección 9.5). Cada clip guarda sus rangos a conservar. |
| 9 | **Metadatos** | Servidor | Claude genera título y descripción por clip (sección 10.3). |
| 10 | **RENDER_CLIP** | Worker | El servidor prepara para cada clip **solo su trozo del vídeo fuente** con `ffmpeg -c copy` (sin recodificar, rápido incluso en la Pi), con unos segundos de margen porque el corte sin recodificar cae en fotogramas clave; el worker descarga ese trozo y hace el corte exacto (*R7*). Una tarea por clip: recorte (con unión de tramos si hay partes eliminadas), decisión de fps, 9:16 con fondo desenfocado, subtítulos karaoke, codificación por GPU. |
| 11 | **Programación** | Servidor | Según el **modo de publicación** (sección 10.4): en modo **inmediato** (pruebas) se publica en cuanto el clip está renderizado; en modo **Claude**, el planificador diario decide cuántos clips salen hoy y a qué hora **en cada plataforma**. Se crea una publicación por clip y plataforma. Horas guardadas en UTC y mostradas en Europe/Madrid. |
| 12 | **Publicación** | Servidor | A la hora prevista se publica en cada plataforma (en YouTube puede subirse antes con `publishAt`). |
| 13 | **Limpieza** | Servidor y workers | Se borra el clip cuando ya **no queda ninguna subida pendiente** a ninguna plataforma (publicado, subido con `publishAt` o fallido definitivamente), y el vídeo fuente cuando todos sus clips están en estado final (sección 15). |
| 14 | **Estadísticas** | Servidor | Recogida periódica de métricas de cada publicación y de cada cuenta (§12.4): cada 6 h los primeros 7 días tras publicar, cada 24 h hasta los 90 días, y después se deja de consultar. |

### 4.1 Modo manual de clips

Para sacar un clip concreto de un vídeo en vez de dejar que Claude elija.
- En cuanto termina la **descarga** (paso 3), el vídeo fuente se puede ver en el detalle del trabajo y se marcan **inicio y fin** de cada clip. Los tramos se guardan al marcarlos; mientras tanto el worker **transcribe el vídeo entero** (paso 5), que hace falta para los subtítulos.
- Al **confirmar los cortes** ya no se pueden cambiar. Si la transcripción termina antes de confirmar, el trabajo espera en `AWAITING_SELECTION`.
- Con los cortes confirmados y la transcripción hecha: **no hay análisis de contenido** (paso 6) ni revisión; cada tramo se ajusta a los límites de la palabra más cercana (para no cortar una palabra por la mitad) y es un clip (paso 8). Se validan los mismos límites de duración (§9.5).
- Claude sigue escribiendo **título y descripción** de cada clip (paso 9).

## 5. Estados

### 5.1 Trabajo de vídeo (`video_job`)
```
SUBMITTED → VALIDATING → REJECTED (fin)
                       → DOWNLOADING → TRANSCRIBING → ANALYZING
                         → AWAITING_REVIEW (solo modo revisión)
                         → AWAITING_SELECTION (solo modo manual, si aún no se han confirmado los cortes)
                         → RENDERING → RENDERED → SCHEDULED → PUBLISHING → COMPLETED
FAILED (error no recuperable) · CANCELLED (desde superadmin)
```
`RENDERED`: todos los clips están en un estado final de render (`RENDERED`, `FAILED` o `CANCELLED`); el trabajo espera a que el planificador (paso 11) cree su plan de publicación y lo pase a `SCHEDULED`. Reintentar un clip fallido devuelve el trabajo a `RENDERING`.

Los estados finales (`FAILED`, `CANCELLED`, `REJECTED`, `COMPLETED`) no se abandonan: un paso que termina tarde (una validación o una descarga que acaba después de cancelar el trabajo) no lo revive ni lo pasa a `FAILED`. Cualquier error inesperado de la validación, la descarga o la preparación de la transcripción deja el trabajo en `FAILED` con el motivo, nunca a medias.

**Tras un reinicio del servidor** se retoma lo que se quedó a medias, porque esos pasos solo viven en memoria:
- `SUBMITTED` (aún en la cola de validación en memoria), `VALIDATING` y `DOWNLOADING` vuelven a empezar su paso (se vuelve a validar; la descarga sobrescribe lo que hubiera).
- `TRANSCRIBING` sin ninguna tarea creada vuelve a trocear el audio y crear las tareas.
- Las tareas de los workers **no se tocan**: el worker sigue con ellas al reconectar y las entrega con el mismo `attempt`. Si una se perdió de verdad, la recoge el timeout de progreso (§6.5).
Solo se toca lo que ya estaba así antes de arrancar, para no pisar lo que avance en los primeros segundos. Un fallo al retomar un trabajo se registra y no impide arrancar.

### 5.2 Clip (`clip`)
```
PLANNED → RENDERING → RENDERED → SCHEDULED → PUBLISHED (en todas sus plataformas) → CLEANED
FAILED · CANCELLED
```
`CLEANED`: su fichero se borró porque no le queda ninguna subida pendiente (sección 15); viene de `PUBLISHED` o de `SCHEDULED` con publicaciones canceladas.
Un clip pasa a `RENDERING` cuando un worker acepta su `RENDER_CLIP` y a `FAILED` cuando esa tarea agota sus intentos; reintentarla lo devuelve a `PLANNED`.

### 5.3 Tarea (`task`, unidad de trabajo de un worker)
```
PENDING → ASSIGNED → RUNNING → DONE
   ▲          │          │
   └──────────┴──────────┘  worker caído / timeout / TASK_RELEASE
FAILED (al superar el máximo de intentos) · CANCELLED (desde superadmin)
FAILED → PENDING al reintentar desde el superadmin (contadores a cero, attempt +1)
```
Cancelar una tarea también sube el `attempt`: lo que envíe después el worker que la tenía llega con un `attempt` viejo y se ignora (§6.3), así una tarea cancelada no vuelve a la cola.

### 5.4 Publicación (`scheduled_post`, una por clip y plataforma)
```
SCHEDULED → UPLOADING → PROCESSING (la plataforma procesa) → PUBLISHED
                                                           → FAILED (reintentable)
CANCELLED
```
`PUBLISHED` significa que la plataforma terminó de procesar el vídeo, **o que lo subiste tú a mano** y lo marcaste en el superadmin (*Ya lo subí a mano*, §11.3; queda `published_manually`). Mientras no haya auditoría (§11.4) eso es en **privado** (YouTube) o como **borrador** (TikTok): el paso a público es manual. Por eso, en esas plataformas la publicación queda además **pendiente de confirmar** (`awaiting_confirmation`, cambio de 2026-10): se ve como "SUBIDO · FALTA PUBLICAR" hasta que pulsas **Ya está publicado** (§11.3). Para el ritmo y el orden de la cola cuenta ya como salida (no frena al clip siguiente); para la limpieza y para dar el trabajo por terminado, no.

## 6. Cola, asignación y tolerancia a fallos

### 6.1 Granularidad
- **TRANSCRIBE**: una por trozo de audio de ~10 min (*R5*), paralelizables.
- **RENDER_CLIP**: una por clip, paralelizables entre workers. Si un worker se cae, solo se pierde ese clip.

### 6.2 Prioridad
- Los **primeros clips** de un vídeo tienen más prioridad (índice ascendente).
- Entre trabajos distintos, **FIFO** por fecha de envío.
- *Pendiente*: **cambiar la prioridad** de un trabajo desde el superadmin. La columna `video_job.priority` existe, pero no hay pantalla ni endpoint que la cambie (cambio de 2026-10: el documento lo daba por hecho).

### 6.3 Garantía de no duplicar tareas
- **Asignación atómica** en PostgreSQL con `SELECT ... FOR UPDATE SKIP LOCKED`.
- Cada asignación lleva un **número de intento** (`attempt`) que funciona como *fencing token*.
- Todo mensaje del worker sobre una tarea incluye `taskId + attempt`. Si el `attempt` no es el vigente, el servidor **rechaza** el mensaje y los ficheros. Cubre el caso de un worker dado por muerto que reconecta tarde.
- En el worker, cada intento trabaja en su propia carpeta (`tasks/<taskId>-<attempt>`). Si le llega un intento nuevo de una tarea que aún está ejecutando, para el anterior (su resultado ya no valdría) en vez de tener los dos a la vez.
- **Dos contadores por tarea** (*R9*):
  - **Fallos** (`TASK_FAILED`, `TASK_REJECT` o timeout de progreso): máximo **3** → `FAILED`. Un `TASK_REJECT` cuenta como fallo (cambio de 2026-10, así lo hace `TaskLifecycleService.reject`): un worker que rechaza una y otra vez la misma tarea no la deja dando vueltas sin fin. Indican que la tarea en sí da problemas.
  - **Desconexiones** (heartbeat perdido): máximo **10**. Cerrar la tapa del portátil no es culpa de la tarea, y no debe agotar sus intentos.
  - `TASK_RELEASE` no cuenta en ninguno. El `attempt` (fencing token) sube en cualquier reasignación.

### 6.4 Asignación según capacidades
- **TRANSCRIBE** → a cualquier worker con hueco libre. *Pendiente*: darlas preferentemente a workers con GPU y modelos grandes; hoy el reparto solo mira la capacidad libre (cambio de 2026-10).
- **RENDER_CLIP** → a cualquier worker con hueco libre, pero **solo cuando su trabajo está en `RENDERING`**. Las tareas se crean al planificar los clips, antes de cortar su trozo de vídeo (paso 10) y, en modo revisión, antes de que se aprueben los cortes; hasta entonces no se reparten.
- Cada worker declara cuántas tareas acepta en paralelo.

### 6.5 Detección de workers caídos o atascados
- **Heartbeat**: `PING` cada **20 s**, espera de **10 s** por el `PONG`, **3 fallos seguidos** → conexión perdida; sus tareas vuelven a `PENDING` con `attempt + 1`.
- **Timeout de progreso**: si una tarea no da señales (`TASK_ACCEPT`, `PROGRESS`) en **30 minutos** (ajuste `tasks.stall-minutes`), se considera atascada aunque el worker responda a los pings: al worker se le manda `TASK_CANCEL` y después vuelve a la cola contando como un fallo (en ese orden: si no, el reparto podría mandársela otra vez al mismo worker y el `TASK_CANCEL`, que no lleva `attempt`, cortaría la nueva). Es generoso porque un render no manda `PROGRESS` mientras ffmpeg codifica.
- **Parada voluntaria**: `TASK_RELEASE` devuelve la tarea a la cola al instante **sin gastar intento**; `GOODBYE` cierra la sesión sin esperar al heartbeat, y lo que el worker aún tuviera vuelve a la cola igual, sin gastar intento. Al parar, el worker cancela sus tareas y manda solo el `GOODBYE` (§8.2); el `TASK_RELEASE` lo usa al recibir una tarea estando en pausa (§8.3).
- **Reconexión**: si un worker vuelve a conectarse antes de que el servidor note que la sesión anterior murió, sus tareas siguen asignadas a él: las sigue ejecutando y las entrega con el mismo `attempt`. Devolverlas a la cola haría que se le mandaran otra vez mientras aún corren.
- Si el `TASK_ASSIGN` no llega a salir (la sesión se cerró justo), la tarea vuelve a la cola al momento.

## 7. Protocolo WebSocket

El WebSocket **solo coordina**. Los ficheros (audio, vídeo fuente, clips, transcripciones) se transfieren por **HTTP** contra el puerto de workers, con **URLs firmadas y con caducidad** ligadas a `taskId + attempt`.

| Dirección | Tipo | Contenido |
|---|---|---|
| W → S | `HELLO` | token del worker, versión del protocolo y del worker, SO, capacidades (GPU, encoders, backend de whisper, modelos disponibles, concurrencia máx.) |
| S → W | `WELCOME` | id de sesión, configuración (intervalos de ping, timeouts) |
| S → W | `TASK_ASSIGN` | `taskId`, `attempt`, tipo (`TRANSCRIBE` / `RENDER_CLIP`), parámetros, URLs de descarga y subida |
| W → S | `TASK_ACCEPT` / `TASK_REJECT` | `taskId`, `attempt` (+ motivo si rechaza) |
| W → S | `PROGRESS` | `taskId`, `attempt`, porcentaje, fase |
| W → S | `TASK_DONE` | `taskId`, `attempt`, referencias de los ficheros subidos |
| W → S | `TASK_FAILED` | `taskId`, `attempt`, error |
| W → S | `TASK_RELEASE` | `taskId`, `attempt`: suelta la tarea (hoy, una tarea recibida en pausa, §8.3); vuelve a `PENDING` sin gastar intento |
| S → W | `TASK_CANCEL` | `taskId` (cancelación desde superadmin) |
| W → S | `GOODBYE` | cierre voluntario de la sesión |
| S ↔ W | `PING` / `PONG` | heartbeat |

Los mensajes se definen una sola vez en el módulo compartido `protocol`.

**Formato de los mensajes** (añadido al implementar `protocol`, 2026-10-01): este documento no fijaba el formato exacto de cada mensaje. Se implementó como JSON con un campo discriminador `type` (p. ej. `{"type":"TASK_ASSIGN", ...}`), vía Jackson, por ser la opción más simple que no ata `protocol` a Spring Boot (el worker es Java plano). Cada mensaje es un `record` de Java; la jerarquía es `sealed` (`permits` lista los 13 tipos) para que el compilador avise si un `switch` deja de contemplar alguno al añadir un mensaje nuevo. Detalle completo en los Javadoc de `protocol/src/main/java/io/github/raulgl422/cliphive/protocol/message/` y en `protocol/README.md`.

**Versionado** (*R14*): `HELLO` incluye la versión del protocolo. Si la versión mayor no coincide, el servidor responde con un error y el worker muestra "actualiza el worker" en lugar de procesar tareas con un formato incompatible.

**Motivos de rechazo de la conexión** (cambio de 2026-10): cuando el servidor no acepta un `HELLO`, cierra el WebSocket con el código `POLICY_VIOLATION` (1008) y uno de estos motivos, en inglés y con este texto exacto:

| Motivo | Cuándo |
|---|---|
| `Unknown token` | El token no corresponde a ningún worker dado de alta |
| `Token revoked` | El token se revocó desde el superadmin |
| `Protocol version mismatch: update your worker` | La versión mayor del protocolo no coincide (*R14*) |

El worker reconoce el motivo **por su texto** para decirle al usuario qué hacer (§8.2), así que estos textos forman parte del contrato: cambiarlos en el servidor exige cambiar a la vez el worker (`ServerConnection.explainRejection`).

**Alta de workers** (*R13*): desde el superadmin se crea un worker (nombre) y se genera su **token, que se muestra una sola vez**; en BD solo se guarda su hash. El token se puede **revocar**, lo que cierra la sesión activa de ese worker.

## 8. Worker

### 8.1 Qué es
- Programa en **Java** que se conecta al servidor por **Tailscale** y procesa tareas sin pedir confirmación.
- **Sin acceso** a cuentas, tokens de redes sociales, logs globales ni estadísticas.

### 8.2 Modos de ejecución (mismo núcleo)
- **Con interfaz (JavaFX)**: ventana pequeña + icono en la bandeja del sistema (`java.awt.SystemTray`; en escritorios Linux sin bandeja, como GNOME por defecto, solo la ventana). Muestra estado, tarea actual, fase, progreso y log reciente; botones de **pausar / reanudar / parar**; editor de configuración.
  - Si el icono de la bandeja no llega a instalarse, la ventana se muestra y al cerrarla se minimiza en vez de ocultarse (oculta no habría forma de volver a abrirla). El editor de configuración valida la URL del servidor y solo aplica los cambios si se han guardado; si no se puede activar o quitar el arranque al iniciar sesión, lo avisa.
- **Headless** (`--headless`): sin interfaz, para Linux sin escritorio o como servicio. Ctrl+C o `SIGTERM` = **parada limpia**: cancela las tareas en curso (mata su ffmpeg o whisper-cli) y manda `GOODBYE`; el servidor las devuelve a la cola sin gastar intento (§6.5). No se manda un `TASK_RELEASE` por tarea (cambio de 2026-10: el documento decía que sí).

**Conexión con el servidor** (cambio de 2026-10):
- Una caída de red o un servidor apagado se reintentan solos (de 2 s a 60 s entre intentos).
- Un **rechazo** del servidor (token desconocido o revocado, versión del protocolo incompatible) o una URL del servidor mal escrita no se arreglan reintentando: la ventana y la bandeja pasan a **Rechazado** con el motivo y qué hacer (revisar el token, actualizar el worker…). Se sigue reintentando cada minuto, así que corregir la configuración basta.
- **Vigilancia propia**: el worker espera un `PING` del servidor cada `pingInterval`; si pasan `pingInterval × (maxMissedPings + 1)` sin ninguno (tras suspender el equipo, cambiar de wifi…), da la conexión por muerta y reconecta, aunque el socket parezca abierto.
- **Resultados sin conexión**: un `TASK_DONE`, `TASK_FAILED` o `TASK_RELEASE` que no se puede enviar se guarda y se manda en cuanto vuelve a llegar el `WELCOME` (§5.1: el servidor mantiene las tareas de un worker que reconecta). Los `PROGRESS` perdidos no se guardan.
  - Solo sirve si el worker reconecta **antes de que el servidor dé la sesión por muerta** (§6.5). Si el servidor ya la dio por perdida y devolvió la tarea a la cola, el `attempt` subió: el resultado guardado llega con un `attempt` viejo y se descarta (§6.3), y la tarea se hace de nuevo.
  - La cola de resultados vive **en memoria**: si se cierra el worker antes de reconectar, se pierde; la tarea la recupera el timeout de progreso (§6.5).
- Un envío que falla o se queda colgado más de 10 s cierra la conexión y fuerza la reconexión. Un `TASK_ASSIGN` que no se puede atender se rechaza (`TASK_REJECT`) en vez de perderse.

### 8.3 Control por parte del usuario
- Ver en todo momento qué está haciendo.
- **Parar**: cancela las tareas en curso y cierra la sesión con `GOODBYE`; el servidor las devuelve a la cola al instante sin gastar intento (§6.5).
- **Pausar / reanudar** la aceptación de tareas nuevas (termina la actual). La pausa se mantiene aunque la conexión se corte y vuelva.
  - **Limitación conocida** (cambio de 2026-10): en pausa el worker sigue anunciando su capacidad, así que el servidor le sigue asignando tareas y el worker contesta a cada una con `TASK_RELEASE`. No gasta intentos, pero cada reasignación sube el `attempt` y la tarea va y viene hasta que otro worker la coge o se reanuda. *Pendiente* (§20.2): anunciar capacidad 0 en pausa.
- **Límites**: número máximo de tareas en paralelo. *Pendiente*: solo con el equipo enchufado y franjas horarias; las claves `onlyWhenPluggedIn` y `workSchedule` se leen de `worker.yml` pero aún no se aplican, así que la ventana de configuración no las ofrece.
- **Arrancar al iniciar sesión** (`startWithSystem`, **activado por defecto**; casilla en la configuración): el worker con interfaz se apunta para arrancar solo al entrar en la sesión del usuario, **minimizado en la bandeja** (`--minimized`; si el escritorio no tiene bandeja, minimizado). Sin permisos de administrador y por usuario:
  - **Windows**: valor `ClipHive Worker` en `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` con la ruta del ejecutable instalado, escrito con `reg.exe` (argumentos en lista, sin `cmd`).
  - **Linux**: fichero `cliphive-worker.desktop` en `~/.config/autostart` (o `$XDG_CONFIG_HOME/autostart`), el estándar XDG que respetan GNOME, KDE, XFCE…
  - Se vuelve a aplicar en cada arranque, así sigue apuntando al ejecutable correcto tras actualizar. Solo funciona en la versión instalada (la que tiene lanzador, propiedad `jpackage.app-path`); con `java -jar` no se toca nada. En modo headless tampoco: ahí lo arranca el servicio (systemd…). Desinstalar no borra la entrada; si queda huérfana el sistema la ignora, y se quita desmarcando la casilla antes de desinstalar.

### 8.4 Configuración
- Fichero por equipo (`worker.yml`) en el directorio de datos del SO; las **variables de entorno lo sobrescriben** (para headless o servicio). La interfaz JavaFX lee y escribe el mismo fichero.
- Variables de entorno (`CLIPHIVE_WORKER_<CLAVE>`, la clave en mayúsculas con `_`; las lee `WorkerPropertiesLoader`): `CLIPHIVE_WORKER_SERVER_URL`, `CLIPHIVE_WORKER_TOKEN`, `CLIPHIVE_WORKER_DATA_DIR`, `CLIPHIVE_WORKER_MAX_CONCURRENT_TASKS`, `CLIPHIVE_WORKER_WHISPER_MODEL`, `CLIPHIVE_WORKER_WHISPER_PATH`, `CLIPHIVE_WORKER_FFMPEG_PATH`, `CLIPHIVE_WORKER_FFPROBE_PATH`, `CLIPHIVE_WORKER_FORCED_ENCODER` y `CLIPHIVE_WORKER_WORK_SCHEDULE` (esta última aún sin efecto, §8.3). Las demás claves (`startWithSystem`, `onlyWhenPluggedIn`…) solo se cambian en el fichero.
- Contenido: URL del servidor, token del worker, límites, modelo de Whisper (o `auto`), encoder forzado (opcional), arrancar al iniciar sesión (§8.3), rutas de binarios (opcional, si no se usan los incluidos).
- **Encoder forzado** (`forcedEncoder`): `auto` (el mejor que pase la prueba de arranque, §9.3), `amf`, `nvenc`, `vaapi` o `software`. Se lee en cada render, así que cambiarlo en la ventana no exige reiniciar. Si el encoder forzado no pasó la prueba de arranque, el worker lo avisa en el log y usa el mejor detectado: forzar uno que no funciona solo haría fallar todos los renders.

- **Errores de configuración** (cambio de 2026-10): un `worker.yml` que no se puede leer, que no es YAML válido o con un valor imposible (`maxConcurrentTasks: abc` o `0`) para el arranque con un mensaje que dice el fichero y la clave; nunca se sustituye por los valores por defecto, que harían creer que es el primer arranque y la ventana de configuración inicial machacaría el fichero. Una clave en blanco (`ffmpegPath:`) deja el valor por defecto, y una clave desconocida (una errata) se avisa en el log. El fichero se guarda escribiendo uno temporal y cambiándolo por el anterior, así un corte a mitad no lo deja a medias.
- Una **ruta de herramienta** configurada (`ffmpegPath`, `whisperPath`…) que no existe o no es ejecutable se avisa como tal al arrancar, en vez de que fallen después todas las tareas.

### 8.5 Detección de hardware y autotest
- Al arrancar detecta: encoders disponibles (`ffmpeg -encoders` y una **prueba** de unos fotogramas con cada encoder de GPU), el backend de whisper.cpp y el SO.
  - **Backend de whisper** (cambio de 2026-10: no se miran los dispositivos): por el **nombre del binario** (`…vulkan…`, `…cuda…`), si no por el fichero `whisper-cli.backend` que el instalador deja junto al binario, y si no por la salida de `whisper-cli --version`. Si nada dice Vulkan o CUDA, es CPU.
- Usa el binario de whisper.cpp configurado o incluido y elige el encoder (sección 9.3).
- La prueba de arranque y el autotest usan **el mismo `Encoder` (argumentos y filtro final) que el render**, así que lo que se prueba es lo que se renderiza.
- En el primer arranque hace un **autotest** corto (transcribir y codificar unos segundos). Reglas (cambio de 2026-10):
  - **Encoder forzado** (`forcedEncoder` distinto de `auto`) que falla: el autotest **no se da por hecho** (se repite en el siguiente arranque), el worker queda en **No puede aceptar tareas** con el motivo genérico "la prueba de arranque ha fallado" (el detalle, en el log) y se **respeta tu elección** (no se cambia el `worker.yml`). Para volver a trabajar, arregla el driver o elige otro encoder.
  - **`auto` con GPU que falla**: **prueba el encoder por software**; si funciona, guarda `forcedEncoder: software` en `worker.yml` (§8.4), el autotest se da por hecho y el worker trabaja por CPU. Para volver a la GPU (por ejemplo, tras actualizar los drivers) se cambia a `auto` en la configuración.
  - **Si también falla el software**: no se guarda nada (el siguiente arranque vuelve a probar en `auto`), no se da por hecho y el worker queda en **No puede aceptar tareas**. Lo mismo si el encoder elegido ya era el de software y falla.
  - Un autotest interrumpido tampoco se da por hecho.
- Declara todo en el `HELLO`.
- **Worker que no puede trabajar** (cambio de 2026-10): si no encuentra ffmpeg o whisper-cli, o el autotest falla (salvo una GPU en `auto` que falla y pasa a CPU con éxito, que sí puede trabajar), se conecta igualmente pero anuncia **0 tareas en paralelo**, así el servidor no le asigna nada y no se gastan los intentos de los trabajos; la ventana y la bandeja pasan a **No puede aceptar tareas** con el motivo. En modo headless se para con un código de error (el servicio lo deja visible). Un autotest fallido se repite en el siguiente arranque.
- Descarga en el primer arranque el **modelo de Whisper** adecuado a su hardware, con verificación de hash.
  - Una descarga que se queda **60 s sin recibir datos** se corta con un error que dice el modelo y lo descargado, en vez de colgar el arranque. Si falla y ya hay otro modelo descargado, se usa ese (también cuando el modelo estaba elegido a mano) y se avisa.
- Si el arranque falla antes de que exista la ventana, el worker lo muestra en un **diálogo** con el error y dónde está el log, en vez de cerrarse sin decir nada.
- **whisper-cli** se vigila igual que ffmpeg (§9.2): su salida se lee aparte, así que el límite de tiempo y la cancelación funcionan aunque se cuelgue sin escribir nada, y el error lleva sus últimas líneas.

### 8.6 Ficheros locales
- El worker solo descarga lo que necesita: el **audio de un trozo** (TRANSCRIBE) o el **trozo de vídeo de un clip** (RENDER_CLIP). No hace falta caché del vídeo fuente completo. Todo se borra al entregar la tarea (sección 15).

## 9. Especificación de procesamiento

### 9.1 Transcripción (whisper.cpp)
- Salida **JSON completo** (`-ojf`) con timestamps **por palabra**: base de los subtítulos karaoke, de los cortes y del análisis con Claude.
- **Idioma**: configurable por trabajo (por defecto `es`; opción `auto`).
- **Modelos**: con `whisperModel: auto` el worker elige entre dos niveles (cambio de 2026-10): **`large-v3-turbo`** si su binario de whisper es Vulkan o CUDA (§8.5) y **`base`** si es de CPU. No hay un nivel aparte para gráficas integradas (*Pendiente*: `small`/`medium` en ellas). Cualquier otro modelo (`medium`, `small`…) se elige a mano en la configuración.
- **Ajustes a evaluar** (sección 18 y 19, fase 0):
  - Decodificación **greedy** (`-bs 1 -bo 1`): mucho más rápida que el beam search por defecto (5).
  - **VAD** (`--vad -vm` con el modelo Silero): salta tramos sin voz, reduce tiempo y alucinaciones en música.
  - `-dtw large.v3.turbo`: timestamps por palabra más precisos.
  - Para pruebas rápidas, `-d <ms>` limita la duración procesada.

### 9.2 Render de un clip (ffmpeg)
1. **Recorte**: el worker recibe el trozo del clip con margen (paso 10) y aplica `trim`/`atrim` exactos de los rangos a conservar; si hay partes eliminadas por dentro, se unen con `concat`.
2. **Fps** (sección 9.4) aplicado al principio del filtro.
3. **Vertical 9:16 (1080×1920) con fondo desenfocado** (si el original ya es vertical, solo se escala, sin fondo):
   - Fondo: el vídeo escalado a **baja resolución** (270×480), recortado, desenfocado y reescalado a 1080×1920 (mismo resultado visual, mucha menos CPU).
   - Primer plano: el vídeo escalado a 1080 de ancho, centrado.
   - Un `split` tras el `fps` para decodificar y reducir fotogramas una sola vez.
4. **Subtítulos karaoke**: el worker genera un **ASS** a partir de las palabras del JSON, con los **tiempos reajustados** al clip (restando el inicio y los tramos eliminados), y lo quema con el filtro `ass`.
   - **Se muestran grupos de palabras, no una sola**: cada línea en pantalla agrupa varias palabras y **resalta la que se está diciendo** (color o ligero aumento de tamaño), al estilo TikTok. Así se puede leer aunque hablen rápido.
   - **Reglas de agrupado** (configurables): hasta **3–5 palabras** o ~**25 caracteres** por grupo (máximo 2 líneas); corte preferente en **pausas y signos de puntuación**; nunca separar una palabra muy corta (artículo, preposición) de la siguiente; duración mínima en pantalla de cada grupo (~0,6 s) para que dé tiempo a leerlo.
   - El SRT de una palabra por línea (`-ml 1`) solo se usó en las pruebas; en producción se parte del JSON con los tiempos de cada palabra.
   - **Título en pantalla** (§10.3): una línea fija arriba y centrada durante todo el clip, en negrita blanca sobre una caja negra semitransparente, a ~14 % del alto desde arriba (por debajo de las pestañas superiores de las apps), con salto automático a dos líneas si no cabe. El servidor lo añade a los parámetros del `RENDER_CLIP` al repartir la tarea (`overlayTitle`), así que siempre va el texto más reciente; se puede desactivar en los ajustes (`overlay.enabled`).
   - **Altura**: configurable en los ajustes del superadmin (`subtitles.bottom-percent`, distancia desde abajo en % del alto), **25 % por defecto** (480 px de 1920): por encima de la franja inferior que tapan el texto y los botones de TikTok, Reels y Shorts. El servidor la manda en los parámetros de cada `RENDER_CLIP` (`subtitleMarginV`, en px), así que se cambia sin reinstalar el worker; un worker antiguo que no la lea usa su valor fijo.
5. **Codificación** con el encoder del worker y el bitrate según fps (sección 9.4). Audio AAC 160 kbps.
6. **Límite de tiempo**: un render que pasa de **30 min** se da por colgado (un driver de GPU bloqueado, por ejemplo): se mata ffmpeg y se envía `TASK_FAILED`. Una tarea cancelada (`TASK_CANCEL`, parar el worker) también mata su ffmpeg antes de borrar la carpeta de la tarea. Al cerrar el worker se matan los procesos externos que sigan en marcha (ffmpeg, whisper-cli). La salida de ffmpeg va a un fichero en esa carpeta y el error enviado al servidor lleva sus últimas líneas (como mucho unos 4 KB, para no pasar del tamaño de un mensaje WebSocket).

Comando de referencia para un clip sin partes eliminadas (validado en la prueba con subtítulos SRT; el definitivo usará ASS). `<margen>` es el desfase entre el inicio del trozo y el inicio real del clip:
```
ffmpeg -y -ss <margen> -i trozo.mp4 -t <duración> -filter_complex
  "[0:v]fps=30,split[a][b];
   [a]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,gblur=sigma=9,scale=1080:1920[bg];
   [b]scale=1080:-2[fg];
   [bg][fg]overlay=(W-w)/2:(H-h)/2,ass=clip.ass"
  -c:v h264_amf -b:v 8M -c:a aac -b:a 160k clip.mp4
```
El desenfoque usa `gblur` y no `boxblur`: `boxblur` es un filtro GPL y no está en la build LGPL de ffmpeg que se distribuye con el worker (§20). `gblur=sigma=9` equivale aproximadamente a `boxblur=10:2` (dos pasadas de radio 10).

### 9.3 Encoders por plataforma

| GPU / SO | Encoder | Notas |
|---|---|---|
| AMD / Windows | `h264_amf` | Validado en portátil con gráfica integrada |
| AMD / Linux | `h264_vaapi` | Requiere `-vaapi_device /dev/dri/renderD128` y `format=nv12,hwupload` al final del filtro (dentro del `filter_complex`, no como `-vf`: ffmpeg no deja combinarlos): el comando cambia. El dispositivo es **fijo** (`/dev/dri/renderD128`, la primera GPU); *Pendiente*: hacerlo configurable para equipos con varias |
| NVIDIA (ambos) | `h264_nvenc` | |
| Sin GPU | `libopenh264` | Fallback por CPU: es el que trae la build LGPL de ffmpeg del instalador (§16.5). `libx264 -preset veryfast` solo si es el único encoder por software de un ffmpeg propio |

El worker construye el comando según el encoder detectado (interfaz `Encoder`). La prueba de arranque, el autotest y el render usan el mismo `Encoder` (argumentos y filtro final), así que lo que se prueba es lo que se renderiza.

### 9.4 Fotogramas por segundo y bitrate
- Las plataformas aceptan entre ~23 y 60 fps.
- Modo configurable (global y por trabajo): **`auto`** (por defecto), `30` o `60`.
- **Estado real** (cambio de 2026-10): hoy el modo `auto` **siempre renderiza a 30 fps**. El worker no recibe los fps del original, y la decisión de 60 fps (pasos 2 y 3) está *Pendiente*. Para un gameplay a 60 fps hay que elegir `60` en el trabajo.
- **Modo `auto`** (diseño):
  1. Si el original es de ≤30 fps → se mantiene.
  2. Si es de 60 fps → se decide con: **metadatos** (categoría/juego de Twitch/Kick, título, etiquetas) + **`tipo_contenido` de Claude** (sección 10.2). Gameplay/deporte → 60; charla/podcast → 30.
  3. **Desempate por clip**: medición de movimiento real con ffmpeg (`mpdecimate` sobre una versión a baja resolución: muchos fotogramas casi idénticos → 30 fps).
- **Bitrate**: ~8 Mbps a 30 fps; ~12 Mbps a 60 fps (1080×1920).

### 9.5 Duración de los clips
- **La decide Claude** para cada clip según el contenido (sección 10.2), siempre en pausas naturales; ideal 30–90 s.
- **Límites duros** que el servidor valida aunque Claude proponga otra cosa (claves `clip.min-ms` y `clip.max-ms` de la tabla `setting`; no salen en la pantalla de Ajustes): mínimo ~15 s y máximo **3 min** mientras se publique en YouTube Shorts (por encima de 3 min deja de ser un Short). Un clip demasiado largo se **divide en partes** en finales de frase y uno demasiado corto se une a su vecino (§10.2); nunca se recorta en seco.
- Antes de publicar se valida contra el máximo de cada plataforma (en TikTok, `creator_info` devuelve el máximo permitido para la cuenta).

## 10. Análisis y metadatos con Claude

### 10.1 Cómo se llama a Claude
- Interfaz `LlmClient` con dos implementaciones:
  - **`claude -p`** (Claude Code headless) en el servidor, con salida JSON. Consume el **crédito mensual del Agent SDK** incluido en la suscripción (independiente de los límites del chat).
  - **API de Anthropic** con clave propia (pago aparte), por si el crédito se queda corto. Implementada (`ApiLlmClient`) pero **sin selector** (cambio de 2026-10): `CliLlmClient` es el `@Primary`, así que hoy siempre se usa `claude -p`; `ANTHROPIC_API_KEY` solo sirve como credencial de la propia CLI. *Pendiente*: un ajuste para elegir la API.
- Se ejecuta como proceso externo, igual que ffmpeg; la transcripción se pasa por stdin.
- Respuestas **siempre en JSON** con un esquema fijo; el servidor valida el JSON y reintenta si no cumple.

### 10.2 Análisis de contenido (paso 6)
Entrada: transcripción en **segmentos numerados** (id, inicio, fin, texto) + metadatos (título, categoría, plataforma, duración).

Salida — un **plan editorial** del vídeo entero:
- `contenido_inicio` / `contenido_fin`: ids de segmento donde empieza y acaba el contenido útil (sin intro ni outro).
- `segmentos_eliminar`: lista de ids o rangos de ids con motivo. **Solo relleno real**: patrocinios, "suscríbete", saludos y despedidas, silencios o tiempos muertos, y lo que no tiene que ver con el vídeo. Nunca contenido del tema aunque sea menos llamativo.
- `secciones`: el vídeo dividido en **secciones temáticas completas y en orden** (`titulo`, `resumen`), cada una con sus `clips` (`inicio`, `fin`: ids de segmento). Una sección corta es un clip; una larga se parte en una **serie** de clips seguidos (partes 1/n, 2/n…), cada uno cortado en una pausa natural y con continuidad con el siguiente.
- `tipo_contenido`: `gameplay`, `charla`, `deporte`, `tutorial`… (para la decisión de fps).

**Criterios que se le piden** (para que todos los clips tengan sentido): cada clip **se entiende por sí solo** — empieza donde arranca la idea (con la pregunta, el planteamiento o el contexto que necesita) y termina en su conclusión o remate, nunca a mitad de frase ni de razonamiento; las partes de una serie se leen seguidas sin saltos (cada una empieza justo donde acabó la anterior); duración ideal de **30 a 90 s**, sin pasar de los límites (§9.5): mejor una serie de partes completas que un clip que corta la historia.

**Cobertura** (ajuste `clips.coverage`):
- **`completa`** (por defecto): los clips cubren **todo** el contenido entre `contenido_inicio` y `contenido_fin` salvo `segmentos_eliminar`, sin saltarse nada.
- **`destacados`**: solo las mejores secciones, para directos de horas en los que publicarlo todo no tiene sentido.

**Validación editorial del servidor** (Claude propone, el servidor garantiza; todo en límites de segmento, que son pausas entre frases):
1. Clips en orden cronológico y sin solapes.
2. **Huecos** (cobertura completa): si a la propuesta le falta más de un 15 % del contenido, se repite la llamada una vez diciéndole a Claude qué tramos se ha dejado; lo que siga faltando se añade al clip vecino de la misma sección si cabe, o como clip propio.
3. **Relleno dentro de un clip** (`segmentos_eliminar` y SponsorBlock): el clip se parte a su alrededor y el relleno no sale en ningún clip.
4. **Clips largos** (> máximo): se dividen en partes de duración parecida, cortando en el final de frase (`.`, `?`, `!`) más cercano. Nunca se recorta un clip en seco.
5. **Clips cortos** (< mínimo): se unen al vecino de la misma sección si cabe.
6. Las partes de cada sección se numeran (`parte`/`de`) y se guardan en el clip con el título de la sección; los ajustes que hizo el servidor quedan en el análisis y se ven en la página del trabajo.

Si Claude falla tras los reintentos, se usa el troceado provisional por pausas (~60 s) y la página del trabajo lo avisa.

Reglas:
- Claude responde con **ids de segmento, nunca con tiempos**; el servidor traduce ids a tiempos reales y valida (dentro del vídeo, sin solapes, duraciones mínimas).
- **Señales de apoyo** que se combinan con Claude:
  - **SponsorBlock** (YouTube): intros, outros y patrocinios marcados por la comunidad.
  - **Tramos sin voz** al principio o al final (huecos en la transcripción o `silencedetect`/VAD): indicio de intro musical.
- **Modo revisión** (global y por trabajo): los cortes quedan en `AWAITING_REVIEW` hasta que los apruebes en el superadmin (los títulos y descripciones se pueden editar; los cortes, *Pendiente*, §12.2). Recomendado al principio; después, modo automático.

### 10.3 Título y descripción por clip (paso 9)
- Claude genera **un título y una descripción distintos por clip** a partir de su transcripción. La numeración de una serie ("Parte 2/3") **no la escribe Claude: la añade el servidor** (ver *Título en pantalla* y *Partes*), así nunca falta ni se equivoca.
- Mismo título y descripción en las tres plataformas.
- Idioma: el del vídeo.
- **Límites que Claude debe respetar** (el más restrictivo de las tres; el servidor valida y recorta) (*R10*):
  - **Título ≤ 100 caracteres** (límite de YouTube).
  - **Descripción** corta (≤ ~2.000 caracteres, por debajo del límite de 2.200 de TikTok e Instagram), con **3–5 hashtags** como máximo.
  - Sin caracteres que alguna plataforma rechace (YouTube no admite `<` ni `>` en el título).
- En modo revisión, títulos y descripciones también se pueden editar antes de programar.
- **Contexto que recibe Claude** para que cada clip tenga la mejor repercusión posible: título del vídeo original, **canal o autor** (`source_channel`, de `yt-dlp`), plataforma de origen, tipo de contenido (§10.2), duración del clip y su transcripción en texto (solo los tramos que se conservan).
- **Criterios** que se le piden: título con gancho en las primeras palabras (idealmente ≤ 60 caracteres, que es lo que se ve sin cortar en los feeds verticales), que sea fiel al contenido (sin clickbait engañoso ni mayúsculas sostenidas); primera línea de la descripción que resuma el clip con las palabras que la gente buscaría; **crédito al creador original** cuando se conoce el canal; y **3–5 hashtags** específicos del tema (personas, juego, programa, temática) más uno o dos amplios del nicho, en el idioma del vídeo, **sin hashtags de relleno** (`#fyp`, `#parati`, `#viral`…).
- **Título en pantalla** (`titulo_pantalla`): una frase corta (≤ 40 caracteres) que resume qué se está viendo y que el worker **graba encima del vídeo** durante todo el clip (§9.2). Se ve también en la miniatura del perfil. Las series las fija el plan editorial (§10.2): Claude recibe la sección y la parte de cada clip, usa el mismo nombre en toda la serie y **el servidor añade la numeración** ("La remontada · 2/3"), así nunca falla. La numeración va en el título en pantalla, en el **título de la publicación** y en la descripción (ver *Partes*).
- **Partes** (cambio de 2026-10: antes el título de la publicación no decía que era una parte, y quien veía la 2 no sabía que había una 1): en un clip de una serie el servidor añade al final del título ` (Parte 2/3)` en el idioma del vídeo (`Part`, `Partie`, `Teil`…; `Parte` por defecto), recortando el título de Claude si hace falta para no pasar de 100 caracteres, y, si la descripción no menciona ya la parte, le añade antes de los hashtags una línea `Parte 2 de 3 · el resto, en el perfil`. A Claude se le pide un título de como mucho 85 caracteres para que quepa el sufijo.
- Para poder detectar las series, **Claude recibe todos los clips del vídeo en una sola llamada** (cada uno con su texto, como mucho ~1.500 caracteres por clip) y devuelve los textos de todos (`{"clips": [{"id", "titulo", "descripcion", "hashtags", "titulo_pantalla"}]}`); con más de 30 clips se hace en tandas de unos 30 que **nunca parten una sección** (una serie entera va en la misma tanda). Si la llamada falla tras los reintentos, los clips quedan sin texto y la publicación usa como título el de la sección con su parte (o el del vídeo si no hay sección).
- **Contexto completo también al regenerar** (cambio de 2026-10): antes *Regenerar textos* solo mandaba los clips que aún se podían cambiar, y Claude escribía la parte 3 sin saber qué decían la 1 y la 2, con títulos repetidos o que no casaban. Ahora siempre recibe **todos** los clips del vídeo; los que ya no se pueden tocar (subidos o con el título en pantalla ya grabado) van marcados como `"fijo": true` con su texto actual, como referencia, y lo que Claude devuelva para ellos se ignora.
- **Prompt** (revisado en 2026-10 con las guías de prompting de Anthropic: rol, el *porqué* de cada regla, secciones en etiquetas XML, un ejemplo completo y una autocomprobación final) con lo que dicen las guías de las plataformas y los estudios de 2025–2026: en el feed vertical el título apenas decide si alguien se queda (eso lo hacen el primer segundo y el título en pantalla), pero sí pesa en la **búsqueda** (TikTok, Instagram y YouTube indexan el texto, los hashtags y lo que se dice en el vídeo), en el perfil y en las sugerencias, así que título y primera línea de la descripción llevan las **palabras que la gente buscaría** (nombres propios, juego, programa, tema); títulos concretos (quién + qué pasa), cada uno distinto de los demás del vídeo y fiel a **su** clip; nada de títulos genéricos ("Momento épico", "No te lo vas a creer"); crédito al creador original; pregunta para comentar solo si sale natural; y no inventar nada que no esté en la transcripción.
- Claude devuelve los hashtags aparte (`{"titulo", "descripcion", "hashtags"}`); el servidor los normaliza (empiezan por `#`, sin espacios, máximo 5, sin repetidos) y los añade al final de la descripción. En YouTube se envían además como **etiquetas** del vídeo (`snippet.tags`, sin `#`).
- Botón **"Regenerar textos"** en la página del trabajo: vuelve a pedir título y descripción para los clips que aún no han empezado a subirse (la publicación lee el texto en el momento de subir). El título en pantalla solo cambia en los clips que aún no se han renderizado: en los demás ya está grabado en el vídeo.

### 10.4 Planificación de publicaciones (*R2*)

**Modo de publicación** (ajuste global en el superadmin, sobrescribible por trabajo):
- **`inmediato`** (para pruebas): cada clip se publica en cuanto termina su render, respetando solo los topes de cada plataforma.
- **`claude`** (modo normal): Claude decide el calendario.

**Planificador con Claude** (modo `claude`):
- Se ejecuta **cada mañana** y **se replanifica** cuando cambia mucho la cola (p. ej. entran muchos clips nuevos) o una publicación falla.
- **Entrada**:
  - Clips listos en cola: cuántos, antigüedad, tipo de contenido, título.
  - **Historial reciente**: qué se ha publicado y cuándo en cada plataforma.
  - **Estadísticas** de las publicaciones anteriores por plataforma y hora (cuando existan).
  - **Topes** de cada plataforma y fecha/día de la semana (zona Europe/Madrid).
- **Salida (JSON)**: para hoy, la lista de clips con su hora `(clip, hora)`. **Cada clip sale a la misma hora en todas las plataformas** (ver *Plataformas a la par*), y el número de publicaciones al día se adapta a la cola: con 20 clips listos se pueden subir 5 al día en lugar de 2, para mantener siempre contenido saliendo y liberar la cola.
- **Qué puede aportar Claude**: al principio, conocimiento general de buenas prácticas por plataforma (no hay datos propios); con el tiempo, **los datos reales de tus publicaciones** (`post_stats`) son los que permiten ajustar los horarios de verdad. No es una predicción exacta: es una heurística que mejora con los datos.
- **Validación del servidor** (Claude propone, el servidor garantiza): horas futuras, dentro de los topes de cada plataforma, **separación mínima** entre publicaciones de una misma plataforma (configurable), un clip no se publica dos veces en la misma plataforma, y orden de clips de un mismo vídeo respetado.
- **Fallback**: si Claude falla, se usan franjas por defecto configurables (p. ej. 13:00 y 20:00) para no parar la publicación.
- En el superadmin se ve el **plan de hoy** y se puede editar, adelantar o cancelar cualquier publicación.

**Concreción de la implementación**:
- **Cuándo**: a las **07:00** (Europe/Madrid) planifica el resto del día; si a esa hora el servidor estaba apagado, en cuanto vuelva (se comprueba cada 10 minutos). **Replanifica** cuando, desde el último plan, hay **3 o más clips nuevos listos**, o al pulsar *Replanificar* en el superadmin. Replanificar **solo añade**: coloca los clips nuevos en los huecos que quedan hoy y **nunca mueve ni borra** lo ya programado (las horas ocupadas cuentan para la separación y los topes). Antes sustituía lo que no había empezado, y eso aplazaba sin fin: cada clip nuevo hacía que Claude volviera a colocar las publicaciones que estaban a punto de salir, siempre al menos 10 minutos más tarde; y una publicación de TikTok aplazada por el tope de borradores arrastraba consigo las de las demás plataformas del mismo clip. "Clips nuevos" son los que entraron en la cola después del último plan (el plan guarda qué clips vio), no los que se modificaron. Las publicaciones fallidas las gestionan los reintentos (§11.3), no provocan replanificación.
- **Unidad**: el **clip**. Claude decide **cuántos** clips salen hoy y **a qué hora**; **cuáles** los marca el orden de la cola (ver *Cola en orden de publicación*). Un clip elegido sale en **todas** las plataformas conectadas **a la misma hora**: el servidor busca la primera hora, desde la que pidió Claude, que respete la separación mínima en todas a la vez, y si en alguna no cabe (tope o fin del día), el clip entero queda para otro día (así un clip nunca queda publicado a medias).
- **Plataformas a la par** (cambio de 2026-10, *"que todos los sitios se suban a la vez y en las mismas cantidades"*): antes cada plataforma podía llevar su propia hora y, cuando una se quedaba atrás (TikTok aplazado por los borradores, una publicación fallida), las demás seguían a su ritmo y se acumulaba cola en una sola. Ahora:
  1. **misma hora**: Claude da una hora por clip y todas las plataformas la usan (si en una plataforma ya había otra publicación cerca de esa hora, se mueve el clip en todas, no solo en esa);
  2. **mismas cantidades**: el plan mira cuántas publicaciones lleva **atrasadas** cada plataforma (programadas cuya hora pasó hace más de 30 minutos, aplazadas por un límite o fallidas). La diferencia entre la más atrasada y la menos atrasada se **resta de los clips de hoy**, para que las demás no se escapen. Por ejemplo, si TikTok lleva 3 por detrás, hoy salen 3 clips menos, hasta que se pongan al día (lo subes a mano, lo reintentas o lo cancelas). Claude lo ve en `ritmo` y el plan lo explica en sus ajustes;
  3. ***Publicar ahora* y *Cambiar hora* mueven el clip en todas las plataformas** donde aún no ha empezado a subirse, no solo en la fila que pulsas.
- **Ventana**: de ahora + 10 minutos hasta las 23:30 de hoy. Lo que no cabe se queda en cola (`RENDERED`) para el plan siguiente.
- **Topes diarios** por plataforma (contando lo ya programado o publicado hoy): **YouTube 10** (desde diciembre de 2025 una subida cuesta ~100 unidades de cuota en lugar de ~1.600, y desde junio de 2026 las subidas tienen su propio cupo, así que el tope ya no lo marca la cuota sino la prudencia), **TikTok 10** e **Instagram 25** (muy por debajo de las 100 de la API, para no parecer spam). Si una plataforma (no de borradores) tiene una publicación aplazada por su límite, ese día no se le programan más.
- **TikTok y el tope de borradores** (cambio de 2026-10): antes el plan no le daba a TikTok más de 5 publicaciones en 24 h (los borradores pendientes que admite la API), y eso frenaba todo el plan, porque un clip sale en todas las plataformas o en ninguna. Ahora TikTok se planifica como las demás; cuando la API rechaza un borrador por ese tope, la publicación se sigue aplazando y además **te llega un email para subirlo a mano** (§11.3, §12.3).
- **Ritmo según la cola** (cambio de 2026-10, *"que suba más al día si hay cola para muchos días"*): el plan calcula para cuántos días hay contenido (clips listos o renderizándose ÷ `publish.clips-per-day`, **3** por defecto). Si pasa de `publish.backlog-days` (**5** días por defecto) entra en **modo acelerado** y el límite de clips de hoy sube a `publish.max-clips-per-day` (**6** por defecto); si no, el límite es el ritmo normal. Claude decide cuántos publica dentro de ese límite (puede ser menos si no hay buenas horas libres) y el servidor no deja pasar del límite ni de los topes de cada plataforma. Claude recibe el cálculo (`ritmo`) para entender por qué tiene más margen.
- **Público y calendario**: el ajuste `publish.audience` describe a quién va dirigido el canal (por defecto *"España (hora peninsular), público hispanohablante general"*) y Claude elige las horas para ese público. Recibe además el contexto del día: si es **fin de semana o festivo nacional** en España (fijos y Viernes Santo, calculado), la **temporada** (verano, Navidad, Semana Santa, vuelta al cole) y las **fechas especiales** (Nochebuena, Nochevieja, Reyes) en las que la noche no es buena hora.
- **Conocimiento de horarios en el prompt** (resumen de los estudios de 2025–2026 de Metricool, Buffer y Sprout Social, ajustados a los horarios españoles, que van 1–2 h más tarde que el resto de Europa: comida 14–16 h, cena 21–22:30 h): franjas fuertes al **mediodía (13:00–15:30)** y por la **tarde-noche (19:00–23:00)**, la mañana (8:00–9:30) como secundaria; TikTok rinde más de tarde-noche y los domingos y lunes; Reels a mediodía y a partir de las 20:00; en Shorts la hora pesa menos porque el feed lo reparte durante días (como la hora es la misma en todas, Claude elige la que mejor encaja en conjunto, normalmente de 19:00 a 22:00); fines de semana y festivos con mañanas más tardías (11:00–13:00). Son puntos de partida: **los datos propios (`vistas_por_hora_de_publicacion`) mandan** en cuanto hay varias publicaciones en una hora.
- **Separación mínima** entre dos publicaciones de la misma plataforma: ajuste `publish.min-gap-minutes`, **90 minutos** por defecto. **Franjas por defecto** del fallback: ajuste `publish.default-slots`, `13:00,20:00`.
- **Cola en orden de publicación** (cambio de 2026-10; antes Claude elegía qué clips salían y, aunque el servidor ponía primero "los vídeos empezados", en cuanto un plan programaba un solo clip de un vídeo nuevo había dos vídeos empezados y se intercalaban): antes de cada plan el servidor revisa lo ya publicado y programado y ordena la cola:
  1. primero los vídeos **en curso** (con algún clip publicado o programado), del que **empezó antes** al último (la hora de su primera publicación), cada uno con los clips que le quedan en orden;
  2. después los vídeos sin empezar, del más antiguo al más reciente.
  Si Claude pide *n* clips, salen **los *n* primeros de la cola** con las horas que dio, de la más temprana a la más tardía: un vídeo se termina antes de empezar el siguiente y los clips de un vídeo nunca se mezclan con los de otro. Claude recibe la cola ya en ese orden y, por cada vídeo, cuántos clips lleva publicados y cuándo fue el último. En modo inmediato las publicaciones de un vídeo se crean con horas escalonadas por índice, para que salgan en orden.
- **Sin mezclar vídeos** (cambio de 2026-10; antes se veían partes desordenadas y vídeos intercalados): el servidor garantiza además que
  1. si un clip de un vídeo se cae del plan (no cabía en alguna plataforma), **también se caen los siguientes de ese vídeo**: antes podía salir la parte 2 sin la 1;
  2. los clips van siempre en el orden de la cola (las horas que eligió Claude se mantienen; solo se reparten en ese orden);
  3. al subir, una publicación **espera a que la anterior del mismo vídeo en esa plataforma haya terminado** (§11.3), así un aplazamiento de la parte 1 no deja pasar a la 2.
- **Entrada a Claude** (JSON por stdin): fecha, día de la semana y hora actual; plataformas conectadas con su tope restante y las horas ya ocupadas hoy; la cola de clips (id corto, título, duración, vídeo, tipo de contenido, horas en cola), como mucho los 60 más antiguos; lo publicado en los últimos 7 días (plataforma y hora); y las vistas medias por hora de publicación de cada plataforma (§12.4) cuando haya datos.
- **Salida**: `{"publicaciones": [{"clip": "c1", "hora": "HH:MM"}], "explicacion": "<breve>"}` (si Claude devolviera horas por plataforma, se toma la más temprana de cada clip). El plan se guarda en `publication_plan` (uno por día; una replanificación lo sobrescribe) con la entrada resumida, la salida y los ajustes que hizo el servidor.
- **Estados**: el trabajo pasa a `SCHEDULED` en cuanto tiene algún clip programado y solo se completa cuando no le queda ningún clip sin programar ni publicar.
- **Superadmin**: en *Publicaciones*, tarjeta "Plan de hoy" (origen Claude o franjas por defecto, explicación de Claude, ajustes del servidor y botón *Replanificar*); en cada publicación programada, *Publicar ahora* y *Cambiar hora* (que mueven el clip en todas sus plataformas pendientes) y *Cancelar* (solo esa). En la página de un trabajo en `RENDERED` se indica qué está esperando.

### 10.5 Corrección de la transcripción

whisper confunde palabras (nombres propios, jerga, palabras mal pronunciadas). Tras unir la transcripción (paso 5) y antes del análisis (paso 6), Claude la corrige; mejora a la vez los subtítulos y el texto que reciben los pasos 6 y 9. Se aplica en los dos modos de clips.
- **Los tiempos no se tocan**: cada palabra conserva su `startMs`/`endMs`, que es lo que sincroniza el karaoke. Claude recibe cada segmento numerado con su lista de palabras y devuelve **solo los segmentos que cambian**, cada uno con **el mismo número de palabras** en el mismo orden (puede corregir una palabra, no unir, partir, añadir ni quitar).
- **Validación del servidor**: el id existe, el número de palabras coincide, ninguna queda vacía ni desmesurada; un segmento que no cumple se descarta y se queda como lo dejó whisper.
- **Lotes** de ~150 segmentos por llamada para no depender de una respuesta enorme en vídeos largos; un lote que falla tras los reintentos se deja sin corregir.
- **Nunca bloquea**: si Claude falla del todo, se sigue con la transcripción original.
- Se marca en `transcript.words_json` (`correctedByClaude`) para no repetirla al reintentar el paso, y se puede desactivar en los ajustes.

## 11. Publicación en redes

### 11.1 Plataformas

| | **TikTok** | **Instagram Reels** | **YouTube Shorts** |
|---|---|---|---|
| API | Content Posting API (Direct Post, `FILE_UPLOAD` por chunks; o Upload API como borrador) | Graph API: contenedor `media_type=REELS` → esperar `FINISHED` → `media_publish` | Data API v3, `videos.insert` resumable |
| Requisitos de cuenta | App de desarrollador | Cuenta **profesional (Business/Creator)** + app de Meta con **Instagram API con login de Instagram** (no requiere Página de Facebook) | Proyecto de Google Cloud + OAuth |
| Origen del vídeo | El servidor sube el fichero | Instagram descarga el vídeo de una **URL pública** | El servidor sube el fichero |
| Programación nativa | No (el scheduler llama a la hora prevista) | No | **Sí** (`status.publishAt`) |
| Sin auditoría | Solo `SELF_ONLY` y la **cuenta debe ser privada al publicar**; alternativa: subir como **borrador** y publicar a mano | Revisión de permisos de la app de Meta | Vídeos en **privado**; se hacen públicos a mano en YouTube Studio |
| Límites | Topes diarios por creador (`creator_info`) | Límite diario de publicaciones por API | Cuota diaria de subidas por proyecto (de sobra para este volumen) |

### 11.2 Estrategia
- Empezar con las **APIs oficiales** para probar (TikTok como borrador, Instagram directo, YouTube en privado).
- Interfaz `Publisher` para poder pasar a un **intermediario** (Upload-Post, Ayrshare…) sin tocar el resto.
- **Refresco automático de tokens** OAuth (los tokens de larga duración de Instagram caducan a los ~60 días si no se refrescan).
- **Instagram** (API con login de Instagram, `graph.instagram.com`): el código OAuth da un token corto que se cambia por uno **largo de 60 días**; ese token se renueva cuando le quedan **menos de 7 días** (solo se puede renovar mientras sigue vigente y con más de 24 h). Para publicar: se comprueba `content_publishing_limit` (100 publicaciones por API cada 24 h; si se ha alcanzado, se reintenta más tarde), se crea el contenedor `REELS` con la **URL firmada** del clip en la ruta `media` (§3.2), se espera a `status_code=FINISHED` y se publica con `media_publish`. Pie = título + descripción (máx. 2.200 caracteres). El enlace de la publicación (`permalink`) se pide a la API y se guarda.
- **Listas de reproducción** (cambio de 2026-10; en el perfil lo más nuevo sale primero, así que la parte 1 queda debajo de la 2 y pasar de una a otra era pesado): en **YouTube** cada vídeo de origen tiene **su lista**, con sus clips en orden, para que quien vea una parte tenga todas con un toque. ClipHive la crea (`playlists.insert`, **pública**) al subir el primer clip del vídeo, con el título del vídeo y el crédito al canal, y añade cada clip al final en cuanto YouTube devuelve su id (`playlistItems.insert`); como los clips de un vídeo se suben en orden (§10.4, §11.3), la lista queda en orden. También se añade un clip marcado como *subido a mano* con un enlace de YouTube. Mientras los vídeos sigan en privado (sin auditoría, §11.4) la lista no los enseña a nadie; aparecen en cuanto se hacen públicos. Cuesta 50 unidades crear la lista y 50 cada clip, del cupo general (no del de subidas). Un fallo al crear la lista o al añadir un clip **nunca hace fallar la publicación**: queda en el log. Necesita el scope `youtube` (gestionar la cuenta); una conexión hecha antes no lo tiene y hay que **reconectar YouTube** en *Cuentas*. Se puede desactivar con el ajuste `publish.playlists`. El id de la lista se guarda en el trabajo (`video_job.playlist_ids_json`, por plataforma). TikTok también tiene listas, pero su API no permite crearlas; Instagram no tiene.
- **TikTok** (Content Posting API, `open.tiktokapis.com/v2`): OAuth web con `client_key`/`client_secret`; el **token de acceso dura 24 h** y se renueva con el **refresh token (365 días)**, que puede cambiar en cada renovación. Hasta la auditoría se publica **como borrador** (*upload*, scope `video.upload`): `POST /post/publish/inbox/video/init/` con `FILE_UPLOAD` devuelve un `publish_id` (el id externo) y una `upload_url` (válida 1 h) a la que se sube el fichero con `PUT` por trozos (`Content-Range`; trozos de 5–64 MB salvo el último, que absorbe el resto hasta 128 MB; un fichero de menos de 5 MB va en un único trozo). Después se consulta `POST /post/publish/status/fetch/`: `SEND_TO_USER_INBOX` (el borrador ya está en la bandeja de TikTok) o `PUBLISH_COMPLETE` cuentan como `PUBLISHED`; `FAILED` con `fail_reason` es error (solo `internal` es reintentable). **En modo borrador la API no acepta título ni descripción**: se ponen a mano al publicar el borrador, y por eso el aviso (§11.4) incluye el texto listo para pegar. *Direct Post* (`video.publish`) queda para después de la auditoría.

### 11.3 Robustez de la publicación (*R4*, *R11*)
- **Idempotencia**: antes de subir se guarda `UPLOADING`, y en cuanto la plataforma devuelve un id (`publish_id` de TikTok, id de contenedor de Instagram, id de vídeo de YouTube) se guarda en BD. Si el servidor se reinicia a mitad, **consulta el estado con ese id** en lugar de volver a subir, para no publicar dos veces.
- **Límites**: el scheduler consulta los topes antes de publicar (`content_publishing_limit` en Instagram, cuota en YouTube; `creator_info` en TikTok solo hará falta con *Direct Post*) y, si se alcanzan, **retrasa** la publicación en lugar de fallar: se reprograma **una hora más tarde sin gastar un intento**. En TikTok en modo borrador el tope es de **5 borradores pendientes en 24 h** (`spam_risk_too_many_pending_share`): borradores que aún no has publicado desde la app. Una publicación aplazada muestra el motivo en Publicaciones y en la página del trabajo ("Aplazada: …"), también al pulsar *Publicar ahora*.
- **Subir a mano cuando la plataforma no deja** (cambio de 2026-10): la primera vez que una publicación se aplaza por un límite de la plataforma (en TikTok, los 5 borradores pendientes), ClipHive **te manda un email** (aviso `MANUAL_UPLOAD`, §12.3) con la plataforma, el motivo, el título y la descripción listos para pegar, el enlace para **descargar el clip** desde el superadmin y el enlace a *Publicaciones*. Solo un email por publicación (`manual_alert_sent`), no uno por hora. La publicación **se sigue aplazando** (cada hora vuelve a intentarlo) hasta que pase una de dos cosas:
  - la plataforma la acepta (por ejemplo, porque has publicado o descartado borradores): sigue el camino normal;
  - pulsas **Ya lo subí a mano** en Publicaciones (o en la página del trabajo) y, si quieres, pegas el **enlace** de la publicación: pasa a `PUBLISHED` con `published_manually`, deja de intentarse y se ve como "SUBIDA A MANO" con su enlace. Si es un enlace de YouTube, ClipHive saca el id del vídeo y así también recoge sus estadísticas. El botón vale también para una publicación `FAILED`.
  El email no adjunta el vídeo (decenas de MB, por encima de lo que aceptan muchos proveedores de correo): lleva el enlace de descarga, y el clip también se puede **descargar desde Publicaciones** (en cada publicación programada o fallida) y desde la página del trabajo, en cualquier estado mientras su fichero exista (hasta que la limpieza lo borra, §15).
  Si la subiste a mano, márcalo: si no, cuando la plataforma vuelva a aceptar borradores ClipHive la subiría otra vez.
- **Confirmar que está publicado** (cambio de 2026-10): un borrador que llega a la bandeja de TikTok o un vídeo subido en privado a YouTube **no está publicado** hasta que lo terminas tú en la app. ClipHive no puede saber si lo hiciste (y en TikTok el borrador a veces falla al abrirlo y desaparece sin llegar), así que la publicación queda **pendiente de confirmar** y aparece arriba en *Publicaciones* ("Falta publicar") y en la página del trabajo, con:
  - **Ya está publicado** (con el enlace si quieres): quita la marca; en TikTok, el enlace queda como enlace de la publicación.
  - **Descargar**: el clip, para subirlo a mano si el borrador se perdió.
  - **Reenviar borrador** (solo TikTok): vuelve a mandar el mismo clip a la bandeja como borrador nuevo, ya, sin replanificar nada. Cuenta para el tope de borradores pendientes como cualquier otro.
  Mientras falte confirmar, el fichero del clip **no se borra** y el trabajo no pasa a `COMPLETED` (§15). Instagram publica directamente, así que no pide confirmación; YouTube solo mientras suba en privado.
- **Orden al subir**: una publicación que ya toca no empieza mientras otra **anterior del mismo vídeo en la misma plataforma** siga pendiente (`SCHEDULED`, `UPLOADING` o `PROCESSING`); se ve como "ESPERA A LA ANTERIOR". Una anterior `FAILED` no bloquea (se ve en rojo y se reintenta a mano).
- **Subidas en paralelo por plataforma**: cada plataforma tiene su propio hilo de subida, así que una subida lenta (Instagram espera a que procese el vídeo) no retrasa las de las demás; dentro de una plataforma se sube en orden. Cualquier error inesperado de una subida queda en el log y marca la publicación para reintento; una publicación que se quedara en `UPLOADING` sin subida en curso (p. ej. por un fallo del hilo) se recupera igual que tras un reinicio.
- **Reintentos** con espera creciente para errores temporales; tras N fallos, `FAILED` con el error visible en el superadmin.
- **Subida cortada sin id**: si el servidor se reinicia con una publicación en `UPLOADING` y aún sin `external_id`, no hay forma de saber si la plataforma la recibió; pasa a `FAILED` con ese motivo y se reintenta a mano, en vez de arriesgarse a publicar dos veces.
- **Nunca se sube dos veces**: en cuanto la plataforma acepta el vídeo, ningún error posterior (guardar el id, el log, la consulta de estado) devuelve la publicación a la cola. Si no se pudo guardar el id, pasa a `FAILED` diciendo con qué id se subió, para revisarlo en la app antes de reintentar.
- **Procesando demasiado**: una publicación que lleva **24 h** en `PROCESSING` y la plataforma sigue diciendo que procesa (o no se le puede preguntar) pasa a `FAILED` (token revocado, borrador sin terminar…), en vez de consultarse para siempre. Siempre se pregunta antes: tras un día con el servidor apagado puede que ya esté publicada.

### 11.4 Consecuencias de no estar auditado (*R3*)
- **TikTok**: en modo borrador, el clip llega a la bandeja de TikTok **cuando se sube**, no a la hora programada; publicarlo es manual. En el superadmin se ve como **"En borradores"**, no como publicado: el vídeo está en la app de TikTok (aviso en la **bandeja de entrada**), pendiente de que lo termines. Para que respete el calendario, el servidor sube el borrador a la hora del hueco y te avisa **por email** (aviso `DRAFT_READY`, §12.3, al destinatario de los avisos) con el título y la descripción listos para pegar, ya que la API de borradores no los admite. Si TikTok no admite más borradores, el aviso es el de subida a mano (§11.3).
- **YouTube**: el bloqueo a privado de los proyectos sin auditar hace que `publishAt` **no llegue a publicarlo en público**; hasta la auditoría, el paso a público es manual. La auditoría se pide con el formulario *YouTube API Services – Audit and Quota Extension*. Cuando Google la apruebe, el ajuste **"YouTube: app auditada"** del superadmin hace que los clips se suban directamente como **públicos** (a la hora que marque el plan, §10.4). Mientras no esté aprobada debe seguir desactivado: subir como público sin auditoría no sirve (Google lo deja en privado igualmente).
- **Instagram**: publicación real a la hora prevista en cuanto la app tenga los permisos.
- Es decir: hasta pasar las auditorías, **solo Instagram será automático de punta a punta**.

### 11.5 Alta como desarrollador en cada plataforma

Requisito común: **páginas públicas de política de privacidad y condiciones de uso** (TikTok las pide para crear la app y Meta para pasarla a modo publicado). Las sirve el propio servidor en `https://<superadmin>/legal/privacy` y `/legal/terms` (p. ej. `https://cliphive.example.com/legal/privacy`; español e inglés; ficheros en `server/src/main/resources/legal/`). Son las únicas rutas del superadmin sin login: Spring Security las deja pasar y en Cloudflare Access hay una aplicación **Bypass** solo para `cliphive.example.com/legal/*`, de modo que el resto del dominio sigue protegido (`docs/DEPLOY.md`).

Quién es el **responsable** que nombran (nombre y email de contacto y de borrado de datos) sale de `CLIPHIVE_OPERATOR_NAME` y `CLIPHIVE_OPERATOR_EMAIL` (cambio de 2026-10): los ficheros son plantillas, así que cada instalación nombra a quien la gestiona y no al autor del proyecto. Sin esas variables las páginas dicen *sin configurar* y el arranque lo avisa en el log.

**TikTok** (developers.tiktok.com)
1. Crear una app (tipo web) con nombre, icono, descripción y las URLs de privacidad y condiciones.
2. Añadir **Login Kit** y **Content Posting API**; scopes `user.info.basic`, `user.info.stats` (seguidores, likes y vídeos de la cuenta, §12.4) y `video.upload` (borradores). `video.publish` (*Direct Post*) solo tras la auditoría.
3. Configurar la URL de redirección OAuth del servidor.
4. Usar el **sandbox**: tiene **su propio client key y secret** (son los que van al `.env`) y en él se añade la cuenta propia como *target user*; así funciona para uso propio sin revisión. La revisión de la app y la **auditoría** para publicar en público son pasos aparte (*R3*).

**Instagram** (developers.facebook.com)
1. Pasar la cuenta de Instagram a **profesional** (Creator o Business) desde la app de Instagram.
2. Crear una app de Meta con el producto **Instagram** → *API con login de Instagram*; permisos `instagram_business_basic` e `instagram_business_content_publish`.
3. Añadir tu cuenta de Instagram como **tester** de la app y aceptar la invitación.
4. En **modo desarrollo**, las cuentas con rol en la app suelen poder usar los permisos sin App Review: suficiente para uso propio. La revisión solo haría falta para cuentas ajenas.

**YouTube** (console.cloud.google.com)
1. Crear un proyecto y activar **YouTube Data API v3**.
2. Configurar la **pantalla de consentimiento OAuth** (tipo externo) con los scopes `youtube.upload`, `youtube` (listas de reproducción, §11.2), `youtube.readonly` (canal y contadores) y `yt-analytics.readonly` (retención y tiempo de visualización, §12.4).
3. Crear credenciales OAuth (aplicación web) con la URL de redirección del servidor.
4. **Ojo**: con la app en estado *Testing*, los **refresh tokens caducan a los 7 días** y habría que reconectar cada semana. Pasarla a *En producción* (aunque no esté verificada, mostrando el aviso de Google) evita esa caducidad. Los vídeos seguirán en privado hasta la auditoría (*R3*).

### 11.6 Cuentas
- Una misma identidad en las tres plataformas. Cada clip se publica en **todas las cuentas conectadas**, con el **mismo título y descripción** y a la **misma hora en todas** (§10.4, *Plataformas a la par*, cambio de 2026-10).

## 12. Web superadmin

### 12.1 Tecnología y acceso
- **Thymeleaf + HTMX**, servida por el servidor Spring Boot (puerto admin). Sin proyecto frontend aparte. **Responsive**. Los gráficos de Estadísticas se dibujan a mano (SVG con un poco de JavaScript en la plantilla y barras en HTML), sin Chart.js ni otra librería de gráficos (cambio de 2026-10).
- HTMX para refrescar estados sin JavaScript propio (`hx-get` + `hx-trigger="every 5s"`, `hx-post` para acciones); **SSE** para los logs en directo.
- Acceso desde cualquier sitio por **Cloudflare Tunnel**, con dos capas:
  1. **Cloudflare Access**: solo tu cuenta llega a la página de login.
  2. **Login propio** con **2FA (TOTP)**, rate limiting y bloqueo tras varios fallos.
     - 5 contraseñas fallidas bloquean esa IP 15 min, y se rechaza antes de comprobar la contraseña. La IP es la última de `X-Forwarded-For`, la que añade el proxy; las anteriores las puede escribir el cliente.
     - 5 códigos TOTP fallidos bloquean el segundo paso 15 min **por cuenta**, no por IP: quien llega ahí ya tiene la contraseña.
     - Con solo la contraseña se llega al formulario del código y a nada más: activar, cambiar o quitar el 2FA exige la sesión completa, y con el 2FA activo hay que desactivarlo antes de configurarlo de nuevo.
     - La cookie de sesión va marcada `Secure` y `SameSite=Lax`: el navegador llega por HTTPS aunque cloudflared hable HTTP con el servidor. Los perfiles locales (`dev`, `embedded`) la dejan sin `Secure`.
- **Una sola cuenta**, sin endpoint de registro: se crea en el primer arranque desde variables de entorno; cambiar `CLIPHIVE_ADMIN_PASSWORD` después no hace nada.
  - **Cambiar la contraseña** (cambio de 2026-10): en *Ajustes → Seguridad*, con la contraseña actual, la nueva dos veces (al menos 12 caracteres y distinta de la actual) y el código 2FA si está activo. Como el login, 5 intentos fallidos bloquean el cambio 15 min.

### 12.2 Funcionalidades
- **Nuevo trabajo**: pegar enlace + opciones (idioma, fps, modo revisión, modo de clips automático/manual, audio original o doblado). En *Idioma*, *Auto* significa el idioma por defecto de los ajustes (no hay detección). La casilla *Revisión* empieza según el valor por defecto, y **desmarcarla desactiva de verdad la revisión** en ese trabajo (cambio de 2026-10: el formulario manda el marcador oculto `_reviewMode`, así una casilla desmarcada llega como `false`); solo un cliente que no sea el formulario y omita el campo recibe el valor por defecto de los ajustes.
- **Selección manual** (§4.1): reproductor del vídeo fuente en el detalle del trabajo, marcar inicio/fin, lista de tramos y confirmar.
- **Trabajos y colas**: estado de cada vídeo, clips y tareas; cancelar, reintentar (*Pendiente*: cambiar la prioridad, §6.2). **Eliminar** un trabajo lo borra de la base de datos (clips, tareas, publicaciones con sus estadísticas, transcripción y análisis; los logs se conservan) y borra su carpeta del disco (`jobs/<id>`: vídeo fuente, audio y clips). Si sigue en marcha se cancela primero; no se permite mientras alguno de sus clips se esté subiendo a una plataforma. Lo ya publicado sigue en las plataformas.
  - **Cancelar** (trabajo no terminado): el trabajo pasa a `CANCELLED`, sus tareas pendientes o en curso a `CANCELLED` (con `TASK_CANCEL` al worker que la tenga) y sus clips sin terminar a `CANCELLED`.
  - **Reintentar tareas fallidas** (trabajo en curso con tareas en `FAILED`): esas tareas vuelven a `PENDING` (§5.3).
  - **Volver a lanzar** (trabajo terminado: `FAILED`, `CANCELLED`, `REJECTED`): crea un trabajo nuevo con el mismo enlace y opciones; el anterior queda como histórico.
- **Revisión**: ver los cortes propuestos por Claude, editar títulos y descripciones y **aprobar** (`AdminClipReviewController`). *Pendiente*: editar los cortes (cambio de 2026-10: el documento lo daba por hecho).
- **Workers**: alta (token mostrado una vez) y revocación; workers conectados con su tarea actual, estado, progreso y último heartbeat. *Pendiente*: mostrar sus capacidades (ya se guardan en `worker.last_capabilities`) y el historial de desconexiones (`worker_session`).
- **Logs**: los eventos que registra el servidor (`LogEventService`), filtrables por nivel, componente y trabajo; en directo por SSE. *Pendiente*: filtrar por worker.
- **Publicaciones**: **plan de hoy** generado por Claude y calendario de próximas subidas por plataforma; tamaño de la cola de clips listos; estado de cada publicación; editar, adelantar, reprogramar o cancelar; **Ya lo subí a mano** (con el enlace, §11.3); botón **"replanificar ahora"**.
- **Estadísticas**: métricas de las publicaciones y de las cuentas con gráficos (§12.4).
- **Cuentas conectadas**: TikTok, Instagram y YouTube; estado del token; reconexión OAuth.
- **Ajustes**: valores por defecto (idioma, fps, modo revisión), **corrección de la transcripción con Claude** (§10.5, activada por defecto), **cobertura** de los clips (§10.2), **modo de publicación** (`inmediato` / `claude`), separación mínima entre publicaciones, franjas por defecto (fallback), **ritmo** (clips al día normal y máximo, días de cola para acelerar) y **público** al que va dirigido (§10.4), **lista de reproducción por vídeo** en YouTube (§11.2), **YouTube: app auditada** (§11.4), duración máxima del vídeo fuente (**2 h**), timeout de tarea atascada sin progreso (§6.5), retención de logs y de ficheros con espacio en disco y **Limpiar ahora** (§15), **avisos por email** (umbral de cola, destinatario, recordatorio), **altura de los subtítulos** y **título en pantalla** (§9.2), y **cambiar la contraseña** (§12.1). Los límites de duración de clip (`clip.min-ms`/`clip.max-ms`, §9.5) no están en la pantalla, y el máximo de intentos de una tarea es fijo en el código (3 fallos / 10 desconexiones, §6.3) (cambio de 2026-10).

### 12.3 Avisos por email

**Aviso de cola baja** (configurable en el superadmin):
- **Umbral X**: si el número de **clips pendientes de publicar** (renderizados o en proceso, aún no publicados: estados `PLANNED`, `RENDERING`, `RENDERED` y `SCHEDULED`) baja de X, se envía un email avisando de que hay que enviar enlaces. **X = 0 desactiva el aviso**, igual que dejar vacío el destinatario.
- **Destinatario**: correo configurable desde el superadmin.
- El email incluye cuántos clips quedan y una estimación de **para cuántos días hay contenido**: clips pendientes ÷ clips publicados por día en los **últimos 7 días** (sin publicaciones en esa ventana no hay estimación), con enlace al superadmin.
- **Sin spam**: se envía una vez al cruzar el umbral hacia abajo; se vuelve a activar cuando la cola supera X de nuevo. Opcionalmente, un **recordatorio** cada N horas mientras siga por debajo (configurable, desactivado por defecto).
- Botón **"enviar email de prueba"** en los ajustes.
- La comprobación corre cada **5 minutos**. Un envío fallido queda en `notification` como `FAILED` y se reintenta como pronto una hora después (no un intento cada 5 minutos con el SMTP caído).

**Envío**: SMTP con Spring Mail (STARTTLS en el puerto 587; SSL implícito si el puerto es 465). Servidor, puerto, usuario, contraseña y remitente del SMTP por **variables de entorno** (son secretos); el destinatario y el umbral, desde el superadmin. Sin `CLIPHIVE_SMTP_HOST` los avisos quedan desactivados y los ajustes lo indican.

**Proveedor elegido: [Resend](https://resend.com)** por SMTP (`smtp.resend.com`, puerto 587, usuario `resend`, contraseña = una API key de Resend), con el dominio propio verificado en Resend para enviar desde una dirección suya (p. ej. `cliphive@example.com`). Se usa su SMTP y no su API HTTP para no atar el código a un proveedor: cambiar a otro (Gmail con contraseña de aplicación, etc.) es solo cambiar el `.env`.

**Borrador listo** (`DRAFT_READY`): cuando una plataforma que publica como borrador (TikTok sin auditar, §11.4) confirma que el borrador ha llegado, se envía un email con el título y la descripción del clip para pegarlos al publicarlo. Usa el mismo destinatario y no depende del umbral.

**Subida a mano** (`MANUAL_UPLOAD`): cuando una plataforma no admite una publicación por su límite (§11.3), un email por publicación con la plataforma, el motivo, el texto para pegar, el enlace para descargar el clip y el enlace a *Publicaciones* para marcarla como subida. Usa el mismo destinatario y no depende del umbral.

**Ampliable** (opcional, mismo mecanismo): avisos de publicación fallida, token de una plataforma a punto de caducar, trabajo en `AWAITING_REVIEW` o ningún worker conectado durante mucho tiempo con tareas pendientes. Cada aviso activable por separado.


### 12.4 Estadísticas

Para decidir qué contenido funciona mejor. Se recogen de las tres plataformas (YouTube, Instagram y TikTok, cada una con lo que da su API, ver abajo; cambio de 2026-10: antes decía "solo YouTube"); cada plataforma aporta sus métricas con el mismo esquema.

**YouTube** — dos APIs, ambas con el token de la cuenta conectada:
- **Data API v3** (`youtube.readonly`): por vídeo, `views`, `likes`, `comments` (`videos.list part=statistics`); por canal, `subscribers`, `views`, `videos` (`channels.list part=statistics`). Casi en tiempo real.
- **Analytics API** (`yt-analytics.readonly`): por vídeo, `minutesWatched`, `averageViewDurationSeconds`, `averageViewPercentage` (retención: qué parte del clip se ve de media) y `subscribersGained`. Llega con **1–3 días de retraso**; hasta entonces esos campos quedan vacíos. Si la cuenta se conectó sin este scope, se recogen solo los de la Data API.
- Mientras los vídeos estén en **privado** (§11.4) las métricas serán casi cero: cuentan desde que se hacen públicos.

**Instagram** — insights de cada Reel (`/{media}/insights`, hasta 48 h de retraso): `views`, `reach`, `likes`, `comments`, `shares`, `saved`, y el tiempo medio y total de visualización (`ig_reels_avg_watch_time`, `ig_reels_video_view_total_time`, en milisegundos) como `averageViewDurationSeconds` y `minutesWatched`. De la cuenta, `followers_count` como `subscribers` y `media_count` como `videos`. Instagram no da el % visto.

**TikTok** — solo de la cuenta (`user.info.stats`): `follower_count` como `subscribers`, `likes_count` como `likes` y `video_count` como `videos`. Por clip no hay métricas mientras se publique como borrador: el borrador no queda ligado al vídeo que acabas publicando desde la app.

**Pantalla** (una pestaña por plataforma conectada): fila de cifras del canal (suscriptores con su variación a 7 días, vistas totales, clips publicados, vistas medias por clip); evolución de los suscriptores; los clips con más vistas; vistas medias según la **hora de publicación** (Europe/Madrid); y una tabla con todas las métricas de cada clip, ordenable, que también es la vista accesible de los datos.

## 13. Seguridad

- **Sin secretos en el repo** (`.env` + `.env.example`), incluidas las credenciales SMTP.
- **Tokens OAuth** de TikTok, Meta y Google **cifrados** en BD; clave de cifrado por variable de entorno.
- **Superadmin**: solo vía Cloudflare Tunnel + Access + login con 2FA; puerto escuchando solo en `localhost`.
- **Media** (*R1*): ruta pública sin Access que solo sirve `GET /media/{token}`; tokens firmados, de un solo clip y con caducidad corta (p. ej. 1 hora, lo justo para que Instagram procese el contenedor); nada más es accesible por ella.
- **Workers**: puerto escuchando solo en Tailscale; token propio que solo da acceso al WebSocket y a los ficheros de sus propias tareas.
- **URLs de ficheros firmadas y con caducidad**, ligadas a `taskId + attempt`.
- Todo el tráfico entre equipos cifrado (Tailscale o TLS del túnel).
- **Enlaces de vídeo**: solo se aceptan URLs `http(s)` con host; a yt-dlp le llegan detrás de `--`, de modo que nunca se pueden leer como una opción (`--exec=…`).
- **Rutas por puerto, antes que nada**: el filtro que limita qué rutas sirve cada puerto (medios: solo `/media/`; workers: `/ws/` y `/tasks/`) va **antes** que Spring Security, así que en el host público de medios —que no pasa por Cloudflare Access— no se puede ni intentar el login. Además, el túnel solo enruta `/media/` hacia ese puerto.
- **Mensajes de un worker**: además del `attempt`, la tarea tiene que estar asignada a ese worker; un worker no puede aceptar, terminar, fallar ni soltar tareas de otro.
- **Claude sin herramientas**: `claude -p` se ejecuta con `--tools ""` y en una carpeta temporal vacía. El transcript y los metadatos vienen de vídeos de terceros: un texto preparado no debe poder hacer que lea ficheros del servidor y los meta en lo que se publica.
- **Modelo de Whisper de una tarea**: el worker solo acepta un nombre de modelo (letras, números, `.`, `-`, `_`), nunca una ruta; una ruta solo puede ponerla el usuario en su propio `worker.yml`.

### 13.1 Variables de entorno del servidor (`.env.example`)

Todo lo secreto o específico de cada instalación. Lo que no es secreto y se cambia en caliente va en los **ajustes del superadmin** (tabla `setting`).

| Variable | Para qué |
|---|---|
| `CLIPHIVE_DB_URL`, `CLIPHIVE_DB_USER`, `CLIPHIVE_DB_PASSWORD` | Conexión a PostgreSQL |
| `CLIPHIVE_DATA_DIR` | Directorio de datos (en el SSD) |
| `CLIPHIVE_ADMIN_USER`, `CLIPHIVE_ADMIN_PASSWORD` | Cuenta única del superadmin (se crea en el primer arranque; el 2FA se configura después desde la web) |
| `CLIPHIVE_ENCRYPTION_KEY` | Clave para cifrar tokens OAuth y el secreto TOTP en BD |
| `CLIPHIVE_URL_SIGNING_KEY` | Clave para firmar las URLs de ficheros de workers y de medios |
| `CLIPHIVE_ADMIN_PORT` | Puerto del superadmin (escucha en `localhost`) |
| `CLIPHIVE_ADMIN_PUBLIC_URL` | URL pública del superadmin (p. ej. `https://cliphive.example.com`), para las URLs de redirección OAuth que se registran en cada plataforma (`<url>/admin/accounts/<plataforma>/callback`) |
| `CLIPHIVE_MEDIA_PORT`, `CLIPHIVE_MEDIA_PUBLIC_URL` | Puerto de medios (`localhost`) y su URL pública (p. ej. `https://cliphive-media.example.com`) |
| `CLIPHIVE_WORKER_BIND`, `CLIPHIVE_WORKER_PORT` | IP de Tailscale y puerto para los workers |
| `CLIPHIVE_OPERATOR_NAME`, `CLIPHIVE_OPERATOR_EMAIL` | Quién gestiona la instalación: el responsable y contacto que nombran las páginas legales (§11.5) |
| `CLIPHIVE_TIKTOK_CLIENT_KEY` / `_SECRET` | App de TikTok |
| `CLIPHIVE_META_APP_ID` / `_SECRET` | App de Meta (Instagram) |
| `CLIPHIVE_GOOGLE_CLIENT_ID` / `_SECRET` | Proyecto de Google (YouTube) |
| `CLIPHIVE_SMTP_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_FROM` | Envío de avisos por email (Resend: `smtp.resend.com`, `587`, `resend`, API key, `ClipHive <cliphive@example.com>`) |
| `CLAUDE_CODE_OAUTH_TOKEN` | Autenticación de `claude -p` cuando el servidor corre como servicio (generado con `claude setup-token`) |
| `ANTHROPIC_API_KEY` (opcional) | Alternativa a `CLAUDE_CODE_OAUTH_TOKEN`: credencial que usa la propia CLI `claude` (pago por uso). El servidor no llama a la API directamente (§10.1) |
| `CLIPHIVE_*_PATH` (opcional) | Rutas de `yt-dlp`, `ffmpeg`, `ffprobe`, `claude` si no están en el `PATH` |

**Comprobación al arrancar** (cambio de 2026-10): antes de levantar nada, el servidor revisa esta configuración y, si falta algo imprescindible, se para con **la lista completa** de lo que falta o está mal, en vez de un error de Spring difícil de leer:
- **Imprescindible**: `CLIPHIVE_DB_URL` (una URL `jdbc:postgresql:`) y `CLIPHIVE_DB_USER`; `CLIPHIVE_DATA_DIR` (ruta absoluta: una relativa acabaría junto al programa, quizá en la tarjeta SD); `CLIPHIVE_URL_SIGNING_KEY` (al menos 32 caracteres); `CLIPHIVE_ENCRYPTION_KEY` (Base64 de exactamente 32 bytes, `openssl rand -base64 32`).
- **Solo aviso en el log**: si faltan `CLIPHIVE_ADMIN_PUBLIC_URL` o `CLIPHIVE_MEDIA_PUBLIC_URL` (sin ellas no se pueden conectar cuentas ni publicar en Instagram) o el responsable de las páginas legales. Si están, las URLs públicas tienen que ser `http(s)`.
- El perfil `embedded` (pruebas locales) no se comprueba. Ese perfil (Postgres embebido, admin `admin`/`admin123`, claves de prueba) **no va en el jar de producción** (cambio de 2026-10): está en `server/src/embedded/`, los tests lo ven siempre y solo se empaqueta con `./mvnw -pl server package -Pembedded`, para la prueba local. El jar de producción y la imagen Docker no lo llevan, ni los ~80 MB de binarios de PostgreSQL. Arrancar un jar de producción con ese perfil se para con el error "The 'embedded' profile is not in this build…". El perfil enlaza el conector de workers a `127.0.0.1` (`cliphive.worker-bind`), para que un worker en el mismo equipo se conecte a `ws://localhost:8082/ws/worker`.
- **Perfil `demo`** (cambio de 2026-10), solo junto a `embedded` (`--spring.profiles.active=embedded,demo`): carga datos inventados para ver el superadmin con contenido (trabajos en varios estados, clips publicados con estadísticas, publicaciones programadas, workers y logs) y para las capturas del README. Es una migración repetible de Flyway en `server/src/embedded/resources/db/demo/`, con las fechas relativas al arranque, así que va donde el perfil `embedded` y nunca llega al jar de producción. Crea tres cuentas sociales con tokens de relleno sin cifrar: solo los leen la publicación y las estadísticas, y el perfil apaga esos procesos junto con el planificador, las alertas y la limpieza (`cliphive.*.scheduler-enabled=false`), así que ningún proceso puede llamar a las plataformas ni enviar emails; un reenvío pulsado a mano falla al descifrar el token, antes de llamar a nada. La comprobación del arranque se niega a seguir con `demo` sin `embedded`, para que esos datos nunca lleguen a una base de datos real. Ningún test lo activa salvo `DemoDataTest`, que lo carga a propósito para que un cambio de esquema que lo rompa falle en el CI.
- **`.env.example` sin valores personales** (cambio de 2026-10): las URLs públicas, el nombre y email del responsable y el host, usuario y remitente del SMTP van vacíos, con un comentario `# e.g.` de ejemplo encima. Así, mientras no se rellenan, saltan los avisos del arranque y el de SMTP sin configurar de los ajustes, en vez de funcionar con valores de ejemplo que no son de nadie.

## 14. Persistencia

### 14.1 PostgreSQL
Corre en el propio servidor. **Backups no críticos**: si el servidor muere, la app deja de existir. Se puede pasar a **Neon** en el futuro. Migraciones con **Flyway**.

### 14.2 Esquema detallado

Expandido al implementar las migraciones Flyway (2026-10-02) — la tabla de una línea por entidad que había aquí no fijaba columnas ni tipos; lo que sigue sí es el contrato real. Convenciones comunes a todas las tablas:
- PK `id UUID` generado por la aplicación (`GenerationType.UUID`), salvo `setting` (PK natural `key`).
- `created_at`/`updated_at` en `timestamptz`, UTC.
- Los enums de estado (`video_job`, `clip`, `task`, `scheduled_post`) son los de `protocol` (`io.github.raulgl422.cliphive.protocol.state.*`), persistidos como texto (`EnumType.STRING`) — un único sitio define cada máquina de estados, para `server` y para los mensajes WebSocket.
- Todo lo cifrado (tokens OAuth, secreto TOTP) se guarda como `text`; el cifrado/descifrado con `CLIPHIVE_ENCRYPTION_KEY` ocurre en la capa de aplicación, no es un tipo de columna.
- JSON variable (parámetros de tarea, rangos de clip, salida de Claude, métricas) se guarda como `jsonb`.

| Tabla | Columnas | Notas |
|---|---|---|
| `admin_user` | `id`, `username`, `password_hash`, `totp_secret_encrypted` (nullable), `totp_enabled` (bool), `created_at` | Una sola fila, creada al arrancar desde `CLIPHIVE_ADMIN_USER`/`_PASSWORD` (§12.1). El 2FA se activa después desde la web. |
| `setting` | `key` (PK), `value`, `updated_at` | Pares clave/valor editables desde el superadmin (§12.2) — idioma/fps/revisión por defecto, modo de publicación, límites de duración, umbral de aviso de cola, etc. No tipado por fila; cada lector parsea lo que espera. |
| `worker` | `id`, `name`, `token_hash`, `revoked_at` (nullable), `last_capabilities` (`jsonb`, nullable), `last_seen_at` (nullable), `created_at` | Alta/revocación desde el superadmin (§7, "Alta de workers"); `last_capabilities` es el último `WorkerCapabilities` recibido en `HELLO`, para mostrarlo en el superadmin. |
| `worker_session` | `id`, `worker_id` (FK), `connected_at`, `disconnected_at` (nullable), `disconnect_reason` (nullable), `last_heartbeat_at` | Una fila por conexión WebSocket (§6.5). |
| `social_account` | `id`, `platform`, `external_account_id`, `display_name` (nullable), `access_token_encrypted`, `refresh_token_encrypted` (nullable), `expires_at` (nullable), `connected_at` | TikTok/Instagram/YouTube (§11); `display_name` es el nombre visible de la cuenta (p. ej. el del canal de YouTube), solo para mostrarlo en el superadmin; `platform` es un enum propio de `server` (no viaja por el protocolo WebSocket, así que no vive en `protocol`). |
| `video_job` | `id`, `source_url`, `source_platform`, `state` (`VideoJobState`), `language`, `fps_mode`, `review_mode` (bool), `clip_mode` (`AUTO` / `MANUAL`), `audio_language` (nullable: audio original; si no, idioma del doblaje elegido), `dubbed_audio_available` (bool, nullable: sin comprobar), `manual_ranges_json` (`jsonb`, nullable: lista de `{startMs, endMs}`), `selection_confirmed` (bool), `priority` (int), `title` (nullable), `category` (nullable), `duration_seconds` (nullable), `source_fps` (nullable), `resolution` (nullable), `rejection_reason` (nullable), `file_hash` (nullable), `source_channel` (nullable: canal o autor del vídeo original, para el crédito en la descripción, §10.3), `playlist_ids_json` (`jsonb`, nullable: id de la lista de reproducción de sus clips por plataforma, §11.2), `created_at`, `updated_at` | `language`/`fps_mode`/`review_mode` heredan el default global si no se fijan por trabajo (§4 paso 1); el resto de metadatos los rellena la validación con `yt-dlp` (§4 paso 2). `priority` se guarda pero aún no se puede cambiar desde el superadmin (§6.2, pendiente). `source_platform` (YouTube/Twitch/Kick...) es un concepto distinto del `platform` de publicación en `social_account`/`scheduled_post` (TikTok/Instagram/YouTube) - de ahí el nombre distinto pese a la coincidencia con YouTube. |
| `transcript` | `id`, `video_job_id` (FK, unique), `language`, `words_json` (`jsonb`), `created_at` | Una fila por vídeo: el JSON de whisper.cpp ya unido por el servidor a partir de los trozos del worker (§4 paso 5), con timestamps por palabra. |
| `content_analysis` | `id`, `video_job_id` (FK, unique), `content_start_segment_id` (nullable), `content_end_segment_id` (nullable), `removed_segments_json` (`jsonb`, nullable), `plan_notes_json` (`jsonb`, nullable: ajustes que hizo el servidor al plan, §10.2), `proposed_clips_json` (`jsonb`, nullable), `content_type` (nullable), `review_status`, `created_at` | Salida de Claude en ids de segmento, no en tiempos (§10.2); `review_status` (`PENDING_REVIEW` / `NOT_NEEDED` / `APPROVED`) solo importa en modo revisión. Nullable cuando Claude falló y se siguió sin recortes (R12). |
| `clip` | `id`, `video_job_id` (FK), `clip_index` (int), `overlay_title` (nullable: título en pantalla, §10.3), `section_title` (nullable), `part_index` / `part_count` (nullable: posición en la serie de su sección, §10.2), `state` (`ClipState`), `kept_ranges_json` (`jsonb`), `fps` (int, nullable hasta decidirse), `title` (nullable), `description` (nullable), `file_reference` (nullable), `duration_seconds` (nullable), `created_at`, `updated_at` | `clip_index` fija el orden de prioridad entre clips de un mismo vídeo (§6.2). `kept_ranges_json` son los rangos a conservar tras aplicar los recortes de Claude. |
| `task` | `id`, `type` (`TaskType`), `state` (`TaskState`), `priority` (int), `attempt` (int), `failure_count` (int), `disconnect_count` (int), `assigned_worker_id` (FK, nullable), `video_job_id` (FK, nullable), `clip_id` (FK, nullable), `parameters_json` (`jsonb`), `last_progress_percent` (nullable), `last_progress_phase` (nullable), `last_progress_at` (nullable), `created_at`, `updated_at` | Exactamente uno de `video_job_id` (chunk de `TRANSCRIBE`) / `clip_id` (`RENDER_CLIP`) está presente, según `type`. `attempt` es el fencing token (§6.3); `failure_count`/`disconnect_count` son los **dos contadores separados** (máx. 3 / máx. 10) de la misma sección. `last_progress_at` alimenta el timeout de tarea atascada (§6.5). |
| `scheduled_post` | `id`, `clip_id` (FK), `platform`, `scheduled_at`, `state` (`ScheduledPostState`), `origin`, `external_id` (nullable), `error_message` (nullable), `attempts` (int), `published_at` (nullable), `external_url` (nullable), `postponed` (bool), `published_manually` (bool), `manual_alert_sent` (bool), `awaiting_confirmation` (bool), `created_at`, `updated_at` | Una fila por `(clip, plataforma)` (§5.4). `postponed`: esperando un límite de la plataforma; `published_manually`: la subiste tú y la marcaste; `awaiting_confirmation`: subida como borrador o en privado, falta que confirmes que la publicaste (§11.3); `manual_alert_sent`: ya se mandó el email de subida a mano (§11.3). `scheduled_at` en UTC, mostrado en Europe/Madrid (§10.4). `external_id` es la clave de idempotencia tras reinicio a mitad de subida (§11.3). `origin`: `IMMEDIATE` / `CLAUDE_PLAN` / `MANUAL`. `attempts` cuenta los intentos fallidos para la espera creciente (§11.3). `published_at`: cuándo quedó publicada, para el ritmo de recogida y la hora de publicación de las estadísticas (§12.4). `external_url`: enlace a la publicación cuando la plataforma no permite deducirlo del id (Instagram). |
| `publication_plan` | `id`, `plan_date`, `input_summary_json` (`jsonb`), `output_json` (`jsonb`), `validations_applied_json` (`jsonb`), `created_at` | Un plan diario de Claude por fecha (§10.4), guardado para poder auditar qué propuso y qué ajustó el servidor. |
| `post_stats` | `id`, `scheduled_post_id` (FK), `captured_at`, `metrics_json` (`jsonb`) | Snapshot periódico de una publicación (§12.4). `metrics_json`: `views`, `likes`, `comments` y, cuando hay datos de Analytics, `minutesWatched`, `averageViewDurationSeconds`, `averageViewPercentage`, `subscribersGained`; los que una plataforma no da, ausentes. |
| `account_stats` | `id`, `social_account_id` (FK), `captured_at`, `metrics_json` (`jsonb`) | Snapshot periódico de una cuenta (§12.4): `subscribers`, `views`, `videos`. |
| `notification` | `id`, `type`, `recipient`, `sent_at`, `status`, `metadata_json` (`jsonb`, nullable) | Avisos ya enviados (§12.3: cola baja, ampliable a fallos/tokens), para no repetirlos y para listarlos en el superadmin. |
| `log_event` | `id`, `level`, `component`, `message`, `context_json` (`jsonb`, nullable), `created_at` | Logs estructurados filtrables por nivel/componente/trabajo (§12.2; por worker, pendiente); `context_json` lleva los ids relevantes. Retención limitada (`logs.retention-days`, 30 días por defecto) por el barrido de limpieza (§15), coherente con "no queda rastro" (*R15*); el índice por `created_at` lo hace barato. |

`platform` se repite en `social_account` y `scheduled_post` con el mismo significado (TikTok/Instagram/YouTube) pero es un enum propio de `server`, no de `protocol`: no forma parte de ningún mensaje WebSocket, así que no tiene por qué vivir en el módulo compartido.

## 15. Retención de ficheros (no queda rastro)

Un barrido del servidor (`FileCleanupService`) recorre `jobs/` **cada hora** (y al arrancar) y borra lo que ya no hace falta. Nunca toca un trabajo con una subida en curso (`UPLOADING` o `PROCESSING`: Instagram aún puede estar leyendo el fichero por `/media`).

- **Vídeo fuente e intermedios** (`source/`, `audio/`, `chunks/`, JSON sueltos): se borran en cuanto el trabajo ha **terminado de renderizar** (`RENDERED`, `SCHEDULED`, `PUBLISHING`). Cada clip ya tiene su trozo cortado en `clips/NNN/`, que es lo único que necesita un reintento de render. Es lo que más ocupa, así que no espera a que se publique.
- **Fichero de un clip** (`clips/NNN/`, trozo + clip renderizado): se borra cuando **no queda ninguna subida pendiente**: todas sus publicaciones están `PUBLISHED` (y **confirmadas**: un borrador de TikTok o un vídeo privado de YouTube sin confirmar guarda el fichero, §11.3) o `CANCELLED` (subido a YouTube con `publishAt` cuenta como publicado). El clip pasa a `CLEANED`. Si llevas **N días** (`files.retention-days`) sin confirmar, el fichero se borra igualmente (la publicación sigue pendiente de confirmar, pero ya no se puede reenviar ni descargar). Uno cancelado también se borra.
- **Red de seguridad** (*R8*): una publicación `FAILED` se puede reintentar desde el trabajo, así que su fichero se guarda… pero solo **N días** (`files.retention-days`, 7 por defecto) sin reintentarla. Pasado ese tiempo la publicación se cancela ("fichero borrado tras N días sin reintentar") y el fichero se borra. Un fallo permanente nunca deja el fichero para siempre.
- **Trabajo terminado** (`COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED`): se borra su carpeta entera.
- **Carpetas huérfanas** (sin trabajo en la BD, p. ej. si falló el borrado de la carpeta al eliminar un trabajo): se borran tras N días sin cambios.
- Un trabajo **atascado** a mitad de proceso no se borra solo: se ve en la lista de trabajos y se puede eliminar con el botón (§12.2).
- Los clips que esperan publicación (`RENDERED` sin plan, publicaciones `SCHEDULED`) **nunca** se borran, aunque esperen días por los topes diarios.
- **Logs**: se borran los de más de `logs.retention-days` días (30 por defecto, *R15*), en el mismo barrido.
- En Ajustes → Ficheros se ve cuánto ocupa `jobs/` y el espacio libre del disco, se cambian los días y hay un botón **Limpiar ahora**. Cada barrido que borra algo deja un log con lo liberado.
- Los workers borran sus ficheros temporales al entregar cada tarea.
- La BD conserva solo **metadatos** (publicaciones, ids externos, estadísticas).

## 16. Stack, repositorio, CI y empaquetado

### 16.1 Stack
- **Servidor**: Java + Spring Boot (REST, WebSocket, `@Scheduled`), PostgreSQL, Flyway, Thymeleaf + HTMX (gráficos dibujados a mano, §12.1).
- **Worker**: Java + JavaFX (opcional) + modo headless.
- **Herramientas externas**: `yt-dlp` (siempre actualizado; el soporte de Kick depende de la versión), `ffmpeg`/`ffprobe`, `whisper.cpp`, `claude` (Claude Code).
- **Red**: Cloudflare Tunnel + Access (superadmin), Tailscale (workers).

### 16.2 Repositorio (monorepo multimódulo)
```
cliphive/
├── protocol/            → mensajes WebSocket, DTOs y enums de estados (compartido)
├── server/              → Spring Boot: API, cola, Claude, scheduler, publishers + superadmin
├── worker/              → núcleo Java + UI JavaFX opcional (modo headless)
├── docs/
│   ├── DESIGN.md        → este documento
│   ├── DEPLOY.md        → instalación del servidor (Pi / Linux)
│   ├── ARCHITECTURE.md, INSTALL-WORKER.md, USER-GUIDE.md, DEVELOPMENT.md → guías en inglés (índice en docs/README.md)
│                         (las páginas legales, §11.5, están en server/src/main/resources/legal/)
├── .github/workflows/   → CI: build, tests, whisper.cpp, instaladores, release del servidor
├── CLAUDE.md            → contexto para Claude Code: apunta a docs/DESIGN.md y fija convenciones
├── .env.example
├── LICENSE              → Apache-2.0
└── README.md
```
- **`CLAUDE.md`**: como el desarrollo se hará con Claude Code, este fichero le da el contexto en cada sesión: qué es el proyecto, que `docs/DESIGN.md` es la fuente de verdad, la estructura de módulos, convenciones (paquete `io.github.raulgl422.cliphive`, Java 25, multiplataforma, sin secretos en el repo) y la fase actual del plan.
Dos entregables, cada uno con su etiqueta de release (§16.4): el **servidor** (`server-vX.Y.Z`: imagen Docker y `.jar`) y el **instalador del worker** (`worker-vX.Y.Z`: MSI). Se versionan por separado; el protocolo WebSocket es el contrato entre ambos.

### 16.3 Pruebas automáticas (*R16*)
- **Unitarias**: planificación de clips, traducción de ids de Claude a tiempos, reajuste de tiempos de subtítulos, decisión de fps, máquinas de estados.
- **Integración** con **Testcontainers** (PostgreSQL real): asignación con `SKIP LOCKED`, fencing con `attempt`, reasignación tras desconexión.
- **Worker simulado** (*fake worker*) para probar el protocolo de punta a punta sin GPU.
- **Prueba de render** con un vídeo corto de muestra en CI (por CPU, con el encoder por software del ffmpeg del runner).

### 16.4 CI (GitHub Actions)
- Compilación y tests de los tres módulos, con ffmpeg instalado para que corran los tests que lo usan (render, troceado de audio, recorte).
- **Permisos mínimos**: los workflows solo leen el repo, salvo los jobs que adjuntan ficheros a una release o publican la imagen del servidor; las acciones de terceros van fijadas a un commit, no a una etiqueta que se puede mover. El instalador pasa los tests antes de empaquetar. El grafo de conocimiento (`graphify-out/`) se actualiza en local tras cada commit, no en el CI.
- **whisper.cpp precompilado** para Windows x64, Linux x64 y Linux ARM64, en variantes **Vulkan** y **CPU** (CUDA opcional). Vulkan cubre AMD, NVIDIA e Intel. Hoy (2026-10) solo están activos los de Windows x64; los de Linux están desactivados en los workflows.
- Instaladores del worker con **`jpackage`** (Java incluido con `jlink`): `.msi`/`.exe` en Windows, `.deb` (o AppImage) en Linux. Incluyen ffmpeg estático y whisper.cpp.
- **Licencias en el instalador** (cambio de 2026-10): la build de ffmpeg está **fijada** (un `autobuild-*` de fin de mes de BtbN, que no se borra, con su SHA-256 comprobado), no `latest`, para poder decir qué fuente exacta se distribuye. El instalador lleva una carpeta `licenses/` con `THIRD-PARTY-NOTICES.txt` (cada componente, su licencia y su fuente), el `LICENSE` de la build de ffmpeg y `ffmpeg-SOURCE.txt` (release, commit y SHA-256), la licencia MIT de whisper.cpp con su versión, la LGPL 2.1 (ffmpeg, logback) y la licencia del proyecto; el JRE trae las suyas en `runtime/legal/`. En el jar del worker se fusionan los `NOTICE` de las librerías en vez de quedarse con uno al azar. El CI falla si falta alguno de estos ficheros.
- El usuario final del worker **no compila nada**: solo instala los drivers de su GPU y Tailscale.
- **Release del servidor** (cambio de 2026-10; hasta entonces se compilaba desde el código fuente): con una etiqueta `server-vX.Y.Z`, el workflow `server.yml` fija esa versión en los `pom.xml` (para que el jar y el banner digan la versión real), pasa los tests y publica dos cosas. La primera es el jar de producción (`cliphive-server-X.Y.Z.jar`, sin el perfil `embedded`) con su SHA-256, adjunto a la release. La segunda es la imagen **multiarquitectura** (`linux/amd64` y `linux/arm64`, para la Raspberry Pi) en `ghcr.io/raulgl422/cliphive-server`, con las etiquetas `X.Y.Z` y `latest`. La imagen compila el jar en la plataforma del runner (el jar no depende de la arquitectura) y solo emula ARM para la etapa final. El `docker-compose.yml` usa esa imagen, así que el servidor se instala sin compilar; `--build` sigue compilándola en local desde el código. Una ejecución manual del workflow compila y prueba todo, pero no publica nada. Las herramientas que la imagen descarga al construirse (Deno, Claude Code, yt-dlp `latest`) no están fijadas, así que dos builds de la misma versión pueden llevar versiones distintas de ellas.
- **Artefactos del CI**: subir los informes de tests o el instalador como artefacto del workflow nunca pone el CI en rojo (en un repo privado la cuota de almacenamiento se agota); en una etiqueta de release el instalador va a la release, no a los artefactos.

### 16.5 Licencia (*R17*)
- Proyecto bajo **Apache-2.0**.
- **ffmpeg en los instaladores**: se distribuye como ejecutable aparte (no se enlaza), pero hay que cumplir su licencia. Una build **LGPL** es la opción más sencilla y compatible, y suele incluir AMF, NVENC y VAAPI; a cambio, **no incluye `libx264`** (es GPL), así que el fallback por CPU usa **`libopenh264`** (decidido en la fase 8, §20.1; `libx264` solo si es el único que tiene un ffmpeg propio del usuario).

### 16.6 Operación del servidor
- **Directorio de datos** (`CLIPHIVE_DATA_DIR`, en el SSD), con subcarpetas por trabajo: `jobs/<id>/source`, `audio`, `chunks`, `clips`. Borrar un trabajo = borrar su carpeta.
- **Actualización automática de yt-dlp**: diez minutos después de arrancar y luego una vez al día el servidor ejecuta `yt-dlp -U` y registra el resultado en los logs (para que funcione, el usuario del servicio tiene que poder escribir en la carpeta del binario). Claude Code nativo se actualiza solo. *Pendiente*: Deno no se actualiza solo (se actualiza a mano con `deno upgrade` o reconstruyendo la imagen Docker).
- **Llamadas a Claude**: con reintentos y espera creciente. *Pendiente* (cambio de 2026-10: el documento las daba por serializadas): ponerlas en serie (una cada vez, cola propia) para no chocar con los límites de uso; hoy pueden coincidir varias.
- **Salud**: la imagen Docker trae un `HEALTHCHECK` que pide `/login` en el puerto admin cada minuto (público y servido por la propia app: un 200 indica que está viva y responde); no se añade Spring Actuator. *Pendiente*: un panel de salud en el superadmin (estado de BD, versiones de herramientas); hoy el espacio libre del disco se ve solo en *Ajustes → Ficheros* y los workers conectados en *Workers*.
- **Docker**: imágenes base fijadas a una versión exacta de Temurin (etiquetas multiarquitectura), logs del contenedor con rotación (20 MB × 5) y 60 s de margen al parar para cerrar subidas y sesiones.
- **Servicio**: el usuario del servicio systemd es el que ejecuta `claude`; su autenticación va en `CLAUDE_CODE_OAUTH_TOKEN` dentro del fichero de entorno del servicio, no en el login interactivo de otro usuario.
- **Apagado limpio**: al parar el servidor se cierran las sesiones de workers (sus tareas vuelven a la cola) y las subidas en curso se retoman al arrancar gracias a la idempotencia (sección 11.3).

## 17. READMEs: requisitos por sistema operativo y GPU

### 17.1 Servidor

| | **Linux** (x86_64 / ARM64) | **Windows** |
|---|---|---|
| **Java** | OpenJDK **25** | Temurin/OpenJDK **25** (`winget`) |
| **PostgreSQL** | Paquete de la distro | Instalador oficial o `winget` |
| **Sistema** | **64 bits** obligatorio (en la Pi: Raspberry Pi OS Lite **64-bit**; comprobar `uname -m` = `aarch64`) y ≥ 4 GB de RAM (requisito de Claude Code) | Windows 10/11 x64 |
| **yt-dlp** | Binario oficial `yt-dlp_linux_aarch64` / `yt-dlp_linux` (el de la distro suele estar desactualizado) | `winget install yt-dlp` |
| **Deno** | Necesario para que yt-dlp descargue de YouTube sin limitaciones (runtime de JavaScript) | Ídem (`winget install DenoLand.Deno`) |
| **ffmpeg** | Paquete de la distro | Build de gyan.dev |
| **Claude Code** (obligatorio: el servidor siempre llama a `claude -p`, §10.1) | Instalador oficial o repositorio apt. **Login**: si falla en ARM64 sin navegador, generar el token en otro equipo (`claude setup-token`) y pasarlo por variable de entorno (`CLAUDE_CODE_OAUTH_TOKEN`), o copiar `~/.claude/.credentials.json` | Instalador oficial o `winget` |
| **cloudflared** | Paquete oficial de Cloudflare | `winget install Cloudflare.cloudflared` |
| **Tailscale** | Paquete oficial | Instalador oficial |
| **Dominio** | En Cloudflare (DNS), para los hostnames del túnel | Ídem |
| **Servicio** | Unidad **systemd** | **WinSW/NSSM** |

### 17.2 Worker (para desarrollo; el usuario final usa el instalador)

| SO / GPU | Drivers | ffmpeg y encoder | whisper.cpp | Compilación |
|---|---|---|---|---|
| **Windows + AMD** | AMD Adrenalin (Vulkan y AMF) | *Full build* de gyan.dev → `h264_amf` | `-DGGML_VULKAN=1` (Vulkan SDK) | Git, CMake, Visual Studio Build Tools (C++) |
| **Windows + NVIDIA** | Driver NVIDIA | *Full build* de gyan.dev → `h264_nvenc` | `-DGGML_CUDA=1` (CUDA Toolkit) o Vulkan | Ídem |
| **Windows sin GPU** | — | Cualquier build con `libopenh264` (o `libx264`) | Estándar | Ídem |
| **Linux + AMD** | Mesa: `mesa-va-drivers`, `mesa-vulkan-drivers` | `h264_vaapi` | `-DGGML_VULKAN=1` (`libvulkan-dev`, `glslc`) | `build-essential`, `cmake`, `git` |
| **Linux + NVIDIA** | Driver propietario | Build con NVENC → `h264_nvenc` | `-DGGML_CUDA=1` o Vulkan | Ídem |
| **Linux sin GPU** | — | `libopenh264` (o `libx264`) | Estándar | Ídem |

**Problemas conocidos en Windows** (vistos en las pruebas):
- `cmake` falla con *"Running 'nmake' '-?' failed"* → faltan **Visual Studio Build Tools** con C++. Instalar, borrar la carpeta `build` y usar `-G "Visual Studio 17 2022" -A x64`.
- El script de descarga de modelos puede no dejar el fichero en `models\` → descargar con `curl.exe -L -o models\ggml-<modelo>.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-<modelo>.bin`.

**Verificación**

| Comprobación | Windows (PowerShell) | Linux |
|---|---|---|
| Encoder disponible | `ffmpeg -hide_banner -encoders \| findstr "amf nvenc"` | `ffmpeg -hide_banner -encoders \| grep -E "vaapi\|nvenc"` |
| GPU visible | `nvidia-smi` / Administrador de tareas | `vainfo` / `nvidia-smi` / `vulkaninfo --summary` |
| whisper.cpp usa la GPU | Log: `ggml_vulkan: Found N Vulkan devices` + `use gpu = 1` | Ídem |
| Render | `speed=` por encima de 1x | Ídem |

## 18. Resultados de las pruebas técnicas

### 18.1 Portátil (Windows, AMD gráfica integrada, `uma: 1`)

| Prueba | Configuración | Resultado |
|---|---|---|
| whisper.cpp + Vulkan | GPU detectada: `AMD Radeon(TM) Graphics`, `use gpu = 1` | ✅ |
| Transcripción | Vídeo de **48 min**, `large-v3-turbo`, beam search por defecto, palabra a palabra | **23 min** (~2,1x tiempo real). Encode 690 s, beam search ~640 s, 9+56 fallbacks |
| ffmpeg + AMF | `h264_amf` disponible | ✅ |
| Render | 2 min de un original a **60 fps**, 9:16 + desenfoque a baja resolución + subtítulos SRT + AMF 8 Mbps | **56 s** (2,13x, 128 fps de proceso) |

### 18.2 Pendientes
Ver fase 0 del plan de acción.

## 19. Plan de acción

### Fase 0 — Validaciones técnicas y trámites (en curso)
Técnicas:
- [x] whisper.cpp con Vulkan en portátil.
- [x] ffmpeg con AMF en portátil.
- [x] Render a 30 fps (`fps=30` + `split`): se ve bien. Confirmado el criterio: **60 fps para gaming, 30 fps para el resto**.
- [x] Revisión visual del clip (9:16, desenfoque, centrado, subtítulos).
- [ ] Render por CPU (`libopenh264`) para comparar tiempos.
- [ ] Whisper con `-bs 1 -bo 1` y con VAD (10 min con `-d 600000`), comparando tiempo y calidad.
- [ ] Precisión de los timestamps por palabra; comparar con `-dtw large.v3.turbo`.
- [ ] Subtítulos karaoke: generar un ASS desde el JSON con grupos de 3–5 palabras y la actual resaltada, y quemarlo.
- [ ] Comprobar las pausas en el JSON para los puntos de corte.
- [ ] Repetir pruebas en el PC de sobremesa (GPU dedicada).
- [ ] `yt-dlp --dump-json --simulate` en el servidor con YouTube, Twitch y Kick, incluidos casos que deben fallar (privado, directo en curso).
- [ ] `claude -p` con una transcripción: análisis de cortes y título/descripción en JSON parseable, autenticado con la suscripción, en la máquina servidor; probar también un plan de publicación de ejemplo.

Preparación del entorno:
- [x] Claude Code instalado en la Pi y sesión iniciada.
- [ ] Reclamar el crédito mensual del Agent SDK en la cuenta de Claude.
- [ ] Pi: comprobar que el sistema es de 64 bits y que los datos irán al SSD.
- [ ] Pi: ffmpeg, Deno y yt-dlp instalados y probados.
- [ ] Pi: Java 25 y PostgreSQL instalados; base de datos y usuario `cliphive`.
- [ ] Equipo de desarrollo: JDK 25, IDE, Git.
- [ ] Repositorio público `cliphive` en GitHub con licencia Apache-2.0.
- [ ] Cuenta de Resend con el dominio verificado y una API key para los avisos por email (§12.3).

Trámites (en paralelo, son lo más lento):
- [ ] Páginas públicas de privacidad y condiciones en el dominio propio (sección 11.5).
- [ ] TikTok: app de desarrollador; subir un clip como borrador con Postman/curl.
- [ ] Instagram: cuenta profesional + app de Meta (login de Instagram, sin Página de Facebook); publicar un Reel de prueba.
- [ ] YouTube: proyecto de Google Cloud + Data API + OAuth (app en producción para evitar la caducidad de 7 días); subir un Short en privado.
- [x] Dominio propio en Cloudflare.
- [ ] Red: Tailscale entre portátil y servidor; Cloudflare Tunnel + Access sirviendo una página de prueba.

### Fase 1 — Esqueleto del repositorio
- Monorepo con `protocol`, `server`, `worker`; build multimódulo.
- `protocol`: mensajes, DTOs y enums de estados.
- `.env.example`, `.gitignore`, licencia, README inicial.
- CI básico (compilar + tests).

### Fase 2 — Servidor: núcleo
- PostgreSQL + Flyway con el esquema de la sección 14.
- Tres conectores: workers (Tailscale), admin (`localhost`) y media (`localhost`, solo `/media/**`).
- Creación de trabajos, validación (duración máxima, espacio libre) y descarga con yt-dlp; extracción de audio y división en trozos; corte de trozos de vídeo por clip con `-c copy`.
- Cola de tareas con `SKIP LOCKED`, `attempt`, máximos de intentos.
- WebSocket: `HELLO`/`WELCOME`, heartbeat, asignación, progreso, release, cancel.
- Servidor de ficheros con URLs firmadas.

### Fase 3 — Worker: núcleo headless
- Conexión, heartbeat, recepción de tareas, descarga/subida de ficheros.
- Detección de hardware y elección de encoder y binario de whisper.
- **TRANSCRIBE** de extremo a extremo.

### Fase 4 — Render
- Planificación provisional de clips por pausas y duración fija (hasta la fase 5, en la que Claude decide los clips y su duración).
- Generación del ASS karaoke con tiempos reajustados.
- **RENDER_CLIP** con recorte/concat, fps, 9:16, desenfoque y encoder.
- Decisión de fps (`auto`, metadatos, `mpdecimate`).
- **Hito**: enlace → clips renderizados, sin Claude ni publicación.

### Fase 5 — Claude
- `LlmClient` (`claude -p` + API).
- Propuesta de clips con duración decidida por Claude y validación de límites.
- Análisis de contenido con validación de ids y combinación con SponsorBlock y tramos sin voz.
- Títulos y descripciones por clip.

### Fase 6 — Superadmin
- Login + 2FA, cuenta única desde variables de entorno.
- Nuevo trabajo, trabajos y colas, revisión de cortes, workers, logs (SSE), ajustes.

### Fase 7 — Publicación
- `Publisher` de YouTube (más sencillo, programación nativa), después TikTok e Instagram.
- OAuth y refresco de tokens; pantalla de cuentas conectadas.
- Modo de publicación **inmediato** primero (para probar las APIs de punta a punta).
- Avisos por email (cola baja; ampliable a fallos y tokens).
- Ruta `media` pública para Instagram (*R1*); idempotencia y control de topes (*R4*, *R11*).
- **Planificador diario con Claude** (sección 10.4), con validación, fallback y vista del plan en el superadmin.
- Scheduler, calendario de publicaciones y limpieza de ficheros.
- Estadísticas y gráficos.

### Fase 8 — UI JavaFX del worker y empaquetado
- Ventana, bandeja, pausar/reanudar/parar, editor de configuración.
- Descarga de modelos y autotest.
- whisper.cpp precompilado en CI; instaladores con `jpackage`.

### Fase 9 — Documentación y despliegue ✓
- READMEs de servidor y worker (sección 17), para Linux y Windows.
- `docs/DEPLOY.md` — guía paso a paso para Raspberry Pi: PostgreSQL, systemd, Tailscale, Cloudflare Tunnel + Access, yt-dlp con actualizaciones automáticas.
- READMEs raíz (`README.md`, `README.es.md`) actualizados con el estado real del proyecto y los pasos de despliegue.

## 20. Decisiones

### 20.1 Tomadas
- [x] Servidor y workers **multiplataforma (Linux y Windows)**, documentados para ambos SO y para AMD/NVIDIA/CPU.
- [x] Monorepo `protocol` / `server` / `worker`.
- [x] Superadmin con **Thymeleaf + HTMX** dentro del servidor (gráficos dibujados a mano, sin Chart.js, §12.1); es también donde se envían los enlaces. Sin app de usuario (Flutter descartado).
- [x] Puertos separados: workers solo en Tailscale; admin solo en `localhost` tras Cloudflare Tunnel + Access + login con 2FA (una sola cuenta, sin registro). Ruta `media` pública para Instagram (*R1*).
- [x] Worker en Java: UI JavaFX opcional + modo headless; configuración en fichero con override por variables de entorno; control local (ver, pausar, parar).
- [x] Worker distribuido con instalador (`jpackage`) con Java, ffmpeg y whisper.cpp precompilado: el usuario solo instala drivers y Tailscale.
- [x] Sin fallback en el servidor: si no hay workers, los trabajos esperan.
- [x] BD en el servidor; backups no críticos.
- [x] Subtítulos **resaltados palabra a palabra** (ASS karaoke desde el JSON de whisper).
- [x] Vertical 9:16 con **fondo desenfocado**.
- [x] Fps en modo **`auto`** (metadatos + Claude + medición de movimiento), forzable a 30 o 60; bitrate 8/12 Mbps. Implementado solo en parte (cambio de 2026-10): hoy `auto` siempre renderiza a 30 fps; la decisión de 60 fps (§9.4, pasos 2 y 3) está pendiente.
- [x] **Duración de cada clip decidida por Claude**, en pausas naturales, con límites duros validados por el servidor (máx. 3 min por YouTube Shorts).
- [x] **Claude** para detectar intros/outros/partes sobrantes (por ids de segmento), con SponsorBlock y tramos sin voz como apoyo, y **modo revisión** opcional.
- [x] **Claude** para título y descripción **por clip**, iguales en las tres plataformas.
- [x] Publicación en **TikTok, Instagram Reels y YouTube Shorts**, misma cuenta, título y descripción, y **misma hora en todas** (§10.4, cambio de 2026-10: antes cada plataforma podía llevar su hora).
- [x] **Modo de publicación** configurable: `inmediato` (pruebas) o `claude` (Claude planifica cada día cuántos clips y a qué hora, adaptándose al tamaño de la cola) (*R2*).
- [x] Ruta **`media` pública** para Instagram, sin Access, con URLs firmadas de corta duración (*R1*).
- [x] Hasta tener el programa completo y probado, se acepta que TikTok y YouTube no sean automáticos de punta a punta (*R3*).
- [x] Nombre: **ClipHive**; un dominio propio en Cloudflare con dos subdominios de un nivel, uno para el superadmin y otro para los medios (en los ejemplos, `cliphive.example.com` y `cliphive-media.example.com`; §3.2).
- [x] **Java 25**, licencia **Apache-2.0**.
- [x] **Spring Boot 4.1** (4.1.2; cambio de 2026-10, antes 4.0.7: la rama 4.0 se queda sin soporte gratuito). Se fija a una versión exacta en el `pom.xml` raíz y Dependabot propone los parches. Trae **Jackson 3** por defecto (`tools.jackson.*`); `protocol` lo usa para `ProtocolCodec` — ver `protocol/README.md` para el detalle de paquetes.
- [x] Herramienta de build: **Maven** (multimódulo, con Maven Wrapper).
- [x] **Duración máxima del vídeo fuente: 2 horas** (configurable en Ajustes, `video.max-duration-s`); los enlaces más largos se rechazan en la validación con el motivo.
- [x] **Aviso por email** cuando quedan menos de X clips pendientes de publicar (umbral y destinatario configurables; SMTP por variables de entorno).
- [x] **Proveedor de email: Resend por SMTP** (§12.3) — sin dependencia de su API; el proveedor se cambia desde el `.env`.
- [x] Subtítulos en **grupos de 3–5 palabras** con la palabra actual resaltada (no una palabra suelta).
- [x] Servidor con **SSD** (en la Pi).
- [x] Empezar con APIs oficiales; interfaz `Publisher` para poder pasar a un intermediario.
- [x] Retención: se borra todo tras publicarse.
- [x] Build de ffmpeg a distribuir (LGPL o GPL) y encoder de fallback por CPU (fase 8): **build LGPL** (sin `libx264`), fijada a un `autobuild-*` concreto (§16.4); encoder de fallback CPU: **`libopenh264`** (incluido en las builds estáticas precompiladas de ffmpeg; sin restricciones LGPL de relinking). La CI compila whisper.cpp para Windows x64 en variantes Vulkan (AMD/NVIDIA/Intel) y CPU, y el instalador lleva la Vulkan; los objetivos Linux (x64, ARM64) están desactivados por ahora. (Movida desde §20.2 en 2026-10.)
- [x] Qué métricas expone cada API para las estadísticas (§12.4): **YouTube** con Data API v3 + Analytics API; **Instagram** con los insights de cada Reel y de la cuenta; **TikTok** solo de la cuenta mientras se publique como borrador. (Movida desde §20.2 en 2026-10.)
- [x] Versión de **OpenJFX**: **25** (misma cadencia que Java 25; groupId `org.openjfx`; módulos `javafx-controls` y `javafx-fxml` — aunque la UI del worker se construye por código sin FXML para simplificar el empaquetado con `jlink`). (Movida desde §20.2 en 2026-10.)

### 20.2 Pendientes
- [ ] Fuente, tamaño y colores de los subtítulos (aplazado). La **posición** ya está decidida: 25 % del alto desde abajo, configurable (§9.2 paso 4).
- [ ] Confirmar que `claude` (Claude Code) funciona en el servidor elegido (Linux ARM64 en la Pi) y que `claude -p` consume el crédito del Agent SDK.
- [ ] Anunciar **capacidad 0 en pausa** para que el servidor deje de asignar tareas a un worker pausado (§8.3, limitación conocida; cambio de 2026-10, sin implementar).

## 21. Riesgos y advertencias

- **Derechos de autor**: resubir contenido ajeno puede infringirlos, y las plataformas penalizan el contenido reciclado. Pensado para contenido propio o con permiso.
- **Auditorías** de TikTok y Google y **revisión de app** de Meta: pueden tardar; mientras tanto, publicación privada o en borrador.
- **yt-dlp** se rompe cuando las plataformas cambian; hay que mantenerlo actualizado.
- Los **límites y formatos** de cada API cambian con el tiempo: revisarlos al integrarlas.

## 22. Revisión técnica

Revisión del documento completo buscando incongruencias, huecos y riesgos antes de empezar a programar. Cada punto tiene una etiqueta (*R1*…*R17*) que aparece en la sección afectada.

### 22.1 Incongruencias encontradas (corregidas)

| # | Problema | Corrección |
|---|---|---|
| R1 | Instagram necesita una **URL pública** del vídeo, pero el único acceso público (el túnel) está detrás de Cloudflare Access, que Instagram no puede pasar, y el puerto de workers solo existe en Tailscale. Tal como estaba, **Instagram no podía funcionar**. | Tercera ruta pública `media` sin Access, solo `/media/{token}` con URLs firmadas de corta duración y un conector dedicado (sección 3.2). |
| R3 | "Programar y publicar automáticamente" no se cumple mientras TikTok y YouTube no estén auditados: los borradores de TikTok y el paso a público en YouTube son manuales. | Documentado qué es automático en cada fase (sección 11.4). |
| R7 | El worker descargaba el **vídeo fuente completo** para renderizar cada clip: con un directo largo, varios GB por worker. | El servidor corta el trozo de cada clip sin recodificar y el worker solo descarga eso (paso 10). |
| R8 | El clip se borraba solo al publicarse **en todas** las plataformas: un fallo permanente en una dejaba el fichero (y el vídeo fuente) para siempre, contradiciendo el "no queda rastro". | Se borra cuando no queda ninguna subida pendiente, incluido `FAILED`; más un borrado por antigüedad (sección 15). |
| R9 | Con un solo contador de intentos, cerrar 3 veces la tapa del portátil marcaba la tarea como `FAILED` aunque la tarea estuviera bien. | Contadores separados para fallos y desconexiones (sección 6.3). |
| R10 | El título único para las tres plataformas no tenía en cuenta el **límite de 100 caracteres de YouTube** ni el de 2.200 de TikTok/Instagram. | Límites explícitos en el encargo a Claude y validación en el servidor (sección 10.3). |

### 22.2 Huecos encontrados (añadidos)

| # | Hueco | Solución |
|---|---|---|
| R2 | No estaba definido **cuándo** se publica cada clip. | **Resuelto**: planificador diario con Claude (misma hora en todas las plataformas, §10.4, cambio de 2026-10; número de clips adaptado a la cola), validado por el servidor, con fallback y modo inmediato para pruebas (sección 10.4). |
| R4 | Si el servidor se reinicia a mitad de una subida, podía **publicar dos veces**. | Idempotencia guardando el id externo y consultando el estado al reiniciar (sección 11.3). |
| R5 | Un directo de varias horas era **una sola tarea de transcripción** de horas: si fallaba al final, se perdía todo. | Transcripción por trozos de ~10 min con solape (paso 5). |
| R6 | Sin límite de **duración ni de espacio en disco**: un VOD de 8 h a 1080p60 puede llenar el disco. | Servidor con SSD; comprobación de espacio libre y **máximo de 2 h** por vídeo fuente (pasos 2 y 3). |
| R11 | Sin control de los **topes diarios** de cada plataforma. | El scheduler consulta los límites y retrasa en vez de fallar (sección 11.3). |
| R12 | Si **Claude fallaba**, el trabajo se quedaba atascado. | Se continúa sin recortes de Claude y pasa a revisión (paso 6). |
| R13 | No estaba definido cómo se **da de alta un worker** ni cómo se revoca. | Alta desde el superadmin con token mostrado una vez y revocación (sección 7). |
| R14 | Sin **versionado del protocolo**: un worker antiguo podría procesar tareas con un formato incompatible. | Versión en `HELLO` y rechazo si no coincide (sección 7). |
| R15 | Los **logs** crecían sin límite y conservaban títulos y enlaces, en contra del "no queda rastro". | Retención limitada configurable (sección 14). |
| R16 | No había **estrategia de pruebas**. | Unitarias, integración con Testcontainers, worker simulado y render en CI (sección 16.3). |
| R17 | Sin **licencia**, y redistribuir ffmpeg tiene implicaciones de licencia. | **Resuelto**: Apache-2.0; build LGPL de ffmpeg con `libopenh264` como encoder por CPU, decidida en la fase 8 (secciones 16.5 y 20.1), y licencias de terceros en el instalador (§16.4). |

### 22.3 Otras observaciones
- **SponsorBlock**: la opción `--sponsorblock-remove` de yt-dlp eliminaría los tramos al descargar, saltándose el análisis y el modo revisión. Se usan solo como **datos** (paso 3).
- **Estado del trabajo**: con varios clips en fases distintas, el estado de `video_job` es un **resumen derivado** de sus clips (p. ej. `PUBLISHING` mientras quede alguno por publicar), no un estado que se actualice a mano.
- **Linux + AMD** usa `h264_vaapi`, que necesita un comando distinto (`hwupload`): la interfaz `Encoder` debe construir el comando completo, no solo cambiar el nombre del códec.
- **SSE a través de Cloudflare Tunnel** funciona, pero conviene enviar un comentario de *keep-alive* cada pocos segundos para que no se corte la conexión de logs en directo.
- **Orden del plan**: el hito de la fase 4 (enlace → clips renderizados) es el primer resultado útil de punta a punta. Hasta ahí no conviene pulir la UI ni el empaquetado.

