package com.tfg.temieeg.game

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tfg.temieeg.data.MentalState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Gestiona la persistencia de niveles personalizados en almacenamiento interno.
 *
 * Formato JSON (custom_levels.json):
 *   [ { "id":"...", "name":"...", "modules":[...] }, ... ]
 *
 * Los vídeos se copian a filesDir/level_videos/<levelId>_<roomIndex>.mp4
 * y se referencian por ruta absoluta en el campo "videoPath".
 */
object CustomLevelStorage {

    private const val FILE_NAME   = "custom_levels.json"
    private const val VIDEO_DIR   = "level_videos"
    private const val IMAGE_DIR   = "level_images"

    // ── Cargar ────────────────────────────────────────────────────────────────

    fun loadAll(context: Context): List<EscapeRoomDef> {
        val file = storageFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { defFromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Guardar ───────────────────────────────────────────────────────────────

    fun saveAll(context: Context, levels: List<EscapeRoomDef>) {
        val array = JSONArray()
        levels.forEach { array.put(defToJson(it)) }
        storageFile(context).writeText(array.toString(2))
    }

    fun save(context: Context, def: EscapeRoomDef) {
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == def.id }
        if (idx >= 0) all[idx] = def else all.add(def)
        saveAll(context, all)
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context).filter { it.id != id }
        saveAll(context, all)
        // Borrar vídeos e imágenes asociados
        videoDir(context).listFiles()
            ?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        imageDir(context).listFiles()
            ?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    /**
     * Exporta todos los niveles + sus vídeos en un ZIP.
     * Estructura del ZIP:
     *   levels.json         → JSON con videoPath como nombre de fichero relativo
     *   videos/<fichero>.mp4 → cada vídeo adjunto
     *
     * Devuelve el [File] ZIP creado, o null si no hay niveles / hay error.
     */
    fun exportZip(context: Context): File? {
        val levels = loadAll(context)
        if (levels.isEmpty()) return null
        val dst = File(context.getExternalFilesDir(null), "escape_rooms_export.zip")
        return try {
            ZipOutputStream(dst.outputStream().buffered()).use { zip ->
                // levels.json con rutas relativas
                zip.putNextEntry(ZipEntry("levels.json"))
                zip.write(buildJsonWithRelativePaths(levels).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // vídeos e imágenes (módulos + nivel)
                levels.forEach levelLoop@{ def ->
                    // Vídeos de nivel (intro / transición)
                    listOfNotNull(def.introVideoPath, def.transitionVideoPath).forEach { path ->
                        val f = File(path)
                        if (f.exists()) {
                            zip.putNextEntry(ZipEntry("videos/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    def.modules.forEach { module ->
                        val vidPath = module.videoPath
                        if (vidPath != null) {
                            val f = File(vidPath)
                            if (f.exists()) {
                                zip.putNextEntry(ZipEntry("videos/${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        val imgPath = module.hintImagePath
                        if (imgPath != null) {
                            val f = File(imgPath)
                            if (f.exists()) {
                                zip.putNextEntry(ZipEntry("images/${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "ZIP exportado → ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.e(TAG, "Error creando ZIP", e)
            dst.delete()
            null
        }
    }

    /**
     * Importa niveles desde un URI (ZIP o JSON plano).
     * - ZIP: extrae vídeos a filesDir/level_videos/ y reconstruye las rutas absolutas.
     * - JSON: importa estructura sin vídeos (videoPath queda vacío).
     * Devuelve el número de niveles importados, o -1 si el fichero no es válido.
     */
    fun importFromUri(context: Context, uri: Uri, replace: Boolean = true): Int =
        if (isZipUri(context, uri)) importFromZip(context, uri, replace)
        else                        importFromJson(context, uri, replace)

    private fun isZipUri(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(4).also { stream.read(it) }
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() // PK magic
        } ?: false
    } catch (_: Exception) { false }

    private fun importFromZip(context: Context, uri: Uri, replace: Boolean): Int {
        val vDir = videoDir(context).also { it.mkdirs() }
        return try {
            var jsonText: String? = null
            val savedVideos = mutableSetOf<String>()
            val savedImages = mutableSetOf<String>()
            val iDir = imageDir(context).also { it.mkdirs() }
            ZipInputStream(context.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "levels.json" ->
                            jsonText = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith("videos/") && !entry.isDirectory -> {
                            val name = entry.name.removePrefix("videos/")
                            File(vDir, name).outputStream().use { zip.copyTo(it) }
                            savedVideos.add(name)
                        }
                        entry.name.startsWith("images/") && !entry.isDirectory -> {
                            val name = entry.name.removePrefix("images/")
                            File(iDir, name).outputStream().use { zip.copyTo(it) }
                            savedImages.add(name)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (jsonText == null) return -1
            // Reconstruir rutas absolutas en el JSON
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val def = array.getJSONObject(i)
                // Vídeos de nivel
                listOf("introVideoPath", "transitionVideoPath").forEach { key ->
                    val rel = def.optString(key)
                    def.put(key, if (rel.isNotEmpty() && rel in savedVideos) File(vDir, rel).absolutePath else "")
                }
                val modules = def.optJSONArray("modules") ?: continue
                for (j in 0 until modules.length()) {
                    val mod = modules.getJSONObject(j)
                    val relVideo = mod.optString("videoPath")
                    mod.put("videoPath",
                        if (relVideo.isNotEmpty() && relVideo in savedVideos)
                            File(vDir, relVideo).absolutePath
                        else "")
                    val relImg = mod.optString("hintImagePath")
                    mod.put("hintImagePath",
                        if (relImg.isNotEmpty() && relImg in savedImages)
                            File(iDir, relImg).absolutePath
                        else "")
                }
            }
            val imported = (0 until array.length()).mapNotNull { defFromJson(array.getJSONObject(it)) }
            if (imported.isEmpty()) return 0
            val existing = if (replace) emptyList() else loadAll(context)
            val merged = existing.filter { e -> imported.none { it.id == e.id } } + imported
            saveAll(context, merged)
            Log.i(TAG, "ZIP importado: ${imported.size} niveles, ${savedVideos.size} vídeos")
            imported.size
        } catch (e: Exception) {
            Log.e(TAG, "Error importando ZIP", e)
            -1
        }
    }

    private fun importFromJson(context: Context, uri: Uri, replace: Boolean): Int {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return -1
            val array = JSONArray(text)
            // Sin vídeos (rutas internas inválidas en otro dispositivo)
            for (i in 0 until array.length()) {
                val modules = array.getJSONObject(i).optJSONArray("modules") ?: continue
                for (j in 0 until modules.length()) {
                    val mod = modules.getJSONObject(j)
                    mod.put("videoPath", "")
                    mod.put("hintImagePath", "")
                }
            }
            val imported = (0 until array.length()).mapNotNull { defFromJson(array.getJSONObject(it)) }
            if (imported.isEmpty()) return 0
            val existing = if (replace) emptyList() else loadAll(context)
            val merged = existing.filter { e -> imported.none { it.id == e.id } } + imported
            saveAll(context, merged)
            Log.i(TAG, "JSON importado: ${imported.size} niveles (sin vídeos)")
            imported.size
        } catch (e: Exception) {
            Log.e(TAG, "Error importando JSON", e)
            -1
        }
    }

    private fun buildJsonWithRelativePaths(levels: List<EscapeRoomDef>): String {
        val array = JSONArray()
        levels.forEach { def ->
            val obj = defToJson(def)
            // Vídeos de nivel → nombre relativo
            listOf("introVideoPath", "transitionVideoPath").forEach { key ->
                val full = obj.optString(key)
                if (full.isNotEmpty()) obj.put(key, File(full).name)
            }
            val modules = obj.getJSONArray("modules")
            for (i in 0 until modules.length()) {
                val mod = modules.getJSONObject(i)
                val vidFull = mod.optString("videoPath")
                if (vidFull.isNotEmpty()) mod.put("videoPath", File(vidFull).name)
                val imgFull = mod.optString("hintImagePath")
                if (imgFull.isNotEmpty()) mod.put("hintImagePath", File(imgFull).name)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    // ── Vídeo ─────────────────────────────────────────────────────────────────

    /** Copia el vídeo seleccionado por el usuario a almacenamiento interno y devuelve la ruta. */
    fun copyVideo(context: Context, levelId: String, roomIndex: Int, uri: Uri): String? =
        copyNamedVideo(context, levelId, roomIndex.toString(), uri)

    /**
     * Copia un vídeo con un nombre de slot libre (p.ej. "intro", "transition", "0", "1").
     * Nombre de fichero resultante: [levelId]_[slot].mp4
     */
    fun copyNamedVideo(context: Context, levelId: String, slot: String, uri: Uri): String? {
        return try {
            val dir  = videoDir(context).also { it.mkdirs() }
            val dest = File(dir, "${levelId}_${slot}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Copia la imagen de ayuda seleccionada a almacenamiento interno y devuelve la ruta. */
    fun copyImage(context: Context, levelId: String, roomIndex: Int, uri: Uri): String? {
        return try {
            val ext  = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "jpg"
            val dir  = imageDir(context).also { it.mkdirs() }
            val dest = File(dir, "${levelId}_${roomIndex}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // ── Serialización ─────────────────────────────────────────────────────────

    private fun defToJson(def: EscapeRoomDef): JSONObject = JSONObject().apply {
        put("id",      def.id)
        put("name",    def.name)
        put("modules", JSONArray().also { arr -> def.modules.forEach { arr.put(moduleToJson(it)) } })
        if (!def.introVideoPath.isNullOrEmpty())      put("introVideoPath",      def.introVideoPath)
        if (!def.transitionVideoPath.isNullOrEmpty()) put("transitionVideoPath", def.transitionVideoPath)
    }

    private fun moduleToJson(m: RoomModule): JSONObject = JSONObject().apply {
        put("type",          moduleType(m))
        put("title",         m.title)
        put("narration",     m.narration)
        put("hint",          m.hint)
        put("videoPath",     m.videoPath ?: "")
        put("hintImagePath", m.hintImagePath ?: "")
        put("robotActions", JSONArray().also { arr ->
            m.robotActions.forEach { arr.put(RobotAction.asString(it)) }
        })
        when (m) {
            is CalmModule        -> put("secondsRequired", m.secondsRequired)
            is MorseModule       -> put("letterPool", m.letterPool.joinToString(""))
            is YesNoModule       -> put("questions", JSONArray().also { arr ->
                m.questions.forEach { q ->
                    arr.put(JSONObject().apply {
                        put("text", q.text)
                        put("expectedYes", q.expectedYes)
                        if (q.gotoOnYes != null) put("gotoOnYes", q.gotoOnYes)
                        if (q.gotoOnNo  != null) put("gotoOnNo",  q.gotoOnNo)
                    })
                }
            })
            is BlinkClenchModule -> put("jawWindowMs", m.jawWindowMs)
            is RobotAnimModule   -> put("delayMs", m.delayMs)
            is VideoStateModule  -> {
                put("targetState", m.targetState?.name ?: "")
                put("secondsRequired", m.secondsRequired)
            }
        }
    }

    private fun defFromJson(obj: JSONObject): EscapeRoomDef? = try {
        val id      = obj.getString("id")
        val name    = obj.getString("name")
        val modules = obj.getJSONArray("modules")
        val list    = (0 until modules.length()).mapNotNull { moduleFromJson(modules.getJSONObject(it)) }
        val intro      = obj.optString("introVideoPath").takeIf      { it.isNotEmpty() }
        val transition = obj.optString("transitionVideoPath").takeIf { it.isNotEmpty() }
        EscapeRoomDef(id, name, list, introVideoPath = intro, transitionVideoPath = transition)
    } catch (_: Exception) { null }

    private fun moduleFromJson(obj: JSONObject): RoomModule? = try {
        val type      = obj.getString("type")
        val title     = obj.getString("title")
        val narration = obj.getString("narration")
        val hint      = obj.getString("hint")
        val vidPath   = obj.optString("videoPath").takeIf { it.isNotEmpty() }
        val imgPath   = obj.optString("hintImagePath").takeIf { it.isNotEmpty() }
        val actionsArr = obj.optJSONArray("robotActions")
        val robotActions = if (actionsArr != null) {
            (0 until actionsArr.length()).mapNotNull { RobotAction.fromString(actionsArr.getString(it)) }
        } else emptyList()
        when (type) {
            "CALM" -> CalmModule(
                title           = title,
                narration       = narration,
                hint            = hint,
                secondsRequired = obj.optInt("secondsRequired", 5),
                videoPath       = vidPath,
                robotActions    = robotActions
            )
            "MORSE" -> MorseModule(
                title         = title,
                narration     = narration,
                hint          = hint,
                letterPool    = obj.optString("letterPool", "ETISAN").toList(),
                videoPath     = vidPath,
                robotActions  = robotActions,
                hintImagePath = imgPath
            )
            "YESNO" -> {
                val arr       = obj.getJSONArray("questions")
                val questions = (0 until arr.length()).map {
                    val q = arr.getJSONObject(it)
                    YesNoQuestion(
                        text        = q.getString("text"),
                        expectedYes = q.optBoolean("expectedYes", true),
                        gotoOnYes   = if (q.has("gotoOnYes")) q.getInt("gotoOnYes") else null,
                        gotoOnNo    = if (q.has("gotoOnNo"))  q.getInt("gotoOnNo")  else null
                    )
                }
                YesNoModule(title = title, narration = narration, hint = hint,
                    questions = questions, videoPath = vidPath, robotActions = robotActions)
            }
            "BLINK_CLENCH" -> BlinkClenchModule(
                title        = title,
                narration    = narration,
                hint         = hint,
                jawWindowMs  = obj.optLong("jawWindowMs", 4000L),
                videoPath    = vidPath,
                robotActions = robotActions
            )
            "ANIM" -> RobotAnimModule(
                title        = title,
                narration    = narration,
                hint         = hint,
                delayMs      = obj.optLong("delayMs", 3000L),
                videoPath    = vidPath,
                robotActions = robotActions
            )
            "VIDEO_STATE" -> VideoStateModule(
                title           = title,
                narration       = narration,
                hint            = hint,
                targetState     = obj.optString("targetState", "").takeIf { it.isNotEmpty() }
                                    ?.let { runCatching { MentalState.valueOf(it) }.getOrNull() },
                secondsRequired = obj.optInt("secondsRequired", 5),
                videoPath       = vidPath,
                robotActions    = robotActions
            )
            else -> null
        }
    } catch (_: Exception) { null }

    private fun moduleType(m: RoomModule) = when (m) {
        is CalmModule        -> "CALM"
        is MorseModule       -> "MORSE"
        is YesNoModule       -> "YESNO"
        is BlinkClenchModule -> "BLINK_CLENCH"
        is RobotAnimModule   -> "ANIM"
        is VideoStateModule  -> "VIDEO_STATE"
        else                 -> "UNKNOWN"
    }

    // ── Paths ─────────────────────────────────────────────────────────────────

    private fun storageFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun videoDir(context: Context)    = File(context.filesDir, VIDEO_DIR)
    private fun imageDir(context: Context)    = File(context.filesDir, IMAGE_DIR)

    private const val TAG = "CustomLevelStorage"
}
