pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    // Sherpa-ONNX публикует AAR только в релизах GitHub: v2.0.1/sherpa-onnx-2.0.1.aar
    ivy {
      url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
      patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
      metadataSources { artifact() }
      content { includeGroup("com.k2fsa") }
    }
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AI Dictaphone"

include(":app")
