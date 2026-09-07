/**
 * TFG - Escape room mental con Temi + MUSE
 * Generador del cuestionario post-experimento (versión mejorada).
 *
 * CÓMO USARLO:
 *   1. Entra en https://script.google.com con una cuenta @gmail PERSONAL
 *      (en la cuenta de la universidad Apps Script suele estar bloqueado).
 *   2. "Nuevo proyecto" -> borra el contenido y pega TODO este archivo.
 *   3. Selecciona la función  crearFormulario  (arriba)  ->  botón "Ejecutar".
 *      La primera vez Google pedirá autorizar el acceso a tus Formularios: acéptalo.
 *   4. En "Registro de ejecución" aparecerán los enlaces de edición y de respuesta.
 *
 * QUÉ CUBRE (indicaciones de la profesora, Raquel):
 *   - Qué les ha gustado / qué no  -> sección "Comentarios finales".
 *   - Si han superado o no los retos -> una pregunta POR SALA de cada reto.
 *   - Con qué / qué problemas -> checklist + descripción por reto.
 *   - Qué soluciones se plantean -> pregunta abierta por reto y global.
 *   (Los problemas que TÚ observas durante la prueba van en una hoja de
 *    observación aparte + en los eventos de tus CSV de sesión.)
 *
 * NOTA DE PUNTUACIÓN (para tu análisis, NO va en el formulario):
 *   - Sense of Agency: ítems 2, 6 y 7 son INVERTIDOS (reverse-scored).
 *   - UX/Engagement: ítems 3, 4 y 5 (frustración, confusión, agotamiento) INVERTIDOS.
 */

// =============================================================================
// CONFIGURACIÓN EDITABLE: los tres retos jugables y sus salas, en orden.
// Cambia aquí los nombres o añade/quita salas y el formulario se regenera solo.
// =============================================================================
// Cada sala lleva entre paréntesis una pista de lo que había que hacer, por si
// el participante no la recuerda por el nombre.
var RETOS = [
  {
    nivel: 'El Escape Clásico',
    salas: [
      'La Puerta de la Calma (relajarte y mantener la calma para abrir la puerta)',
      'El Código Secreto (escribir una letra en Morse con los parpadeos)',
      'El Guardián de la Sala (responder sí o no moviendo la cabeza)',
      'La Cerradura Final (parpadear y después apretar la mandíbula)'
    ]
  },
  {
    nivel: 'Aventura Espacial',
    salas: [
      'Puente de mando (escuchar el informe inicial de la nave)',
      'Cámara de criogenia (mantener la calma para estabilizar tus constantes)',
      'Rumbo de la nave (elegir la ruta respondiendo con la cabeza)',
      'El cinturón de asteroides (responder a la baliza en Morse con parpadeos)',
      'Maniobra evasiva (parpadear para el escudo y apretar la mandíbula para impulsar)',
      'Reserva de oxígeno (mantener la calma para ahorrar oxígeno)',
      'Secuencia de aterrizaje (parpadear y apretar la mandíbula para acoplar la nave)'
    ]
  },
  {
    nivel: 'El Castillo Encantado',
    salas: [
      'El Grimorio (trazar la runa en Morse con los parpadeos)',
      'El Espejo que Miente (responder sí o no moviendo la cabeza)',
      'El Eco del Hechizo (repetir la runa en Morse con parpadeos)',
      'La Cripta (mantener la calma durante más tiempo)',
      'El Portón de Hierro (parpadear y apretar la mandíbula sin demora)'
    ]
  }
];

// Escala Likert de 5 puntos, idéntica en todas las secciones.
var LIKERT = [
  '1. Totalmente en desacuerdo',
  '2. En desacuerdo',
  '3. Ni de acuerdo ni en desacuerdo',
  '4. De acuerdo',
  '5. Totalmente de acuerdo'
];

// Respuestas para "¿superaste la sala?".
var SUPERADO = ['Sí, sin ayuda', 'Sí, con algo de ayuda', 'No la conseguí', 'No llegué a jugarla'];

