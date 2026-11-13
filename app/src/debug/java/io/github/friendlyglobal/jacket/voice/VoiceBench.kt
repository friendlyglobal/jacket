package io.github.friendlyglobal.jacket.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Debug builds only: how much processor the natural voices take to make the same sentences, by
 * how many threads they run on and whether those threads spin or sleep while they wait (see
 * [EngineSettings]). Each setting is loaded afresh, warmed up on a sentence that isn't counted,
 * then timed on English and Brazilian Portuguese, a few rounds in turn. The sound can be kept as
 * WAV files, to hear and to compare one setting against another: Supertonic starts each
 * sentence from random noise, so here each sentence has a fixed seed, and every setting starts
 * it from the same noise.
 *
 *     adb shell am start -n app.usefriendly.jacket/io.github.friendlyglobal.jacket.voice.VoiceBench \
 *         --es configs 1q,2q,4q,2s,4s --ei rounds 2 --es wavs 4s,2q
 *     adb logcat -s JacketBench
 *     adb pull /sdcard/Android/data/app.usefriendly.jacket/files/bench
 *
 * A setting is threads and "s" (spinning, as ONNX Runtime does by itself) or "q" (sleeping, as
 * Jacket has it). The processor time is the whole app's, of which making speech is nearly all
 * while this runs; leave the phone alone meanwhile.
 */
