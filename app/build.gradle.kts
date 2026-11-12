import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (kept out of git; see RELEASING.md). Without it,
// release builds are signed with the debug key so test builds keep installing over each other.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "io.github.friendlyglobal.jacket"
    compileSdk = 37

    defaultConfig {
        // The store identity (usefriendly.app); the code keeps its original package name.
        applicationId = "app.usefriendly.jacket"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.4.0"

        // The voice engine is native code. 32-bit x86 is left out: no phone that runs Android 8+
        // needs it. The APK carries the engine for all three (about 9 MB each): beside the
        // voices' 145 MB, an APK per processor would save a tenth and cost people a choice, and
        // AGP can't build split APKs and the Play bundle in one go. Play splits the bundle itself.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimizes the release build (about half the size).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium needs newer Java library APIs than minSdk 26 ships; desugaring backports them.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // The engine's native code for three kinds of processor is 68 MB as it stands and 27 MB
        // compressed. Compressed in the APK, Android unpacks the one the phone needs when it
        // installs: a 40 MB smaller download for everyone.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // Lists the app's languages for Android 13+'s per-app language setting.
        generateLocaleConfig = true
        // The natural voices' models are read straight out of the installed app, which only
        // works without copying if they're stored as they are. (They hardly compress anyway.)
        noCompress += listOf("onnx", "bin")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.window.core.ExperimentalWindowApi",
            "org.readium.r2.shared.ExperimentalReadiumApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.window)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.palette)
    implementation(libs.webkit)
    implementation(libs.graphics.shapes)
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.fragment.compose)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.tts)
    implementation(libs.jsoup)

    implementation(libs.media3.session)
    // Natural voices: the engine. The voices themselves are fetched below.
    implementation(variantOf(libs.sherpa.onnx) { artifactType("aar") })
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kxml2)
}

// ---------------------------------------------------------------------------------------------
// Natural voices. The engine (sherpa-onnx) and the voices (Supertonic 3, and a Brazilian one) are too big for git and
// aren't on Maven Central: both come from sherpa-onnx's GitHub releases, and both are checked
// against a pinned SHA-256 before anything is built with them. Change a version and its checksum
// together, after reviewing the new release.
// ---------------------------------------------------------------------------------------------

val sherpaOnnxSha256 = "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
val sherpaOnnxAar = configurations.create("sherpaOnnxAar") {
    isCanBeConsumed = false
}
dependencies {
    "sherpaOnnxAar"(variantOf(libs.sherpa.onnx) { artifactType("aar") })
}
val verifySherpaOnnx = tasks.register("verifySherpaOnnx") {
    val files = sherpaOnnxAar.incoming.files
    val expected = sherpaOnnxSha256
    inputs.files(files)
    inputs.property("sha256", expected)
    val stamp = layout.buildDirectory.file("verified/sherpa-onnx.sha256")
    outputs.file(stamp)
    doLast {
        val aar = files.single()
        val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "sherpa-onnx (${aar.name}) has SHA-256 $actual, expected $expected. " +
                "If you changed its version on purpose, review the new release and update sherpaOnnxSha256."
        }
        stamp.get().asFile.writeText(actual)
    }
}
tasks.named("preBuild") { dependsOn(verifySherpaOnnx) }

/**
 * Downloads the voices (once; the archive is kept in .gradle/voices), checks them, and unpacks the
 * model's files as app assets under voices/<id>/. To build offline, put the archive there yourself.
 */