// Cuántas preguntas como máximo por página (el formulario avanza con "Siguiente").
var PREGUNTAS_POR_PAGINA = 6;

function crearFormulario() {
  var form = FormApp.create('Cuestionario - Escape room mental (Temi + MUSE)');
  form.setDescription(
    'Cuestionario posterior a la experiencia. Tus respuestas son anónimas y ' +
    'se usarán únicamente con fines de investigación de este Trabajo de Fin de Grado. ' +
    'Puedes retirarte en cualquier momento sin dar explicaciones.'
  );
  form.setProgressBar(true);
  form.setCollectEmail(false); // no recogemos correo: mantiene el anonimato

  // ---------------------------------------------------------------------------
  // PÁGINA 1 - Información, consentimiento y escalas
  // ---------------------------------------------------------------------------
  form.addSectionHeaderItem()
      .setTitle('Información y consentimiento')
      .setHelpText('Los campos marcados con * son obligatorios.');

  // Código anónimo asignado por el investigador (NO derivado del DNI -> RGPD).
  form.addTextItem()
      .setTitle('Código de participante')
      .setHelpText('Introduce el código que te ha proporcionado el investigador (p. ej. P01).')
      .setRequired(true);

  form.addMultipleChoiceItem()
      .setTitle('Género')
      .setChoiceValues(['Masculino', 'Femenino', 'Otro', 'Prefiero no decirlo'])
      .setRequired(true);

  var edad = form.addTextItem().setTitle('Edad').setRequired(true);
  edad.setValidation(
    FormApp.createTextValidation()
      .setHelpText('Introduce tu edad como un número (18-99).')
      .requireNumberBetween(18, 99)
      .build()
  );

  form.addCheckboxItem()
      .setTitle('Consentimiento informado')
      .setChoiceValues([
        'Confirmo que he leído y entendido la información sobre este estudio y que ' +
        'acepto participar voluntariamente. Sé que puedo retirarme en cualquier momento ' +
        'sin dar explicaciones.'
      ])
      .setRequired(true);

  form.addSectionHeaderItem().setTitle('La prueba que has hecho');
  form.addMultipleChoiceItem()
      .setTitle('¿Con qué has hecho la prueba?')
      .setChoiceValues(['Con la tablet', 'Con el robot'])
      .setRequired(true);

  // --- Sense of Agency (8 ítems) --- (se reparte en páginas)
  anadirEscala(form, 'Test de Sense of Agency',
    'Valora tu grado de acuerdo con cada afirmación.', [
    'Tengo el control total de lo que hago durante mi interacción con el robot/tablet.',
    'Me siento como un instrumento guiado por el robot/tablet u otra entidad.',
    'Siento que soy el autor de mis acciones cuando interactúo con el robot/tablet.',
    'Las decisiones que tomo al interactuar con el robot/tablet dependen únicamente de mi libre albedrío.',
    'Decidir si actuar y cuándo hacerlo durante la interacción con el robot/tablet depende de mí.',
    'Nada de lo que hago con el robot/tablet es realmente voluntario.',
    'Mientras interactúo con el robot/tablet, siento que soy como un robot controlado a distancia.',
    'Me siento completamente responsable de todo lo que resulta de mi interacción con el robot/tablet.'
  ]);

  // --- Game Engagement Questionnaire (GEQ, 19 ítems) --- (se reparte en páginas)
  anadirEscala(form, 'Test de Game Engagement (GEQ)',
    'Valora tu grado de acuerdo con cada afirmación.', [
    'Durante el ejercicio, perdí la noción del tiempo.',
    'Las cosas parecían suceder automáticamente.',
    'Me siento diferente.',
    'Me siento asustado/a.',
    'El juego se siente real.',
    'Si alguien me habla, no lo escucho.',
    'Me pongo nervioso/a.',
    'El tiempo parece detenerse o pasar muy lento.',
    'Me siento desconectado/a o ausente.',
    'No respondo cuando alguien me habla.',
    'No noto que me estoy cansando.',
    'Jugar se siente automático.',
    'Mis pensamientos van muy rápido.',
    'Pierdo la noción de dónde estoy.',
    'Juego sin pensar en cómo jugar.',
    'Jugar me hace sentir en calma.',
    'Juego durante más tiempo del que pensaba.',
    'Me meto completamente en el juego.',
    'Siento que simplemente no puedo dejar de jugar.'
  ]);

  // --- Experiencia de usuario (15 ítems) --- (una página por bloque de subescalas)
  anadirEscalaAgrupada(form, 'Test de experiencia de usuario',
    'Valora tu grado de acuerdo con cada afirmación.', [
    { sub: 'Implicación e inmersión', items: [
      'Me dejé llevar completamente en esta experiencia.',
      'Estuve tan involucrado/a en esta experiencia que perdí la noción del tiempo.'
    ]},
    { sub: 'Usabilidad y esfuerzo', items: [
      'Me sentí frustrado/a mientras usaba el robot/tablet para hacer el ejercicio.',
      'La interacción con el robot/tablet me pareció confusa.',
      'Usar el robot/tablet para hacer el ejercicio fue agotador.'
    ]},
    { sub: 'Atractivo estético y sensorial', items: [
      'El robot tenía una apariencia estéticamente agradable.',
      'La interacción con el robot/tablet estimuló mis sentidos.'
    ]},
    { sub: 'Valor percibido y recomendación', items: [
      'Usar el robot/tablet para hacer el ejercicio valió la pena.',
      'Recomendaría el uso del robot/tablet a mi familia y amigos.'
    ]},
    { sub: 'Curiosidad', items: [
      'Seguiría usando el robot/tablet por curiosidad.',
      'El contenido proporcionado por el robot/tablet despertó mi curiosidad.'
    ]},
    { sub: 'Satisfacción e interés', items: [
      'Mi experiencia fue gratificante.',
      'Me sentí involucrado/a en esta experiencia.',
      'Me sentí interesado/a en esta experiencia.',
      'Hacer la actividad a través de la interacción con el robot/tablet fue divertido.'
    ]}
  ]);

  // ---------------------------------------------------------------------------
  // PÁGINA 2 - Selección del reto (ramifica a la sección de cada reto)
  // ---------------------------------------------------------------------------
  form.addPageBreakItem()
      .setTitle('Sobre el escape room que has jugado')
      .setHelpText('Ahora unas preguntas concretas sobre tu partida.');

  var selectorReto = form.addMultipleChoiceItem()
      .setTitle('¿Qué escape room has jugado?')
      .setRequired(true);
  // Las opciones y su navegación se asignan más abajo, cuando existan las páginas.

  // ---------------------------------------------------------------------------
  // Una PÁGINA por reto, generada a partir de RETOS.
  // ---------------------------------------------------------------------------
  var paginasReto = [];
  for (var r = 0; r < RETOS.length; r++) {
    var reto = RETOS[r];
    var pagina = form.addPageBreakItem().setTitle(reto.nivel);
    paginasReto.push(pagina);

    // Una pregunta de superación por cada sala del reto.
    form.addSectionHeaderItem()
        .setTitle('¿Cómo te fue en cada sala?')
        .setHelpText('Dinos si conseguiste superarla. Si el juego se bifurcó y no llegaste ' +
                     'a alguna sala, marca "No llegué a jugarla".');
    for (var s = 0; s < reto.salas.length; s++) {
      form.addMultipleChoiceItem()
          .setTitle((s + 1) + '. ' + reto.salas[s])
          .setChoiceValues(SUPERADO)
          .setRequired(true);
    }

    // ¿En qué sala hubo problemas? (checklist con las salas del reto).
    var conProblemas = reto.salas.slice();
    conProblemas.push('En ninguna, fue todo fluido');
    form.addCheckboxItem()
        .setTitle('¿En qué sala(s) te costó más o tuviste algún problema?')
        .setChoiceValues(conProblemas);

    // Descripción del problema y solución propuesta.
    form.addParagraphTextItem()
        .setTitle('Si tuviste algún problema, cuéntanos qué pasó y por qué')
        .setHelpText('Por ejemplo: no entendía las instrucciones, no me salía el ' +
                     'parpadeo o el gesto, el robot no respondía, tuve que preguntar, ' +
                     'me puse nervioso/a...');
    form.addParagraphTextItem()
        .setTitle('¿Se te ocurre alguna forma de mejorar este escape room?');
  }

  // ---------------------------------------------------------------------------
  // PÁGINA FINAL - Comentarios generales (común a todos los retos)
  // ---------------------------------------------------------------------------
  var paginaFinal = form.addPageBreakItem()
      .setTitle('Para terminar')
      .setHelpText('Estas preguntas son opcionales, pero nos ayudan mucho. ¡Gracias!');
  form.addParagraphTextItem().setTitle('¿Qué es lo que más te ha gustado de la experiencia?');
  form.addParagraphTextItem().setTitle('¿Y lo que menos?');
  form.addParagraphTextItem().setTitle('Si pudieras cambiar algo del sistema, ¿qué sería?');
  form.addParagraphTextItem().setTitle('¿Quieres añadir algo más?');

  // ---------------------------------------------------------------------------
  // Conexión de la navegación: cada reto salta a "Comentarios finales".
  // ---------------------------------------------------------------------------
  var opciones = [];
  for (var i = 0; i < RETOS.length; i++) {
    paginasReto[i].setGoToPage(paginaFinal);
    opciones.push(selectorReto.createChoice(RETOS[i].nivel, paginasReto[i]));
  }
  selectorReto.setChoices(opciones);

  // ---------------------------------------------------------------------------
  Logger.log('Formulario creado.');
  Logger.log('Editar:    ' + form.getEditUrl());
  Logger.log('Responder: ' + form.getPublishedUrl());
}

