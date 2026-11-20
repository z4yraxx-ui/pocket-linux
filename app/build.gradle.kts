import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// "dono/repositorio" do GitHub onde fica a Release com o rootfs do Debian.
// O workflow de build passa -ProotfsRepo=$GITHUB_REPOSITORY automaticamente.
// Para compilar localmente: gradle assembleDebug -ProotfsRepo=seu-usuario/PocketLinux
val rootfsRepo: String = (findProperty("rootfsRepo") as String?)
    ?.takeIf { it.isNotBlank() } ?: "SEU_USUARIO/PocketLinux"

// PRoot ARM64 pronto para Android (green-green-avk/build-proot-android, licença MIT;
// o PRoot em si é GPL-2.0+ e o talloc é LGPL). Fixado em um commit para o build ser reproduzível.
val prootCommit = "01f83b8841358450c78333d1b33ab30d4943bec4"
val prootUrl = "https://raw.githubusercontent.com/green-green-avk/build-proot-android/" +
    "$prootCommit/packages/proot-android-aarch64.tar.gz"
val prootGenDir = layout.buildDirectory.dir("generated/proot")

android {
    namespace = "com.pocketlinux.app"
    compileSdk = 35
    // Mesma versão do NDK que o servidor X (módulo :lorie) exige; o workflow instala a mesma.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pocketlinux.app"
        minSdk = 26
        // targetSdk 28 de propósito (mesma escolha do Termux): a partir do 29 o Android
        // proíbe executar arquivos da pasta de dados do app, e é lá que o Debian fica.
        targetSdk = 28
        versionCode = 7
        versionName = "0.6.1-teste"

        // O rootfs é ARM64, então o APK só precisa da ABI arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "ROOTFS_REPO", "\"$rootfsRepo\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // O PRoot é um executável disfarçado de biblioteca (libproot.so): precisa ser
        // extraído para o disco na instalação para poder ser executado.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**"
            )
        }
    }

    // Código nativo próprio do terminal (pseudoterminal): app/src/main/cpp/pty.c
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/proot/jniLibs"))
        }
    }
}

// Baixa o PRoot e o coloca em jniLibs/arm64-v8a como libproot.so e libproot-loader.so.
val fetchProot = tasks.register("fetchProot") {
    val genDir = prootGenDir
    outputs.dir(genDir)
    doLast {
        val base = genDir.get().asFile
        val libDir = File(base, "jniLibs/arm64-v8a")
        val prootOut = File(libDir, "libproot.so")
        val loaderOut = File(libDir, "libproot-loader.so")
        if (prootOut.exists() && loaderOut.exists()) return@doLast

        libDir.mkdirs()
        val tgz = File(base, "proot-android-aarch64.tar.gz")
        if (!tgz.exists() || tgz.length() == 0L) {
            URL(prootUrl).openStream().use { input ->
                tgz.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val extracted = File(base, "extracted")
        extracted.deleteRecursively()
        copy {
            from(tarTree(resources.gzip(tgz)))
            into(extracted)
        }

        val files = extracted.walkTopDown().filter { it.isFile }.toList()
        val prootSrc = files.firstOrNull { it.name == "libproot.so" }
            ?: files.firstOrNull { it.name == "proot" }
        val loaderSrc = files.firstOrNull { it.name == "libproot-loader.so" }
            ?: files.firstOrNull { it.name == "loader" }
            ?: files.firstOrNull { it.name.contains("loader") && !it.name.contains("32") }

        if (prootSrc == null || loaderSrc == null) {
            val listing = files.joinToString("\n") { "  " + it.relativeTo(extracted).path }
            throw GradleException(
                "Não achei o PRoot/loader dentro de $prootUrl\nArquivos encontrados:\n$listing"
            )
        }

        prootSrc.copyTo(prootOut, overwrite = true)
        loaderSrc.copyTo(loaderOut, overwrite = true)
        logger.lifecycle(
            "PRoot: ${prootSrc.relativeTo(extracted).path} + loader: ${loaderSrc.relativeTo(extracted).path}"
        )
    }
}

tasks.named("preBuild") { dependsOn(fetchProot) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Leitor de .tar (o Android não tem um nativo).
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Servidor X do Termux:X11 (GPL-3.0): tela, teclado e mouse do desktop.
    implementation(project(":lorie"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
