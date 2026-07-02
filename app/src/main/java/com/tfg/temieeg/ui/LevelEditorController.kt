package com.tfg.temieeg.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tfg.temieeg.R
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.databinding.ActivityMainBinding
import com.tfg.temieeg.game.BlinkClenchModule
import com.tfg.temieeg.game.CalmModule
import com.tfg.temieeg.game.CustomLevelStorage
import com.tfg.temieeg.game.EscapeRoomDef
import com.tfg.temieeg.game.MorseModule
import com.tfg.temieeg.game.RobotAction
import com.tfg.temieeg.game.RobotAnimModule
import com.tfg.temieeg.game.VideoStateModule
import com.tfg.temieeg.game.YesNoModule
import com.tfg.temieeg.game.YesNoQuestion

/**
 * Editor de niveles del Escape Room — extraído de [MainActivity].
 *
 * Gestiona la lista de niveles personalizados, el constructor visual de salas,
 * la previsualización, el guardado y el import/export vía [CustomLevelStorage].
 *
 * Debe instanciarse en [MainActivity.onCreate]: los ActivityResultLauncher de
 * vídeo e importación se registran en el constructor, y Android exige que el
 * registro ocurra antes de onStart.
 *
 * @param getLocations   ubicaciones guardadas del robot Temi (para las acciones GOTO).
 * @param onPlayLevel    lanza una partida con el nivel indicado.
 * @param onNavigateHome vuelve a la pantalla principal.
 */