/** Añade un ítem Likert obligatorio ya numerado. */
function anadirItemLikert(form, numero, texto) {
  form.addMultipleChoiceItem()
      .setTitle(numero + '. ' + texto)
      .setChoiceValues(LIKERT)
      .setRequired(true);
}

/**
 * Escala Likert repartida en varias páginas (el formulario avanza con "Siguiente").
 * Reparte los ítems en páginas lo más equilibradas posible, sin pasar de
 * PREGUNTAS_POR_PAGINA por página. La numeración es continua en toda la escala.
 */
function anadirEscala(form, titulo, ayuda, afirmaciones) {
  var n = afirmaciones.length;
  var nPaginas = Math.ceil(n / PREGUNTAS_POR_PAGINA);
  var base = Math.floor(n / nPaginas);
  var resto = n % nPaginas;
  var idx = 0;
  for (var p = 0; p < nPaginas; p++) {
    var enEsta = base + (p < resto ? 1 : 0);
    var pb = form.addPageBreakItem()
                 .setTitle(p === 0 ? titulo : titulo + ' (continuación)');
    if (p === 0 && ayuda) pb.setHelpText(ayuda);
    for (var k = 0; k < enEsta; k++) {
      anadirItemLikert(form, idx + 1, afirmaciones[idx]);
      idx++;
    }
  }
}

/**
 * Escala Likert con subescalas, repartida en páginas: agrupa subescalas
 * completas en cada página sin pasar de PREGUNTAS_POR_PAGINA (una subescala
 * nunca se parte a la mitad). Numeración continua en toda la escala.
 */
function anadirEscalaAgrupada(form, titulo, ayuda, grupos) {
  var idx = 0, enPagina = 0, primera = true, abierta = false;
  for (var g = 0; g < grupos.length; g++) {
    var grupo = grupos[g];
    var noCabe = enPagina > 0 && (enPagina + grupo.items.length) > PREGUNTAS_POR_PAGINA;
    if (!abierta || noCabe) {
      var pb = form.addPageBreakItem()
                   .setTitle(primera ? titulo : titulo + ' (continuación)');
      if (primera && ayuda) pb.setHelpText(ayuda);
      primera = false; abierta = true; enPagina = 0;
    }
    form.addSectionHeaderItem().setTitle(grupo.sub);
    for (var k = 0; k < grupo.items.length; k++) {
      anadirItemLikert(form, idx + 1, grupo.items[k]);
      idx++; enPagina++;
    }
  }
}
