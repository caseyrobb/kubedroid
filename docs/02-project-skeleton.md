---
description: Defines the kubedroid project skeleton — Gradle build with version catalog, flat single-module src/ layout, Hilt wiring, Material 3 theme, and a Navigation 3 adaptive shell with a placeholder Home destination.
status: active
---

# Project Skeleton

This spec implements stage 1 of the implementation order in
`01-project-architecture.md`: a buildable, empty-but-running app shell.
After this spec is `active`, later specs (cluster connections, resource
browsing, etc.) only add feature code and never touch build plumbing.

## Scope

In scope:

* Gradle build (Kotlin DSL, version catalog, Gradle wrapper).
* Single Gradle module at the repo root (flat `src/` layout, per AGENTS.md).
* Application class + main activity + Hilt bootstrapping.
* Material 3 theming (light/dark, dynamic color).
* Navigation 3 shell: user-owned back stack, `NavDisplay`, one placeholder
  Home destination, adaptive scene scaffolding.

Out of scope (future specs):

* Anything touching Kubernetes: no kubeconfig, no client library, no
  repositories.
* Credential storage (DataStore / Keystore) — arrives with the cluster
  connection spec.
* CI, linters, formatters, typecheckers (explicitly forbidden by AGENTS.md
  unless the project owner adds them).

## Build setup

* Gradle wrapper pinned via `gradle/wrapper/gradle-wrapper.properties`.
* Version catalog at `gradle/libs.versions.toml`; **no literal versions in
  any `build.gradle.kts`** — every dependency/plugin references the catalog.
* Single-module build: root `build.gradle.kts` applies `com.android.application`,
  `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`,
  `com.google.devtools.ksp`, and `com.google.dagger.hilt.android`.
  `org.jetbrains.kotlin.android` is deliberately NOT applied: Kotlin support is
  built into AGP 9 (kotl.in/gradle/agp-built-in-kotlin) and applying the plugin
  fails the build. `settings.gradle.kts` declares
  `pluginManagement` + `dependencyResolutionManagement` (repos: `google()`,
  `mavenCentral()`) and includes no subprojects.
* Source layout (flat, per AGENTS.md):

  ```
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  gradle/wrapper/...
  src/main/AndroidManifest.xml
  src/main/java/com/neozeon/kubedroid/...
  src/main/res/...
  src/test/java/com/neozeon/kubedroid/...
  ```

* JVM/Java toolchain: Java 17 (`kotlin { compilerOptions { jvmTarget = 17 } }`,
  `compileOptions { sourceCompatibility/targetCompatibility = 17 }`).
  AGP 9.x requires JDK 17+ to run Gradle.

## Application shell

* `KubeApplication` — annotated `@HiltAndroidApp`. No bindings defined yet;
  `di/` contains only an empty-ops module placeholder is NOT created until a
  spec needs a binding.
* `MainActivity` extends `ComponentActivity`, annotated `@AndroidEntryPoint`,
  calls `enableEdgeToEdge()` then `setContent { KubedroidTheme { KubedroidApp() } }`.
* `ui/theme/`: `Color.kt`, `Theme.kt`, `Type.kt` per standard Material 3
  template. `KubedroidTheme` uses dynamic color on Android 12+ (API 31+),
  falling back to the app's static seed palette; supports light and dark.
  Brand colors are placeholders — design polish is a later spec.

## Navigation 3 shell

* `navigation/NavKey.kt`: `@Serializable` `object NavKey`s; skeleton ships
  `HomeKey` only. All keys live in one file and are `@Serializable` so
  Nav3's saveable-state decorator can persist them.
* `navigation/KubedroidApp.kt`: owns the back stack with
  `rememberNavBackStack(HomeKey)`, supplies `NavDisplay` with:
  * `entryProvider { entry<HomeKey> { HomeScreen(...) } }`
  * `entryDecorators = rememberSaveableStateHolderNavEntryDecorator() +
    rememberViewModelStoreNavEntryDecorator()` (lifecycle 2.11 line)
  * `onBack = { backStack.removeLastOrNull() }` — back handling is
    app-owned under Nav3, so a single testable helper function
    `popBackStack(backStack)` wraps the mutation.
