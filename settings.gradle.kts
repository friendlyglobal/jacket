pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (the on-device voice engine, Apache-2.0) publishes its Android library as a
        // GitHub release asset rather than on Maven Central. Only that one module comes from here,
        // and app/build.gradle.kts checks the file against a pinned SHA-256.
        ivy {
            name = "sherpaOnnxReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx-static-link-onnxruntime") }
        }
    }
}

rootProject.name = "Jacket"
include(":app")