abstract class FetchVoices : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val pack: Property<String>
    @get:Input abstract val size: Property<String>
    @get:Input abstract val files: ListProperty<String>

    /** Model files whose weights are stored at half size (see [halveWeights]). */
    @get:Input abstract val halved: ListProperty<String>
    @get:Internal abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val assets: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun fetch() {
        val file = archive.get().asFile
        if (!file.exists() || digest(file) != sha256.get()) {
            logger.lifecycle("Downloading a natural voice (${size.get()}, once): ${url.get()}")
            file.parentFile.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            check(digest(partial) == sha256.get()) {
                "The voices archive has SHA-256 ${digest(partial)}, expected ${sha256.get()}. " +
                    "If you changed it on purpose, review the new files and update the checksum."
            }
            check(partial.renameTo(file) || run { file.delete(); partial.renameTo(file) }) { "Couldn't store ${file.name}" }
        }
        val wanted = files.get()
        val folder = "voices/${pack.get()}"
        fileSystem.sync {
            from(archives.tarTree(archives.bzip2(file))) {
                include(wanted.map { "*/$it" })
                eachFile { path = "$folder/$name" }
                includeEmptyDirs = false
            }
            into(assets)
        }
        val missing = wanted.filterNot { assets.get().file("$folder/$it").asFile.isFile }
        check(missing.isEmpty()) { "The voices archive has no ${missing.joinToString()}" }
        for (name in halved.get()) {
            val model = assets.get().file("$folder/$name").asFile
            val before = model.length()
            model.writeBytes(halveWeights(model.readBytes()))
            logger.lifecycle("Stored the weights of $name at half size: ${before / 1_000_000} MB became ${model.length() / 1_000_000} MB")
        }
    }

    // ---- A model at half the size, with nothing lost that can be heard ----
    //
    // A voice model is mostly numbers: its weights, each a 32-bit float. The ready-made 8-bit
    // version of the Brazilian voice is small, but the rounding hisses. Kept as 16-bit floats the
    // weights lose nothing audible and take half the room; a "Cast" step put in front of each
    // widens it again as the model loads, so the engine runs exactly the full-size model.
    //
    // The file is an ONNX model, which is a protobuf: fields within fields, each with a number.
    // Only the few this needs are read: the model's graph (7), the graph's steps (1) and stored
    // tensors (5), and a tensor's kind (2), name (8) and raw bytes (9).

    /** One field of a protobuf message: its number, its kind, and where it lies in the file. */
    private class Field(val number: Int, val kind: Int, val tag: Int, val start: Int, val end: Int)

    private fun fields(data: ByteArray, from: Int, to: Int): List<Field> {
        val out = ArrayList<Field>()
        var at = from
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = data[at++].toInt()
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
        while (at < to) {
            val tag = at
            val key = varint().toInt()
            val start: Int
            when (key and 7) {
                0 -> { start = at; varint() }
                1 -> { start = at; at += 8 }
                2 -> { val length = varint().toInt(); start = at; at += length }
                5 -> { start = at; at += 4 }
                else -> error("This isn't a model file the build knows how to read")
            }
            out += Field(key ushr 3, key and 7, tag, start, at)
        }
        check(at == to) { "The model file is damaged" }
        return out
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.number(field: Int, value: Long) {
        varint((field shl 3).toLong())
        varint(value)
    }

    private fun ByteArrayOutputStream.bytes(field: Int, value: ByteArray) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        write(value)
    }

    private fun ByteArrayOutputStream.text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    private fun halveWeights(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 65_536)
        for (f in fields(data, 0, data.size)) {
            if (f.number == 7 && f.kind == 2) out.bytes(7, halveGraph(data, f)) else out.write(data, f.tag, f.end - f.tag)
        }
        return out.toByteArray()
    }

    private fun halveGraph(data: ByteArray, graph: Field): ByteArray {
        val widening = ByteArrayOutputStream()
        val rest = ByteArrayOutputStream((graph.end - graph.start) / 2 + 65_536)
        var count = 0
        for (f in fields(data, graph.start, graph.end)) {
            val halved = if (f.number == 5 && f.kind == 2) halveTensor(data, f) else null
            if (halved == null) {
                rest.write(data, f.tag, f.end - f.tag)
                continue
            }
            val (name, tensor) = halved
            rest.bytes(5, tensor)
            // A step that widens it back to 32 bits under the name the rest of the model knows.
            val to = ByteArrayOutputStream().apply { text(1, "to"); number(3, FLOAT32.toLong()); number(20, 2) }
            val cast = ByteArrayOutputStream().apply {
                text(1, name + HALF)
                text(2, name)
                text(3, "widen/$name")
                text(4, "Cast")
                bytes(5, to.toByteArray())
            }
            widening.bytes(1, cast.toByteArray())
            count++
        }
        check(count > 0) { "The model has no weights to store at half size: has its format changed?" }
        // The widening steps first: a model's steps are kept in the order they run.
        return widening.toByteArray() + rest.toByteArray()
    }

    /** [tensor] with its 32-bit floats as 16-bit ones, and its name; null for one that isn't worth it or isn't floats. */
    private fun halveTensor(data: ByteArray, tensor: Field): Pair<String, ByteArray>? {
        val parts = fields(data, tensor.start, tensor.end)
        val kind = parts.firstOrNull { it.number == 2 && it.kind == 0 }?.let { data[it.start].toInt() }
        val raw = parts.singleOrNull { it.number == 9 && it.kind == 2 }
        val named = parts.singleOrNull { it.number == 8 && it.kind == 2 }
        val elsewhere = parts.any { it.number == 13 || it.number == 14 }
        if (kind != FLOAT32 || raw == null || named == null || elsewhere || raw.end - raw.start < 4096 || (raw.end - raw.start) % 4 != 0) return null
        val name = String(data, named.start, named.end - named.start, Charsets.UTF_8)
        val half = ByteArray((raw.end - raw.start) / 2)
        var at = raw.start
        var to = 0
        while (at < raw.end) {
            val bits = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
            val h = half(bits)
            check(h and 0x7C00 != 0x7C00) { "A weight of $name is too large for a 16-bit float" }
            half[to] = h.toByte()
            half[to + 1] = (h ushr 8).toByte()
            at += 4
            to += 2
        }
        val out = ByteArrayOutputStream(half.size + 256)
        for (f in parts) {
            when (f.number) {
                2 -> out.number(2, FLOAT16.toLong())
                8 -> out.text(8, name + HALF)
                9 -> out.bytes(9, half)
                else -> out.write(data, f.tag, f.end - f.tag)
            }
        }
        return name to out.toByteArray()
    }

    /** A 32-bit float's bits as the nearest 16-bit float's (ties to even, as everywhere). */
    private fun half(bits: Int): Int {
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        val fraction = bits and 0x7FFFFF
        if (exponent >= 0x1F) return sign or 0x7C00
        if (exponent <= 0) {
            // Too small for a whole 16-bit float: a part of the smallest one, or nothing.
            if (exponent < -10) return sign
            val whole = fraction or 0x800000
            val shift = 14 - exponent
            var h = whole ushr shift
            val left = whole and ((1 shl shift) - 1)
            val middle = 1 shl (shift - 1)
            if (left > middle || (left == middle && h and 1 == 1)) h++
            return sign or h
        }
        var h = (exponent shl 10) or (fraction ushr 13)
        val left = fraction and 0x1FFF
        if (left > 0x1000 || (left == 0x1000 && h and 1 == 1)) h++
        return sign or h
    }

    private companion object {
        const val FLOAT32 = 1
        const val FLOAT16 = 10
        const val HALF = "/half"
    }
}