* `ui/home/HomeScreen.kt`: Material 3 `Scaffold` + `TopAppBar` titled
  "kubedroid" + placeholder body text. It is the future cluster-list
  landing screen.
* Adaptive scaffolding: a `WindowSizeClassProvider` helper in `ui/adaptive/`
  exposes `currentWindowAdaptiveInfo()` for feature specs; the skeleton does
  not wire `ListDetailSceneStrategy` yet (no list-detail destinations
  exist), but pins the `adaptive-navigation3` artifact so stage-3 specs
  don't change build files.

## Additional dependencies

All pins verified against Maven Central / AndroidX release pages on
2026-10-23. Anything absent here must be introduced by a later spec with
its own "Additional dependencies" section.

Build plugins:

| Plugin | Version |
| --- | --- |
| Gradle (wrapper) | 9.6.0 |
| AGP (`com.android.application`) | 9.4.1 |
| Kotlin (`android`, `plugin.compose`, `plugin.serialization`) | 2.4.21 |
| KSP (`com.google.devtools.ksp`) | 2.3.12 |
| Hilt (`com.google.dagger.hilt.android`) | 2.60.1 |

Runtime:

| Artifact | Version | Notes |
| --- | --- | --- |
| `androidx.core:core` | 1.19.1 | core-ktx is an empty compat artifact since 1.19.0 |
| `androidx.activity:activity-compose` | 1.13.0 | |
| `androidx.compose:compose-bom` | 2026.09.00 | governs compose-* artifacts (ui 1.12.1, material3 1.4.0) |
| `androidx.compose.material3:material3` | BOM | |
| `androidx.compose.material3.adaptive:adaptive` | 1.3.0 | window size classes |
| `androidx.compose.material3.adaptive:adaptive-navigation3` | 1.4.0-alpha03 | alpha; only Nav3 scene API available (see Open questions) |
| `androidx.navigation3:navigation3-runtime` | 1.2.0 | |
| `androidx.navigation3:navigation3-ui` | 1.2.0 | |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | |
| `androidx.lifecycle:lifecycle-viewmodel-navigation3` | 2.11.0 | ViewModel-store entry decorator |
| `androidx.hilt:hilt-navigation-compose` | 1.4.0 | `hiltViewModel()` (see Open questions) |
| `com.google.dagger:hilt-android` | 2.60.1 | |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.9.0 | serializable NavKeys |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | test scope (NavKey round trip) |

Test-only:

| Artifact | Version | Notes |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | |
| `org.robolectric:robolectric` | 4.17 | supports SDK 37 |
| `androidx.test.ext:junit` | 1.3.0 | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | used by later specs; pinned now to avoid version drift |
| `com.google.dagger:hilt-android-testing` | 2.60.1 | |
| `androidx.compose.ui:ui-test-manifest` | BOM | also on the unit-test classpath |