class LevelEditorController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val getLocations: () -> List<String>,
    private val onPlayLevel: (EscapeRoomDef) -> Unit,
    private val onNavigateHome: () -> Unit
) {

    private data class RoomConfig(
        val type: String,
        val title: String,
        val narration: String,
        val hint: String,
        val secondsRequired: Int = 5,
        val letterPool: String = "ETISAN",
        val jawWindowMs: Long = 4000L,   // BLINK_CLENCH
        val durationMs: Long = 3000L,    // ANIM
        val questions: List<YesNoQuestion> = emptyList(),
        var videoPath: String? = null,
        val robotActions: List<RobotAction> = emptyList(),
        val targetState: String = "CALM"
    )

    private val pendingRoomConfigs = mutableListOf<RoomConfig>()
    private var editingLevelId: String? = null
    private var pendingVideoSlot = -1
    private var pendingIntroVideoPath: String? = null
    private var pendingTransitionVideoPath: String? = null

    private val videoPicker =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null || pendingVideoSlot < 0) return@registerForActivityResult
            val levelId = editingLevelId ?: "lvl_${System.currentTimeMillis()}"
            if (editingLevelId == null) editingLevelId = levelId
            val path = CustomLevelStorage.copyVideo(activity, levelId, pendingVideoSlot, uri)
            if (path != null) {
                pendingRoomConfigs[pendingVideoSlot] = pendingRoomConfigs[pendingVideoSlot].copy(videoPath = path)
                refreshRoomCards()
                Toast.makeText(activity, activity.getString(R.string.video_attached), Toast.LENGTH_SHORT).show()
            }
            pendingVideoSlot = -1
        }

    private val introVideoPicker =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val levelId = editingLevelId ?: "lvl_${System.currentTimeMillis()}"
            if (editingLevelId == null) editingLevelId = levelId
            val path = CustomLevelStorage.copyNamedVideo(activity, levelId, "intro", uri)
            if (path != null) {
                pendingIntroVideoPath = path
                binding.btnIntroVideo.text = "🎬✓ Entrada"
                Toast.makeText(activity, "Vídeo de entrada adjunto", Toast.LENGTH_SHORT).show()
            }
        }

    private val transitionVideoPicker =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val levelId = editingLevelId ?: "lvl_${System.currentTimeMillis()}"
            if (editingLevelId == null) editingLevelId = levelId
            val path = CustomLevelStorage.copyNamedVideo(activity, levelId, "transition", uri)
            if (path != null) {
                pendingTransitionVideoPath = path
                binding.btnTransitionVideo.text = "🎬✓ Transición"
                Toast.makeText(activity, "Vídeo de transición adjunto", Toast.LENGTH_SHORT).show()
            }
        }

    private val levelImportPicker = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AlertDialog.Builder(activity)
            .setTitle("Importar niveles")
            .setMessage("ZIP → niveles + vídeos incluidos.\nJSON → solo estructura, sin vídeos.\n\n¿Cómo quieres importar?")
            .setPositiveButton("Reemplazar todo") { _, _ ->
                val n = CustomLevelStorage.importFromUri(activity, uri, replace = true)
                val msg = when {
                    n > 0  -> "Importados $n niveles (anteriores eliminados)"
                    n == 0 -> "El fichero no contiene niveles válidos"
                    else   -> "Error: fichero no reconocido (usa ZIP o JSON)"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                if (n > 0) refreshLevelList()
            }
            .setNeutralButton("Añadir / fusionar") { _, _ ->
                val n = CustomLevelStorage.importFromUri(activity, uri, replace = false)
                val msg = when {
                    n > 0  -> "Añadidos $n niveles nuevos"
                    n == 0 -> "El fichero no contiene niveles válidos"
                    else   -> "Error: fichero no reconocido (usa ZIP o JSON)"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                if (n > 0) refreshLevelList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Conecta los botones del editor — equivalente al antiguo setupLevelEditor(). */
    fun setup() {
        binding.btnCreateLevel.setOnClickListener     { showLevelEditor() }
        binding.btnLevelEditorBack.setOnClickListener {
            if (binding.levelBuilderPanel.visibility == View.VISIBLE) showLevelList()
            else onNavigateHome()
        }
        binding.btnNewLevel.setOnClickListener        { openLevelBuilder(null) }
        binding.btnAddRoom.setOnClickListener         { showRoomTypePicker() }
        binding.btnSaveLevel.setOnClickListener       { saveCurrentLevel() }
        binding.btnPreviewLevel.setOnClickListener    { showLevelPreview() }
        binding.btnIntroVideo.setOnClickListener      { introVideoPicker.launch("video/*") }
        binding.btnTransitionVideo.setOnClickListener { transitionVideoPicker.launch("video/*") }

        binding.btnExportLevels.setOnClickListener {
            val file = CustomLevelStorage.exportZip(activity)
            if (file != null) {
                shareFile(file, "application/zip", "Compartir niveles Escape Room")
            } else {
                Toast.makeText(activity, "No hay niveles guardados que exportar", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnImportLevels.setOnClickListener { levelImportPicker.launch("*/*") }
    }

    private fun showLevelEditor() {
        binding.homeScreen.visibility        = View.GONE
        binding.levelEditorScreen.visibility = View.VISIBLE
        showLevelList()
    }

    private fun showLevelList() {
        binding.levelListPanel.visibility    = View.VISIBLE
        binding.levelBuilderPanel.visibility = View.GONE
        refreshLevelList()
    }

    private fun refreshLevelList() {
        val levels = CustomLevelStorage.loadAll(activity)
        binding.tvLevelListEmpty.visibility = if (levels.isEmpty()) View.VISIBLE else View.GONE
        binding.levelListContainer.removeAllViews()
        levels.forEach { def ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val tvName = TextView(activity).apply {
                text    = def.name
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(4, 0, 4, 0)
            }
            val btnPlay = android.widget.Button(activity).apply {
                text = activity.getString(R.string.btn_play_custom)
                setOnClickListener { onPlayLevel(def) }
            }
            val btnEdit = android.widget.Button(activity).apply {
                text = "✏"
                setOnClickListener { openLevelBuilder(def) }
            }
            val btnDelete = android.widget.Button(activity).apply {
                text = activity.getString(R.string.btn_delete_level)
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Eliminar «${def.name}»")
                        .setMessage("¿Confirmar eliminación?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            CustomLevelStorage.delete(activity, def.id)
                            refreshLevelList()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            card.addView(tvName)
            card.addView(btnPlay)
            card.addView(btnEdit)
            card.addView(btnDelete)
            binding.levelListContainer.addView(card)
        }
    }

    private fun openLevelBuilder(def: EscapeRoomDef?) {
        pendingRoomConfigs.clear()
        editingLevelId             = def?.id
        pendingIntroVideoPath      = def?.introVideoPath
        pendingTransitionVideoPath = def?.transitionVideoPath
        binding.etLevelName.setText(def?.name ?: "")
        binding.btnIntroVideo.text      = if (def?.introVideoPath      != null) "🎬✓ Entrada"     else "🎬 Entrada"
        binding.btnTransitionVideo.text = if (def?.transitionVideoPath != null) "🎬✓ Transición"  else "🎬 Transición"
        if (def != null) {
            def.modules.forEach { m -> pendingRoomConfigs.add(moduleToConfig(m)) }
        }
        binding.levelListPanel.visibility    = View.GONE
        binding.levelBuilderPanel.visibility = View.VISIBLE
        refreshRoomCards()
    }

    private fun moduleToConfig(m: com.tfg.temieeg.game.RoomModule): RoomConfig = when (m) {
        is CalmModule        -> RoomConfig("CALM",         m.title, m.narration, m.hint,
            secondsRequired = m.secondsRequired, videoPath = m.videoPath, robotActions = m.robotActions)
        is MorseModule       -> RoomConfig("MORSE",        m.title, m.narration, m.hint,
            letterPool = m.letterPool.joinToString(""), videoPath = m.videoPath, robotActions = m.robotActions)
        is YesNoModule       -> RoomConfig("YESNO",        m.title, m.narration, m.hint,
            questions = m.questions, videoPath = m.videoPath, robotActions = m.robotActions)
        is BlinkClenchModule -> RoomConfig("BLINK_CLENCH", m.title, m.narration, m.hint,
            jawWindowMs = m.jawWindowMs, videoPath = m.videoPath, robotActions = m.robotActions)
        is RobotAnimModule   -> RoomConfig("ANIM",          m.title, m.narration, m.hint,
            durationMs = m.delayMs, videoPath = m.videoPath, robotActions = m.robotActions)
        is VideoStateModule  -> RoomConfig("VIDEO_STATE",  m.title, m.narration, m.hint,
            secondsRequired = m.secondsRequired, targetState = m.targetState?.name ?: "",
            videoPath = m.videoPath, robotActions = m.robotActions)
        else                 -> RoomConfig("CALM",         m.title, m.narration, m.hint)
    }

    private fun refreshRoomCards() {
        binding.roomListContainer.removeAllViews()
        pendingRoomConfigs.forEachIndexed { idx, cfg ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundResource(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 8)
                layoutParams = lp
            }
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // ── Reorder buttons ──────────────────────────────────────────────
            val btnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(2, 2, 2, 2)
            }

            val btnUp = android.widget.Button(activity).apply {
                text = activity.getString(R.string.btn_move_up)
                textSize = 14f
                setPadding(4, 0, 4, 0)
                isEnabled = idx > 0
                alpha = if (idx > 0) 1f else 0.3f
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener {
                    val tmp = pendingRoomConfigs[idx - 1]
                    pendingRoomConfigs[idx - 1] = pendingRoomConfigs[idx]
                    pendingRoomConfigs[idx] = tmp
                    refreshRoomCards()
                }
            }
            val btnDown = android.widget.Button(activity).apply {
                text = activity.getString(R.string.btn_move_down)
                textSize = 14f
                setPadding(4, 0, 4, 0)
                isEnabled = idx < pendingRoomConfigs.size - 1
                alpha = if (idx < pendingRoomConfigs.size - 1) 1f else 0.3f
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener {
                    val tmp = pendingRoomConfigs[idx + 1]
                    pendingRoomConfigs[idx + 1] = pendingRoomConfigs[idx]
                    pendingRoomConfigs[idx] = tmp
                    refreshRoomCards()
                }
            }

            val tvTitle = TextView(activity).apply {
                text = "${idx + 1}. [${cfg.type}] ${cfg.title}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setPadding(8, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnEdit = android.widget.Button(activity).apply {
                text = activity.getString(R.string.btn_edit_room)
                textSize = 14f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener { showRoomConfigDialog(cfg.type, editIndex = idx) }
            }
            val btnDuplicate = android.widget.Button(activity).apply {
                text = "📋"
                textSize = 14f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener {
                    pendingRoomConfigs.add(idx + 1, pendingRoomConfigs[idx].copy())
                    refreshRoomCards()
                }
            }
            val btnVid = android.widget.Button(activity).apply {
                text = if (cfg.videoPath != null) "🎬✓" else "🎬"
                textSize = 14f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener { pendingVideoSlot = idx; videoPicker.launch("video/*") }
            }
            val btnDel = android.widget.Button(activity).apply {
                text = "✕"
                textSize = 14f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(btnLp).also { it.weight = 0.7f }
                setOnClickListener { pendingRoomConfigs.removeAt(idx); refreshRoomCards() }
            }
            header.addView(btnUp)
            header.addView(btnDown)
            header.addView(tvTitle)
            header.addView(btnEdit)
            header.addView(btnDuplicate)
            header.addView(btnVid)
            header.addView(btnDel)
            card.addView(header)
            binding.roomListContainer.addView(card)
        }
    }

    private fun showRoomTypePicker() {
        val types = arrayOf(
            activity.getString(R.string.room_type_calm),
            activity.getString(R.string.room_type_morse),
            activity.getString(R.string.room_type_yesno),
            activity.getString(R.string.room_type_blink),
            activity.getString(R.string.room_type_video_state),
            activity.getString(R.string.room_type_anim)
        )
        val typeKeys = arrayOf("CALM", "MORSE", "YESNO", "BLINK_CLENCH", "VIDEO_STATE", "ANIM")
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_pick_room_type))
            .setItems(types) { _, which -> showRoomConfigDialog(typeKeys[which]) }
            .show()
    }

    private fun showRoomConfigDialog(type: String, editIndex: Int = -1) {
        val existing = if (editIndex >= 0) pendingRoomConfigs.getOrNull(editIndex) else null
        val scroll = android.widget.ScrollView(activity)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        scroll.addView(view)

        fun label(text: String) = view.addView(TextView(activity).apply {
            this.text = text; textSize = 11f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            setPadding(0, 12, 0, 2)
        })
        fun field(hint: String, default: String = ""): EditText {
            val et = EditText(activity).apply { this.hint = hint; setText(default) }
            view.addView(et); return et
        }

        val etTitle     = field(activity.getString(R.string.room_title_hint),     existing?.title     ?: "")
        val etNarration = field(activity.getString(R.string.room_narration_hint), existing?.narration ?: "")
        val etHint      = field(activity.getString(R.string.room_hint_hint),      existing?.hint      ?: "")

        // ── Campos numéricos / texto libre por tipo ───────────────────────────
        val etExtra: EditText? = when (type) {
            "CALM"         -> field(activity.getString(R.string.label_seconds_required),
                existing?.secondsRequired?.toString() ?: "5")
            "MORSE"        -> field(activity.getString(R.string.label_letter_pool),
                existing?.letterPool ?: "ETISAN")
            "BLINK_CLENCH" -> field(activity.getString(R.string.label_jaw_window),
                existing?.jawWindowMs?.toString() ?: "4000")
            "ANIM"         -> field(activity.getString(R.string.label_anim_delay),
                existing?.durationMs?.toString() ?: "3000")
            else           -> null  // YESNO y VIDEO_STATE usan controles visuales
        }

        // ── VIDEO_STATE: selector de estado + segundos ────────────────────────
        val stateNames = MentalState.entries.map { it.name }
        var targetStateSpinner: Spinner? = null
        val etSeconds: EditText?
        if (type == "VIDEO_STATE") {
            label("Estado objetivo")
            targetStateSpinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity,
                    android.R.layout.simple_spinner_item, stateNames).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                val sel = stateNames.indexOf(existing?.targetState ?: "CALM")
                if (sel >= 0) setSelection(sel)
            }
            view.addView(targetStateSpinner)
            etSeconds = field(activity.getString(R.string.label_seconds_required),
                existing?.secondsRequired?.toString() ?: "5")
        } else {
            etSeconds = null
        }

        // ── Constructor visual de preguntas Sí/No ─────────────────────────────
        val questionRows = mutableListOf<Pair<EditText, CheckBox>>()
        if (type == "YESNO") {
            label("Preguntas (✓ = respuesta SÍ)")
            val qContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            view.addView(qContainer)

            fun addQuestionRow(text: String = "", expectedYes: Boolean = true) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val etQ = EditText(activity).apply {
                    hint = "Pregunta"; setText(text)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val cb = CheckBox(activity).apply { this.text = "SÍ"; isChecked = expectedYes }
                val btnDel = android.widget.Button(activity).apply {
                    this.text = "✕"
                    setOnClickListener { qContainer.removeView(row); questionRows.removeAll { it.first === etQ } }
                }
                row.addView(etQ); row.addView(cb); row.addView(btnDel)
                qContainer.addView(row); questionRows.add(etQ to cb)
            }

            val initQs = existing?.questions?.takeIf { it.isNotEmpty() }
                ?: listOf(YesNoQuestion("", true))
            initQs.forEach { q -> addQuestionRow(q.text, q.expectedYes) }
            view.addView(android.widget.Button(activity).apply {
                text = "+ Añadir pregunta"
                setOnClickListener { addQuestionRow() }
            })
        }

        // ── Constructor visual de acciones del robot ──────────────────────────
        label("Acciones del robot")
        val locations = getLocations()
        if (locations.isNotEmpty()) {
            view.addView(TextView(activity).apply {
                text = "📍 ${locations.joinToString("  ·  ")}"
                textSize = 10f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setPadding(0, 0, 0, 4)
            })
        }

        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        view.addView(actionsContainer)

        val actionRows = mutableListOf<Pair<Spinner, EditText>>()
        val actionTypeNames = RobotAction.Type.entries.map { it.name }

        fun addActionRow(actionType: RobotAction.Type = RobotAction.Type.SPEAK, param: String = "") {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val sp = Spinner(activity).apply {
                adapter = ArrayAdapter(activity,
                    android.R.layout.simple_spinner_item, actionTypeNames).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(RobotAction.Type.entries.indexOf(actionType).coerceAtLeast(0))
            }
            val etParam = EditText(activity).apply {
                hint = "param"; setText(param)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnDel = android.widget.Button(activity).apply {
                this.text = "✕"
                setOnClickListener { actionsContainer.removeView(row); actionRows.removeAll { it.first === sp } }
            }
            row.addView(sp); row.addView(etParam); row.addView(btnDel)
            actionsContainer.addView(row); actionRows.add(sp to etParam)
        }

        val initActions = existing?.robotActions
            ?: if (type == "ANIM") listOf(RobotAction(RobotAction.Type.SPEAK, "Bienvenido")) else emptyList()
        initActions.forEach { a -> addActionRow(a.type, a.param) }

        view.addView(android.widget.Button(activity).apply {
            text = "+ Añadir acción"
            setOnClickListener { addActionRow() }
        })

        // ── Dialog ────────────────────────────────────────────────────────────
        val buttonLabel = if (editIndex >= 0) "Guardar" else "Añadir"
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_configure_room))
            .setView(scroll)
            .setPositiveButton(buttonLabel) { _, _ ->
                val title     = etTitle.text.toString().trim()
                    .ifEmpty { "Sala ${pendingRoomConfigs.size + 1}" }
                val narration = etNarration.text.toString().trim()
                val hint      = etHint.text.toString().trim()
                val extra     = etExtra?.text?.toString()?.trim() ?: ""
                val actions   = actionRows.map { (sp, etP) ->
                    RobotAction(RobotAction.Type.entries[sp.selectedItemPosition],
                        etP.text.toString().trim())
                }
                val cfg = when (type) {
                    "CALM"         -> RoomConfig("CALM", title, narration, hint,
                        secondsRequired = extra.toIntOrNull() ?: 5,
                        robotActions = actions, videoPath = existing?.videoPath)
                    "MORSE"        -> RoomConfig("MORSE", title, narration, hint,
                        letterPool = extra.uppercase().ifEmpty { "ETISAN" },
                        robotActions = actions, videoPath = existing?.videoPath)
                    "YESNO"        -> {
                        val qs = questionRows
                            .map { (etQ, cb) -> YesNoQuestion(etQ.text.toString().trim(), cb.isChecked) }
                            .filter { it.text.isNotEmpty() }
                        RoomConfig("YESNO", title, narration, hint, questions = qs,
                            robotActions = actions, videoPath = existing?.videoPath)
                    }
                    "BLINK_CLENCH" -> RoomConfig("BLINK_CLENCH", title, narration, hint,
                        jawWindowMs = extra.toLongOrNull() ?: 4000L,
                        robotActions = actions, videoPath = existing?.videoPath)
                    "VIDEO_STATE"  -> RoomConfig("VIDEO_STATE", title, narration, hint,
                        targetState  = targetStateSpinner?.selectedItem as? String ?: "CALM",
                        secondsRequired = etSeconds?.text?.toString()?.toIntOrNull() ?: 5,
                        robotActions = actions, videoPath = existing?.videoPath)
                    "ANIM"         -> RoomConfig("ANIM", title, narration, hint,
                        durationMs = extra.toLongOrNull() ?: 3000L,
                        robotActions = actions, videoPath = existing?.videoPath)
                    else           -> RoomConfig(type, title, narration, hint,
                        robotActions = actions, videoPath = existing?.videoPath)
                }
                if (editIndex >= 0) pendingRoomConfigs[editIndex] = cfg
                else                pendingRoomConfigs.add(cfg)
                refreshRoomCards()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLevelPreview() {
        if (pendingRoomConfigs.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.level_no_rooms), Toast.LENGTH_SHORT).show()
            return
        }
        val scroll = android.widget.ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 24)
        }
        scroll.addView(container)

        pendingRoomConfigs.forEachIndexed { idx, cfg ->
            val typeIcon = when (cfg.type) {
                "CALM"         -> "🧘"
                "MORSE"        -> "·—"
                "YESNO"        -> "✅❌"
                "BLINK_CLENCH" -> "👁😬"
                "VIDEO_STATE"  -> "🎬🧠"
                "ANIM"         -> "🤖"
                else           -> "?"
            }
            val typeLabel = when (cfg.type) {
                "CALM"         -> "Calma"
                "MORSE"        -> "Morse"
                "YESNO"        -> "Sí/No"
                "BLINK_CLENCH" -> "Parpadeo+Mandíbula"
                "VIDEO_STATE"  -> "Estado+Vídeo"
                "ANIM"         -> "Animación robot"
                else           -> cfg.type
            }

            val tvHeader = TextView(activity).apply {
                text = "${idx + 1}. $typeIcon  $typeLabel — \"${cfg.title}\""
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(activity, R.color.text_on_primary))
                setBackgroundColor(ContextCompat.getColor(activity, R.color.primary_dark))
                setPadding(16, 10, 16, 10)
            }

            val details = buildString {
                if (cfg.narration.isNotEmpty())
                    append("📢 ${cfg.narration.take(90)}${if (cfg.narration.length > 90) "…" else ""}\n")
                when (cfg.type) {
                    "CALM"         -> append("⏱ ${cfg.secondsRequired} s de calma\n")
                    "MORSE"        -> append("🔤 Letras: ${cfg.letterPool}\n")
                    "YESNO"        -> append("❓ ${cfg.questions.size} pregunta(s)\n")
                    "BLINK_CLENCH" -> append("⏱ Ventana mandíbula: ${cfg.jawWindowMs} ms\n")
                    "VIDEO_STATE"  -> {
                        append("🎯 Estado: ${cfg.targetState}  ⏱ ${cfg.secondsRequired} s\n")
                        if (cfg.videoPath != null) append("🎬 Vídeo concurrente ✓\n")
                        else append("⚠ Sin vídeo (funcionará sin él)\n")
                    }
                    "ANIM" -> append("⏱ Duración: ${cfg.durationMs} ms\n")
                }
                if (cfg.videoPath != null && cfg.type != "VIDEO_STATE") append("🎬 Vídeo introductorio ✓\n")
                if (cfg.robotActions.isNotEmpty()) {
                    append("🤖 Acciones:\n")
                    cfg.robotActions.forEach { a -> append("   • ${RobotAction.asString(a)}\n") }
                } else {
                    append("🤖 Sin acciones de robot\n")
                }
            }

            val tvDetails = TextView(activity).apply {
                text = details.trimEnd()
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_tint))
                setPadding(16, 8, 16, 10)
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(tvHeader, lp)
            container.addView(tvDetails, lp)
            container.addView(android.widget.Space(activity).apply { minimumHeight = 12 })
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.btn_preview_level))
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun saveCurrentLevel() {
        val name = binding.etLevelName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.level_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingRoomConfigs.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.level_no_rooms), Toast.LENGTH_SHORT).show()
            return
        }

        // ── Validación ────────────────────────────────────────────────────────
        val warnings = mutableListOf<String>()
        pendingRoomConfigs.forEachIndexed { idx, cfg ->
            val n = idx + 1
            if (cfg.narration.isBlank())
                warnings.add("• Sala $n (${cfg.type}): sin narración.")
            when (cfg.type) {
                "YESNO"       -> if (cfg.questions.isEmpty())
                    warnings.add("• Sala $n (Sí/No): no tiene preguntas — no se podrá superar.")
                "MORSE"       -> if (cfg.letterPool.isBlank())
                    warnings.add("• Sala $n (Morse): pool de letras vacío.")
                "VIDEO_STATE" -> if (cfg.videoPath == null)
                    warnings.add("• Sala $n (Estado+Vídeo): sin vídeo adjunto — el reto funcionará sin él.")
            }
        }
        if (warnings.isNotEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.validation_title))
                .setMessage(warnings.joinToString("\n"))
                .setPositiveButton(activity.getString(R.string.validation_save_anyway)) { _, _ -> doSaveLevel(name) }
                .setNegativeButton(activity.getString(R.string.validation_fix), null)
                .show()
            return
        }

        doSaveLevel(name)
    }

    private fun doSaveLevel(name: String) {
        val id = editingLevelId ?: "lvl_${System.currentTimeMillis()}"
        editingLevelId = id
        val modules = pendingRoomConfigs.map { cfg ->
            when (cfg.type) {
                "CALM"         -> CalmModule(cfg.title, cfg.narration, cfg.hint,
                    cfg.secondsRequired, videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                "MORSE"        -> MorseModule(cfg.title, cfg.narration, cfg.hint,
                    cfg.letterPool.toList(), videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                "YESNO"        -> YesNoModule(cfg.title, cfg.narration, cfg.hint,
                    cfg.questions, videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                "BLINK_CLENCH" -> BlinkClenchModule(cfg.title, cfg.narration, cfg.hint,
                    cfg.jawWindowMs, videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                "ANIM"         -> RobotAnimModule(cfg.title, cfg.narration, cfg.hint,
                    delayMs = cfg.durationMs,
                    videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                "VIDEO_STATE"  -> VideoStateModule(cfg.title, cfg.narration, cfg.hint,
                    targetState = runCatching {
                        com.tfg.temieeg.data.MentalState.valueOf(cfg.targetState)
                    }.getOrDefault(com.tfg.temieeg.data.MentalState.CALM),
                    secondsRequired = cfg.secondsRequired,
                    videoPath = cfg.videoPath, robotActions = cfg.robotActions)
                else           -> CalmModule(cfg.title, cfg.narration, cfg.hint)
            }
        }
        CustomLevelStorage.save(activity, EscapeRoomDef(id, name, modules,
            introVideoPath      = pendingIntroVideoPath,
            transitionVideoPath = pendingTransitionVideoPath))
        Toast.makeText(activity, activity.getString(R.string.level_saved_ok), Toast.LENGTH_SHORT).show()
        showLevelList()
    }

    private fun shareFile(file: java.io.File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(android.content.Intent.createChooser(intent, title))
    }
}