val fetchVoices = tasks.register<FetchVoices>("fetchVoices") {
    // Supertonic 3 (OpenRAIL-M, Supertone Inc.), made 8-bit for phones by the sherpa-onnx project.
    // The files are listed, with what they're for, in src/main/assets/voices/catalog.json.
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
    pack = "supertonic3"
    size = "about 130 MB"
    files = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx",
        "tts.json", "unicode_indexer.bin", "voice.bin",
    )
    halved = emptyList()
    archive = rootProject.layout.projectDirectory.file(".gradle/voices/supertonic3.tar.bz2")
    assets = layout.buildDirectory.dir("generated/voices")
}

val fetchBrazilianVoice = tasks.register<FetchVoices>("fetchBrazilianVoice") {
    // A voice with a Brazilian accent: Piper's "cadu" (MIT; recorded by a volunteer and given to the
    // public domain, CC0; taught from a CC0 base voice), as the sherpa-onnx project publishes it. It
    // reads sounds, not letters: Jacket writes them itself (see voice/ptbr), so the engine's old
    // GPL-licensed espeak-ng data, which is also in this archive, is neither used nor shipped.
    //
    // This is the full-size model, stored at half size by the build (see halveWeights above). The
    // ready-made 8-bit one is smaller still, but its rounding is heard as a steady hiss; the
    // ready-made 16-bit one doesn't load in this version of the engine.
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-cadu-medium.tar.bz2"
    sha256 = "aba78157d4b89acc17ddef15a70f4b2474f4c189d4de6035002ac7fec9d5d303"
    pack = "cadu"
    size = "about 67 MB"
    files = listOf("pt_BR-cadu-medium.onnx", "tokens.txt")
    halved = listOf("pt_BR-cadu-medium.onnx")
    archive = rootProject.layout.projectDirectory.file(".gradle/voices/cadu-full.tar.bz2")
    assets = layout.buildDirectory.dir("generated/voices-pt-br")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchVoices, FetchVoices::assets)
        variant.sources.assets?.addGeneratedSourceDirectory(fetchBrazilianVoice, FetchVoices::assets)
    }
}
