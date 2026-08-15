# Cuentivo Premium UI Design

## Objetivo

Elevar Cuentivo, después de su lanzamiento público, a una aplicación móvil nativa de administración de negocios con una estética SaaS financiera moderna, ligera y confiable. El rediseño conserva toda la lógica, Firebase, Room, autenticación, persistencia y rutas actuales.

## Alcance y secuencia

El trabajo es incremental y no forma parte del AAB 30 / 1.16.5. Cada fase debe ser integrable, verificable y reversible por separado:

1. Fundaciones: tema claro/oscuro real, tokens, primitives y pruebas visuales.
2. Alto impacto: shell de navegación, Dashboard y Productos.
3. Flujos operativos: detalle, Clientes, formularios, estados vacíos, carga y feedback.
4. Pulido: motion, accesibilidad, rendimiento y regresión en dispositivos pequeños/grandes.

No se añade analítica, predicción, inventario o filtros nuevos si la capa de dominio no ofrece ya los datos necesarios. Las mejoras muestran únicamente datos actuales.

## Dirección visual

La referencia dominante es un SaaS móvil de inventario/finanzas: superficies sobrias, jerarquía de datos evidente, densidad cómoda y decisiones visuales orientadas a acción. Dashboard usa Bento moderado; Productos usa composición Retail CRM; insights existentes reciben una capa premium discreta. Material 3 aporta comportamiento, accesibilidad y feedback, no una apariencia genérica.

Se eliminan gradualmente los rasgos que contradicen esa dirección: claymorphism pesado, aureolas cian/violeta de estilo futurista, bordes constantes y cards anidadas. No se usarán glow permanente, gradientes de pantalla, glassmorphism general, sombras grandes ni iconos decorativos.

## Restricciones innegociables

- No cambiar backend, Firebase, Firestore rules, Room, modelos, repositorios, autenticación, billing ni lógica financiera.
- No cambiar rutas o semántica de navegación salvo el aspecto de sus controles.
- No eliminar funcionalidades que funcionen.
- Mantener `minSdk 24`; no depender de blur/RenderEffect ni APIs visuales sin fallback.
- Respetar fuente del sistema, escala de texto, TalkBack, contraste y controles de 48 dp.
- Evitar dependencias nuevas salvo que una necesidad de UI no pueda resolverse con Compose/Material 3 ya presentes. No introducir Rive, Lottie, AGSL ni una librería de gráficas en la primera fase.
- Cada modificación debe tener pruebas focalizadas; el rediseño no puede empeorar las pruebas de autenticación, Pro, recordatorios o navegación existentes.

## Sistema de diseño

### Color y elevación

Definir esquemas Material 3 explícitos para claro y oscuro. Claro: fondo neutral cálido muy pálido, superficies blancas y texto casi negro suavizado. Oscuro: grafito/azul profundo, superficies tonales escalonadas y texto blanco suavizado; nunca inversión mecánica del claro ni negro puro generalizado.

Los colores de marca se reservan para acción primaria, selección y énfasis. Crear tokens semánticos para éxito, advertencia, vencido/error, información y stock; cada estado debe comunicar icono o texto además de color. Contenedores y bordes deben usar roles de `ColorScheme`, no valores HEX locales.

### Tipografía, forma y espaciado

Conservar la escala existente de 4, 8, 12, 16, 20, 24 y 32 dp; ampliar tokens de forma a radio de fila, control, panel y hero dentro del rango 16–24 dp según jerarquía. Definir roles claros: métrica principal, título de sección, título de fila, cuerpo, etiqueta y valor monetario. No usar negritas masivas ni familias monoespaciadas para todos los importes si reducen legibilidad.

### Primitives reutilizables

Evolucionar, no duplicar, `NeoFintechTokens.kt` y `NeoFintechComponents.kt`. El conjunto final debe incluir como mínimo: screen rail responsive, app bar contextual, panel/hero de métrica, tarjeta de métrica secundaria, chip de estado semántico, fila de directorio, campo de búsqueda/filtros, sección de formulario, estado vacío accionable, skeleton estático/animado con respeto a escala de animación, y feedback de guardado/snackbar.

