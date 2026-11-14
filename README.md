# FastMultiversion

FastMultiversion builds one Minecraft mod for several Minecraft versions, and optionally several loaders, from
a single source tree. Code that differs between versions is ordinary Java: the compiler and the IDE check
every branch.

```java
if (Mc.is("[26.2,)")) {
    mc.gui.setScreen(screen);
} else {
    mc.setScreen(screen);
}
```

## Requirements

- Minecraft 26.1 or newer.
- Java 25 and Gradle 9.
- IntelliJ IDEA 2026.3 for the IDE plugin. The plugin is optional: builds check everything without it.

## Installation

### From a release

[Releases](https://github.com/GahahaWang/FastMultiversion/releases) are stable versions; pre-releases such as
`0.2.0-dev.1` have the latest changes. Each has two files:

- `fastmultiversion-<version>-maven-repository.zip`: the Gradle plugin. Unzip it into your mod, e.g. to
  `libs/fastmultiversion-repo`, and add it to the plugin repositories in `settings.gradle`:

  ```groovy
  pluginManagement {
      repositories {
          maven { url = file('libs/fastmultiversion-repo') }
          mavenCentral()
          gradlePluginPortal()
      }
  }

  plugins {
      id 'cc.gahaha.fastmultiversion' version '<version>'
  }
  ```

- `fastmultiversion-intellij-plugin-<version>.zip`: the IntelliJ plugin. Install it with
  **Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart the IDE.

### From source

Install the Gradle plugin into your local Maven repository, and add `mavenLocal()` to the plugin repositories
instead. The version is a snapshot of the last release, e.g. `0.1.0-SNAPSHOT` (see `gradle.properties`):

```shell
./gradlew publishToMavenLocal
```

To build the IntelliJ plugin, point `ideaPath` at your IntelliJ IDEA installation. Without one, the build
downloads IntelliJ IDEA 2026.3:

```shell
./gradlew :intellij-plugin:buildPlugin -PideaPath="C:/Program Files/JetBrains/IntelliJ IDEA 2026.3"
```

The plugin is then `intellij-plugin/build/distributions/intellij-plugin-0.1.0-SNAPSHOT.zip`.

## Setting up a mod

### settings.gradle

```groovy
pluginManagement {
    repositories {
        mavenLocal()    // or the release repository, see Installation
        maven { url = "https://maven.fabricmc.net/" }
        maven { url = "https://maven.neoforged.net/releases" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id 'cc.gahaha.fastmultiversion' version '0.1.0-SNAPSHOT'
}

rootProject.name = 'MyMod'

multiversion {
    versions '26.1.2', '26.2', '26.3'
    loaders 'fabric', 'neoforge'    // leave out for a mod without loader projects
}
```

All settings of the `multiversion` block:

| Setting | Meaning |
| --- | --- |
| `versions '26.1.2', '26.2'` | The Minecraft versions to build for. Required. |
| `loaders 'fabric', 'neoforge'` | One project per version and loader. Optional. |
| `active = '26.2'` | The version the IDE works with. Default: the newest. |
| `activeLoader = 'fabric'` | The loader the IDE works with. Default: the first. |
| `versionScript = 'version.gradle'` | Build script of each version project, without loaders. Default: `version.gradle`. |

### Project layout

```text
settings.gradle
build.gradle          loader plugins, declared with `apply false`
common.gradle         with several loaders: Minecraft without a loader, for the shared code
fabric.gradle         the build script of every :<version>:fabric project
neoforge.gradle       the build script of every :<version>:neoforge project
src/main/             code shared by all loaders
src/fabric/           Fabric-only code
src/neoforge/         NeoForge-only code
versions/             one directory per version and loader, created for you
```

FastMultiversion creates a Gradle project for each version and loader, such as `:26.2:fabric` in
`versions/26.2/fabric`. Each of these projects runs `fabric.gradle`. Without loaders there is one project per
version, such as `:26.2` in `versions/26.2`, running `version.gradle`.

Declare the loader plugins once in the root `build.gradle`, so that all version projects share them:

```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version "${fabric_loom_version}" apply false
    id 'net.neoforged.moddev' version "${moddevgradle_version}" apply false
}
```

### Loader build scripts

`fabric.gradle`, `neoforge.gradle` and `version.gradle` are ordinary build scripts that set up the loader for
one version. The `multiversion` object describes the project being configured:

| Property | Value |
| --- | --- |
| `multiversion.version` | This project's Minecraft version, e.g. `'26.2'` |
| `multiversion.loader` | This project's loader, e.g. `'fabric'` |
| `multiversion.common` | Whether this is a `:<version>:common` project |
| `multiversion.active` | Whether this is the version and loader the IDE works with |
| `multiversion.versions` | All versions |
| `multiversion.is('[26.2,)')` | Whether this project's version is in the range |

```groovy
apply plugin: 'net.fabricmc.fabric-loom'

def mc = multiversion.version

loom {
    runs {
        client {
            client()
            ideConfigGenerated(multiversion.active)    // run configurations for the active version only
        }
    }
}

dependencies {
    minecraft "net.minecraft:minecraft:${mc}"
    implementation "net.fabricmc:fabric-loader:${fabric_loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.property("mc.${mc}.fabric_api")}"
}
```

Dependency versions that change with Minecraft fit well in `gradle.properties`, one key per version:

```properties
mc.26.2.fabric_api=0.161.0+26.2
mc.26.3.fabric_api=0.166.2+26.3
```

### common.gradle

With two or more loaders, `common.gradle` is required. It sets up Minecraft without a loader, plus the
libraries the shared code in `src/main` uses. With it, Fabric code and NeoForge code both see the shared code,
but not each other's code.

```groovy
apply plugin: 'net.fabricmc.fabric-loom'

dependencies {
    minecraft "net.minecraft:minecraft:${multiversion.version}"
}
```

### .gitignore

```text
versions/**/build/
versions/**/runs/
versions/**/.gradle/
```

## Writing version-specific code

Import `cc.gahaha.fastmultiversion.Mc` and `cc.gahaha.fastmultiversion.IfMc`. Each version's build keeps only
the code that runs in that version.

### Statements and expressions

```java
if (Mc.is("[26.2,)")) {
    mc.gui.setScreen(screen);
} else {
    mc.setScreen(screen);
}

Screen current = Mc.is("[26.2,)") ? mc.gui.screen() : mc.screen;

boolean hidden = Mc.is("[26.2,)") ? mc.gui.hud.isHidden() : mc.options.hideGui;
```

An early return limits the code after it:

```java
if (!Mc.is("[26.2,)")) {
    return mc.options.hideGui;
}
return mc.gui.hud.isHidden();    // only 26.2 and newer get here
```

`&&` and `||` work too: in `Mc.is("[26.2,)") && mc.gui.hud.isHidden()`, the right side only runs in 26.2
and newer.

### Declarations

`@IfMc` keeps a class, method, constructor, field, enum constant or record component only in the versions in
its range:

```java
@IfMc("[26.1,26.2)")
static Screen screen(Minecraft mc) { return mc.screen; }

@IfMc("[26.2,)")
static Screen screen(Minecraft mc) { return mc.gui.screen(); }
```

### Version ranges

| Range | Versions |
| --- | --- |
| `"26.2"` or `"[26.2]"` | only 26.2 |
| `"[26.2,)"` | 26.2 and newer |
| `"(,26.2)"` | before 26.2 |
| `"[26.1,26.2)"` | from 26.1 up to 26.2, without 26.2 |
| `"(26.1,26.3]"` | after 26.1, up to and with 26.3 |
| `"[26.1,26.2),[26.3,)"` | several ranges together |

`[` and `]` include the version next to them; `(` and `)` leave it out.

A range must be a string literal or a `String` constant declared in the same file:

```java
private static final String GUI_SCREEN = "[26.2,)";

if (Mc.is(GUI_SCREEN)) { ... }
```

Call `Mc.is` directly with its range. A method reference such as `Mc::is` is not allowed.

### Classes that moved to another package

If a class lives in a different package in some versions, import both packages on demand:

```java
import com.mojang.blaze3d.vertex.*;      // older versions
import com.mojang.renderpearl.vertex.*;  // newer versions
```

Each version uses the package it has. Importing the class from both packages by name also builds, but the IDE
only resolves the name when the active version's import comes first.

## Building

| Command | Result |
| --- | --- |
| `./gradlew buildAllVersions` | Every version and loader; jars in `versions/<version>/<loader>/build/libs/` |
| `./gradlew compileAllVersions` | Compiles every version and loader, including the common projects |
| `./gradlew :26.2:fabric:build` | One version and loader |
| `./gradlew :26.3:fabric:runClient` | Runs the game, with the loader plugin's own tasks |
| `./gradlew generateApiDatabase` | Updates the list of Minecraft API per version (see below) |

The build stops when code uses Minecraft API in a version that does not have it, for example:

```text
'Minecraft.setScreen(Screen)' only exists in [26.1,26.2), but this code runs in [26.1,).
Use it inside if (Mc.is("[26.1,26.2)")) or in a declaration with @IfMc("[26.1,26.2)").
```

Put such code inside `Mc.is` or `@IfMc` with the range the message gives.

## Working in IntelliJ IDEA

1. Open the root directory and let IntelliJ import the Gradle project.
2. Run `./gradlew generateApiDatabase` once, and again after you change the versions or dependencies. The IDE
   plugin reads this database. Any build also updates it.

The IDE works with the **active version and loader**: the newest version and the first loader unless you set
them. To switch, set them in `gradle.properties` and reload the Gradle project:

```properties
multiversion.active=26.2
multiversion.activeLoader=neoforge
```

They can also be set with `-Pmultiversion.active=26.2` or in the `multiversion` block of `settings.gradle`.

With the FastMultiversion plugin installed:

- Code for other versions is checked against those versions. Errors in it name the version, e.g.
  `[26.1.2] Cannot resolve method 'setScren' in 'Minecraft'`.
- Go to Declaration, Quick Documentation and completion inside such code use that version's Minecraft.
- Minecraft API used in code that also runs in versions without it is reported as you type. Press
  **Alt+Enter** for the fixes:
  - Surround with `if (Mc.is(...))`
  - Annotate with `@IfMc(...)`
  - Import a class of another version
  - Import packages on demand
- Version ranges are checked as you type. Press **Ctrl+Space** inside a range to choose from the project's
  versions.