// (A tool for developers, in debug builds only: what it shows is for them, in English.)
@SuppressLint("SetTextI18n")
class VoiceBench : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(48, 48, 48, 48); text = "Measuring the voices…\n" }
        setContentView(out)
        val configs = (intent.getStringExtra("configs") ?: "1q,2q,3q,4q,1s,2s,3s,4s").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val rounds = intent.getIntExtra("rounds", 2).coerceAtLeast(1)
        val wavs = intent.getStringExtra("wavs").orEmpty().split(',').map { it.trim() }.toSet()
        val only = intent.getStringExtra("texts")?.split(',')?.map { it.trim() }?.toSet()
        val speed = intent.getFloatExtra("speed", 1.25f)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2 * Process.THREAD_PRIORITY_LESS_FAVORABLE)
            runCatching { run(configs, rounds, wavs, only, speed) }.onFailure { say("Failed: $it"); Log.e(TAG, "Failed", it) }
        }, "jacket-voice").start()
    }

    private class Text(val name: String, val pack: String, val language: String, val speaker: Int, val sentences: List<String>)

    private class Tally {
        var speechMs = 0L
        var wallMs = 0L
        var cpuMs = 0L
    }

    private fun run(configs: List<String>, rounds: Int, wavs: Set<String>, only: Set<String>?, speed: Float) {
        val packs = VoiceCatalog.parse(assets.open("voices/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) })
            .filter { pack -> assets.list(pack.folder).orEmpty().toSet().containsAll(pack.files.values) }
            .associateBy { it.id }
        val texts = TEXTS.filter { (only == null || it.name in only) && it.pack in packs }
        val folder = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val tallies = LinkedHashMap<String, Tally>()
        say("Cores: ${Runtime.getRuntime().availableProcessors()}, speed $speed, rounds $rounds")
        for (round in 1..rounds) {
            for (config in configs) {
                val threads = config.dropLast(1).toInt()
                val spinning = config.last() == 's'
                for ((pack, group) in texts.groupBy { it.pack }) {
                    val model = NaturalModel(this, packs.getValue(pack), threads, spinning)
                    try {
                        // Warmed up: the first sentence after loading pays for setting things up.
                        model.synthesize(WARM_UP, group.first().speaker, group.first().language, speed, QualityGuard.FULL_STEPS) { false }
                        for (text in group) {
                            val tally = tallies.getOrPut("${text.name} $config") { Tally() }
                            val sound = ArrayList<FloatArray>()
                            for ((i, sentence) in text.sentences.withIndex()) {
                                val cpu = Process.getElapsedCpuTime()
                                val wall = SystemClock.elapsedRealtime()
                                // (Each sentence its own fixed seed: the same sound on every setting, to compare.)
                                val samples = model.synthesize(sentence, text.speaker, text.language, speed, QualityGuard.FULL_STEPS, seed = SEED + i) { false }
                                    ?: FloatArray(0)
                                tally.wallMs += SystemClock.elapsedRealtime() - wall
                                tally.cpuMs += Process.getElapsedCpuTime() - cpu
                                tally.speechMs += samples.size * 1000L / model.sampleRate
                                sound += samples
                            }
                            if (round == 1 && config in wavs) writeWav(File(folder, "${text.name}-$config.wav"), sound, model.sampleRate)
                        }
                    } finally {
                        model.release()
                    }
                }
                say("Round $round, $config done")
            }
        }
        val report = buildString {
            appendLine("text config speech_s wall_s pace cpu_s cpu_s_per_speech_min")
            for ((key, t) in tallies) {
                val speech = t.speechMs / 1000f
                appendLine(
                    "$key ${"%.1f".format(speech)} ${"%.2f".format(t.wallMs / 1000f)} ${"%.3f".format(t.wallMs / t.speechMs.toFloat())} " +
                        "${"%.2f".format(t.cpuMs / 1000f)} ${"%.2f".format(t.cpuMs / 1000f / (speech / 60f))}",
                )
            }
        }
        File(folder, "results.txt").writeText(report)
        report.lines().forEach { if (it.isNotBlank()) Log.i(TAG, it) }
        say(report)
        Log.i(TAG, "Done")
    }

    /** The model's own sound, unchanged, as 16-bit samples: a sentence after another, a third of a second apart. */
    private fun writeWav(file: File, sentences: List<FloatArray>, rate: Int) {
        val gap = rate / 3
        val frames = sentences.sumOf { it.size } + gap * sentences.size
        val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in sentences) {
            for (x in s) pcm.putShort((x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            repeat(gap) { pcm.putShort(0) }
        }
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            fun int(v: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun short(v: Int) = o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            o.writeBytes("RIFF"); int(36 + frames * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            o.writeBytes("data"); int(frames * 2); o.write(pcm.array())
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    private companion object {
        const val TAG = "JacketBench"
        const val WARM_UP = "This sentence warms the voice up."
        const val SEED = 1818

        // Public-domain books: the opening of Frankenstein (Mary Shelley, 1818) and of Dom
        // Casmurro (Machado de Assis, 1899).
        val ENGLISH = listOf(
            "You will rejoice to hear that no disaster has accompanied the commencement of an enterprise which you have regarded with such evil forebodings.",
            "I arrived here yesterday, and my first task is to assure my dear sister of my welfare and increasing confidence in the success of my undertaking.",
            "I am already far north of London, and as I walk in the streets of Petersburgh, I feel a cold northern breeze play upon my cheeks, which braces my nerves and fills me with delight.",
            "Do you understand this feeling?",
            "This breeze, which has travelled from the regions towards which I am advancing, gives me a foretaste of those icy climes.",
        )
        val BRAZILIAN = listOf(
            "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu.",
            "Cumprimentou-me, sentou-se ao pé de mim, falou da lua e dos ministros, e acabou recitando-me versos.",
            "A viagem era curta, e os versos pode ser que não fossem inteiramente maus.",
            "Sucedeu, porém, que, como eu estava cansado, fechei os olhos três ou quatro vezes; tanto bastou para que ele interrompesse a leitura e metesse os versos no bolso.",
        )

        /** Supertonic's Sofia (the voice Jacket starts people on) in English and Portuguese, and the Brazilian voice. */
        val TEXTS = listOf(
            Text("en-supertonic", "supertonic3", "en", 1, ENGLISH),
            Text("pt-supertonic", "supertonic3", "pt", 1, BRAZILIAN),
            Text("pt_BR-cadu", "cadu", "pt", 0, BRAZILIAN),
        )
    }
}
