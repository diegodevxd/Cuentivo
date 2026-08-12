# Diseño: navegación adaptable y accesible

## Objetivo

Evitar que los textos de navegación y acciones críticas de Cuentivo se recorten en Android de cualquier antigüedad, fabricante, ancho de pantalla o escala de fuente elegida por la persona usuaria. La solución debe conservar la lectura grande, los iconos claros y áreas táctiles amplias; nunca reducir artificialmente la preferencia de tamaño de letra del sistema.

## Alcance

La primera intervención cubre la navegación global y establece una política reutilizable para el resto de la interfaz:

- La barra inferior tiene cuatro destinos: Inicio, Clientes, Productos y Abonos.
- Nueva venta sigue siendo una acción, no un destino persistente.
- En una configuración cómoda, la barra muestra icono y etiqueta de cada destino, más la acción Nueva venta.
- Cuando el ancho disponible o la escala de fuente impidan mostrar las cinco etiquetas completas, los destinos se representan con iconos grandes y nombres accesibles para TalkBack; Nueva venta se presenta como botón flotante extendido sobre la barra, con su texto completo.
- La variante se decide con el ancho disponible y `fontScale`, no por marca, modelo ni versión de Android.

## Política de texto adaptable

1. No fijar acciones críticas a una línea si esa restricción puede recortar su significado.
2. Un control puede crecer hasta dos líneas cuando el flujo admite altura adicional; si no, el contenedor debe reorganizarse a otra variante.
3. La elipsis solo es aceptable en datos secundarios que puedan recuperarse al abrir el elemento. Nunca se usa para nombres de acciones, confirmaciones ni destinos principales.
4. Se respeta el `fontScale` del sistema: no se aplica escalado inverso ni un tamaño visual menor para forzar que el texto quepa.
5. Todos los controles mantienen un objetivo táctil de al menos 48 dp y contenido accesible equivalente cuando una etiqueta visual no se muestre.

## Implementación propuesta

La shell Compose calcula una variante compacta a partir de restricciones de composición (`BoxWithConstraints`) y la escala actual (`LocalDensity.fontScale`). No se añaden dependencias.

- **Variante etiquetada:** conserva el patrón actual para anchos y escala donde las etiquetas completas caben sin truncamiento.
- **Variante compacta:** presenta cuatro iconos de destino con semántica y `contentDescription`; muestra una acción extendida Nueva venta fuera de la hilera para que su rótulo completo conserve tamaño legible.
- **Componentes revisados:** se auditan los usos de `maxLines = 1`, `TextOverflow.Clip` y filas de controles de ancho fijo. Cada caso se clasifica como acción crítica, dato secundario o texto descriptivo y se ajusta según la política anterior.

## Pruebas y aceptación

- Pruebas Compose cubren como mínimo un ancho compacto y una escala de fuente alta, y verifican que Nueva venta siga disponible y que los destinos conserven semántica accesible.
- Pruebas unitarias de la política de navegación verifican qué variante se elige en los umbrales definidos.
- Verificación visual en el AVD Medium_Phone y en el teléfono A059P, con escala predeterminada y una escala grande configurada temporalmente y restaurada al finalizar.
- No se desinstala ni reinstala la aplicación del teléfono físico salvo que sea necesario para una validación posterior y se haga explícitamente dentro de la tarea de prueba.
- Sin cambios a Firebase, Firestore, reglas, datos de negocio ni flujos de venta.

## Riesgos y mitigación

- Una barra más alta puede tapar el contenido: el margen inferior compartido (`NeoLayout.bottomBarClearance`) debe derivarse de la variante activa o reservar el máximo seguro.
- Ocultar etiquetas visuales no debe reducir accesibilidad: los iconos deben conservar nombre, estado seleccionado y rol de pestaña en la semántica.
- La acción flotante no debe ocultar campos ni botones en Nueva venta: las pantallas conservan el espacio inferior de seguridad y la acción se oculta o se adapta al abrir el flujo de venta.
