package com.nexg.ide.application

/**
 * The single project template `ProjectManager` can generate in Phase 2.
 *
 * Scope is deliberately narrow: a minimal, valid Android + Gradle project that
 * matches the version matrix pinned in `PLAN.MD` Part 7, so the created project
 * can actually be opened and built. This is not a project generator — no
 * variants, no Kotlin/Java/Compose/Flavors choice, no per-project name
 * substitution. Adding those is a later phase, and a half-built generator is
 * worse than a small honest one.
 *
 * The package is fixed (`com.nexg.template`) rather than derived from the
 * project name. Deriving it would need identifier sanitisation rules, and
 * getting those subtly wrong produces a template that does not compile, which
 * is a worse outcome than a shared package for a first template.
 *
 * All content lives here as plain Kotlin strings so template generation is
 * unit-testable on the JVM with a fake [com.nexg.ide.domain.port.FileSystemPort]
 * — no device and no SAF provider required.
 */
object ProjectTemplate {

    const val TEMPLATE_ID = "android-gradle-minimal"
    const val TEMPLATE_LABEL = "Android (Kotlin, minimal)"

    /** Fixed package of the generated project. */
    const val PACKAGE = "com.nexg.template"
    const val APPLICATION_ID = "com.nexg.template"

    /**
     * The same package as a *directory path*.
     *
     * `MainActivity.kt` declares `package com.nexg.template`, and Kotlin/Java
     * require that to live at `com/nexg/template/`. Building the file path from
     * [PACKAGE] directly produced a single directory literally named
     * `com.nexg.template`, so the generated project would not have compiled.
     * Deriving the path from the package keeps the two from drifting apart.
     */
    val PACKAGE_PATH: String = PACKAGE.replace('.', '/')

    /**
     * Versions copied from `PLAN.MD` Part 7 so the generated project's wrapper
     * settings match the one NexG itself builds with.
     */
    const val AGP_VERSION = "8.9.2"
    const val KOTLIN_VERSION = "2.1.0"
    const val COMPILE_SDK = 36
    const val MIN_SDK = 26
    const val TARGET_SDK = 36

    data class TemplateFile(val relativePath: String, val content: String)

    val files: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "settings.gradle.kts",
            content = """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                    plugins {
                        id("com.android.application") version "$AGP_VERSION" apply false
                        id("org.jetbrains.kotlin.android") version "$KOTLIN_VERSION" apply false
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }

                rootProject.name = "app"
                include(":app")
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "build.gradle.kts",
            content = """
                plugins {
                    id("com.android.application") apply false
                    id("org.jetbrains.kotlin.android") apply false
                }
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "gradle.properties",
            content = """
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                android.useAndroidX=true
                android.nonTransitiveRClass=true
                kotlin.code.style=official
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "app/build.gradle.kts",
            content = """
                import org.jetbrains.kotlin.gradle.dsl.JvmTarget

                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$PACKAGE"
                    compileSdk = $COMPILE_SDK

                    defaultConfig {
                        applicationId = "$APPLICATION_ID"
                        minSdk = $MIN_SDK
                        targetSdk = $TARGET_SDK
                        versionCode = 1
                        versionName = "0.1.0"
                    }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                }

                kotlin {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "app/src/main/AndroidManifest.xml",
            content = """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                    <application
                        android:allowBackup="true"
                        android:label="NexG Template"
                        android:theme="@android:style/Theme.Material.Light">

                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "app/src/main/java/$PACKAGE_PATH/MainActivity.kt",
            content = """
                package $PACKAGE

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        val label = TextView(this)
                        label.text = "NexG template project"
                        setContentView(label)
                    }
                }
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # Created by NexG IDE

                Minimal Android + Kotlin project generated from the
                "$TEMPLATE_ID" template.

                | Setting     | Value        |
                |-------------|--------------|
                | AGP         | $AGP_VERSION |
                | Kotlin      | $KOTLIN_VERSION |
                | compileSdk  | $COMPILE_SDK |
                | minSdk      | $MIN_SDK |
                | targetSdk   | $TARGET_SDK |
                | JVM target  | 17 |

                Note: `gradle/wrapper/` is not included. A Gradle wrapper JAR is
                binary and cannot be written through SAF, so run
                `gradle wrapper` once in this directory to generate it.

                Open this folder in NexG and start editing.
            """.trimIndent() + "\n",
        ),
    )

    /**
     * Splits a relative path into the ordered directory segments a document
     * provider needs, because SAF can only create one child at a time.
     *
     * `app/src/main/AndroidManifest.xml` becomes
     * `listOf("app", "src", "main", "AndroidManifest.xml")`.
     */
    fun segments(relativePath: String): List<String> =
        relativePath.split('/').filter { it.isNotEmpty() }

    /**
     * Directories the template requires that contain no file of their own.
     *
     * Derived directories alone are not enough: `app/src/main/res` is part of the
     * required skeleton but no generated file lives inside it, so deriving from
     * [files] would skip it and the Explorer would show a project with no
     * resource directory at all. Empty-but-present is the correct state here —
     * the template's manifest uses a framework theme, so it needs no resources
     * to be valid.
     */
    val extraDirectories: List<String> = listOf(
        "app/src/main/res",
        "app/src/main/res/values",
    )

    /**
     * Every directory the template requires, parents before children.
     *
     * Ordering is by depth so a segment's parent always exists before it does;
     * the writer walks each entry from the project root, so a shallow entry
     * appearing after a deep one is harmless but wasteful.
     */
    val requiredDirectories: List<String> = buildList {
        for (file in files) {
            val parts = segments(file.relativePath)
            for (i in 1 until parts.size) {
                val dir = parts.take(i).joinToString("/")
                if (dir !in this) add(dir)
            }
        }
        for (dir in extraDirectories) {
            if (dir !in this) add(dir)
        }
    }.sortedBy { it.count { c -> c == '/' } }

    /**
     * File paths the template must produce, for tests and for the Explorer to
     * know what a complete project looks like.
     */
    val requiredFilePaths: List<String> = files.map { it.relativePath }

    /**
     * A path is inside the project if the template is complete.
     *
     * A convenience for assertions, and for the ProjectLayout probe: a directory
     * that exists but has no files under it is not evidence of a generated
     * project, so presence of a file is the test.
     */
    fun isCompleteProject(presentPaths: Set<String>): Boolean =
        requiredFilePaths.all { path -> presentPaths.any { it.endsWith(path) } }
}
