package dev.egarcia.harness.voice

/** A Whisper model the user can download after enabling Local Whisper. Models are never part of the APK. */
data class WhisperModel(
    val id: String,
    val label: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    /** English-only models are smaller and more accurate for English; multilingual ones take a language setting. */
    val englishOnly: Boolean,
    val note: String,
) {
    val megabytes: Int get() = ((bytes + 500_000) / 1_000_000).toInt()
}

/**
 * ggml quantised (q5_1) Whisper models from the whisper.cpp model repository, pinned to one revision and verified by
 * SHA-256 after download, so a changed or tampered file is rejected.
 */
object WhisperModels {
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION"

    val TINY_EN = WhisperModel("tiny.en", "Tiny, English", "ggml-tiny.en-q5_1.bin", 32_166_155,
        "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", true, "Fastest, fair accuracy")
    val BASE_EN = WhisperModel("base.en", "Base, English", "ggml-base.en-q5_1.bin", 59_721_011,
        "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", true, "Recommended: good accuracy, quick")
    val BASE = WhisperModel("base", "Base, multilingual", "ggml-base-q5_1.bin", 59_707_625,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", false, "English, Spanish and about 100 more languages")
    val SMALL = WhisperModel("small", "Small, multilingual", "ggml-small-q5_1.bin", 190_085_487,
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", false, "Most accurate, slower and larger")

    val all = listOf(TINY_EN, BASE_EN, BASE, SMALL)
    val DEFAULT = BASE_EN

    fun byId(id: String?): WhisperModel = all.firstOrNull { it.id == id } ?: DEFAULT

    fun url(model: WhisperModel, base: String = BASE_URL) = "$base/${model.file}"
}

/** Speech languages offered for multilingual models; English-only models always use `en`. */
object VoiceLanguages {
    val choices = listOf("auto" to "Detect automatically", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese")

    fun effective(model: WhisperModel, chosen: String): String =
        if (model.englishOnly) "en" else if (choices.any { it.first == chosen }) chosen else "auto"
}