Unit-test JVM flags (in `build.gradle.kts` `testOptions.unitTests.all`):
Robolectric 4.17 on JDK 17+ needs `--add-opens` for `java.base/java.io`,
`java.lang`, `java.lang.reflect`, `java.util`, `java.util.concurrent`,
`java.net`, `sun.nio.fs`, plus `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`
(robolectric#11434). Without them every Robolectric test dies in
`FileDescriptorInterceptor`.

`src/debug/AndroidManifest.xml` explicitly declares
`androidx.activity.ComponentActivity` (exported=true, matching the
ui-test-manifest AAR): Robolectric resolves activities only from the
variant's merged manifest, so the createComposeRule-hosting activity must be
declared there (debug source set only — never in release).

## Configuration

No runtime configuration properties. Build configuration values:

| Property | Value | Notes |
| --- | --- | --- |
| `namespace` / `applicationId` | `com.neozeon.kubedroid` | |
| `compileSdk` | 37 | platform `android-37.0` (Google now publishes versioned minors); AGP 9.4 max |
| `targetSdk` | 37 | |
| `minSdk` | 26 | per `00-project-overview.md` |
| `versionCode` / `versionName` | 1 / 0.1.0 | |
| `buildTypes.debug.applicationIdSuffix` | `.debug` | allows side-by-side install |

## Testing

Unit tests (`src/test/`, JVM + Robolectric, no emulator required):

* `MainActivityTest` (Robolectric): app launches, Home scaffold renders,
  top app bar shows "kubedroid".
* `NavKeyTest`: `HomeKey` survives kotlinx-serialization round trip
  (protects Nav3 saved-state restore).
* `BackStackTest`: `popBackStack` helper pops exactly one entry, is a no-op
  on a single-entry stack, and preserves remaining order.
* `ThemeTest`: `KubedroidTheme` composes under both light and dark color
  schemes without throwing (Robolectric + `createComposeRule`).
* Hilt graph compiles (the KSP compilation itself is the test; a broken
  graph fails the build).

Compose instrumented tests are deferred to the first feature spec that has
a user-visible flow to test; the skeleton's Robolectric coverage is
sufficient per the rule "prefer pure functions and immutable state".

## Acceptance criteria

* `./gradlew build` exits 0 (assemble + unit tests + lint).
* `./gradlew assembleDebug` produces `build/outputs/apk/debug/kubedroid-debug.apk`.
* `./gradlew testDebugUnitTest` passes with the four test classes above.
* The debug APK installs and launches on an API 26 device/emulator and an
  API 37 device/emulator, showing the Home placeholder.
* Pressing system back on the root destination exits the app cleanly
  (back stack is single-entry, pop is a no-op).
* No file under `src/` or any Gradle `.kts` contains a hard-coded version
  string outside `gradle/libs.versions.toml` and the wrapper properties.

## Open questions

* **Kubernetes client library?** Deferred to the cluster-connection spec
  (03). Recommendation: `io.kubernetes:client-java` (currently 27.0.0)
  over Fabric8 — Fabric8 assumes Java 11+ APIs and JVM-only SSL/TLS
  assumptions that historically break under Android. *Answered 2026-10-23:
  human confirmed client-java over Fabric8.*
* **`hiltViewModel()` under Nav3 entry-scoped stores:** the standard entry
  path is `androidx.hilt:hilt-navigation-compose`'s `hiltViewModel()`
  reading `LocalViewModelStoreOwner`, which the Nav3 ViewModel-store
  decorator provides. Implementations must verify one injected ViewModel is
  created per entry and cleared on pop; if behavior is wrong, fall back to
  assisted injection + a `di/` ViewModel factory instead of fighting the
  library. *Answered 2026-10-24: the skeleton contains no ViewModels, so this
  is deferred to the first feature spec that injects one (spec 03); the
  assisted-injection fallback stands as the documented plan B.*
* **`adaptive-navigation3` is alpha (1.4.0-alpha03).** It is the only source
  of the canonical `ListDetailSceneStrategy`. Acceptable for now; revisit
  when 1.4.0 reaches stable. *Answered: accept alpha.*

## Dev plan

1. **Toolchain:** Gradle wrapper 9.6.0, settings/build scripts,
   `libs.versions.toml` with every pin above, manifest, resources, empty
   theme. Exit: `./gradlew assembleDebug` produces an APK that shows a
   blank Material surface.
2. **DI + shell:** `KubeApplication`, `MainActivity`, full theme, Hilt
   plugins wired. Exit: launches with Hilt graph intact.
3. **Navigation:** NavKeys, decorators, `KubedroidApp`, `HomeScreen`,
   back helper, window-size-class helper. Exit: placeholder screen with
   working system-back behavior.
4. **Tests + flip:** add the four test classes, make `./gradlew build`
   green, flip this doc to `active`, and update any prose that drifted
   from the actual code.