## Navegación y shell

Mantener los cuatro destinos y la acción Nueva venta. Reemplazar progresivamente la barra clay por una NavigationBar Material 3 o una variante propia sobria: icono y etiqueta, indicador seleccionado perceptible, animación de 150–250 ms, alto contraste y áreas táctiles de 48 dp. En fuente grande seguirá el modo compacto comprobado por pruebas, sin etiquetas truncadas.

El encabezado contextual debe priorizar cuenta/negocio y alertas reales sin duplicar títulos de detalle. No se añade un destino “Más” ni se mueven rutas en esta fase.

## Dashboard

Usar exclusivamente `DashboardUiState.Content` y datos existentes. La composición debe priorizar una métrica financiera real (por ejemplo cartera por cobrar) como hero; las métricas existentes de vendido/cobrado se muestran secundarias. `insights`, clientes críticos y actividad reciente pasan a módulos separados con espacio vertical consistente.

No se añade una gráfica si no hay serie temporal fiable expuesta por el estado. En su lugar, los insights y actividad existente obtienen una composición premium. Una gráfica futura requiere una tarea de dominio independiente que defina fuente, rango, agregación, vacíos y pruebas.

## Productos

La lista usa `LazyColumn` y `ProductEntity` actual: imagen/fallback, nombre, categoría/SKU cuando existan, precio y stock cuando exista. La fila se convierte en una composición Retail CRM escaneable y no inventa proveedor, ventas, categoría ni stock ausentes.

El stock se expresa como: sin dato, disponible, stock bajo o sin stock. El umbral de “stock bajo” no se inventará: hasta existir una política de negocio configurada, mostrar el valor real y usar solo “sin stock” cuando sea cero. La búsqueda existente se vuelve control prominente y los filtros se mejoran solo visualmente hasta que exista la lógica correspondiente.

El CTA Agregar producto conserva el flujo actual y se integra como FAB extendido/contextual o acción visible sin tapar filas. La edición/archivo y selección múltiple conservan semántica accesible.

## Clientes, detalle y formularios

Clientes reutiliza la misma jerarquía de filas: inicial/avatar solo si se puede derivar localmente, nombre, contacto existente, saldo y estado de deuda real. El detalle prioriza identidad, saldo/estado y actividad existente antes de información secundaria.

Formularios agrupan campos actuales en secciones (general, precio/inventario, imagen) sin añadir campos ni modificar validación. Se mantiene el comportamiento de guardar, errores y borrado; se añade feedback de progreso y éxito usando componentes existentes de estado y snackbar cuando corresponda.

## Estados y motion

Los vacíos explican el siguiente paso e incluyen CTA cuando el flujo ya lo ofrece. Carga de listas/pantallas usa skeleton de baja carga donde el espacio final sea conocido; spinner se mantiene para operaciones breves o bloqueantes. Motion sólo usa 150–350 ms, fade/slide corto, `animateContentSize` y escala de presión sutil; se desactiva si el sistema desactiva animaciones.

## Criterios de aceptación globales

- Claro, oscuro y seguir sistema se renderizan con esquemas distintos, sin valores hard-coded fuera de tokens.
- 320 dp, teléfono estándar, teléfono grande y fuente 1.3 conservan contenido principal, acciones y navegación accesibles; fuente 2.0 no recorta la acción crítica.
- TalkBack anuncia destino, estado, acciones y valor relevante sin depender de color.
- Dashboard, Productos y Clientes usan listas perezosas donde ya las usan; no hay animación infinita, blur obligatorio ni recomposición de lista por frame.
- No se introducen datos o funciones inexistentes; no se altera ningún contrato de dominio/sincronización.
- Las suites unitarias e instrumentadas relevantes continúan verdes y se agregan pruebas semánticas/visual-regresión por cada primitive crítica.

## Roles

- Dirección, contrato y auditoría final: Terra.
- Implementación por fases y TDD: OpenCode con DeepSeek V4 Flash.
- Revisión de diseño, Compose y riesgos de UX: Claude Code con Opus.
- Las decisiones de alcance o cambios que afecten datos se escalan al propietario antes de implementarse.
