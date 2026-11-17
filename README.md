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

### Gradle plugin

Add the FastMultiversion Maven repository to the plugin repositories in `settings.gradle`, and apply the
plugin:

```groovy
pluginManagement {
    repositories {
        maven { url = 'https://gahahawang.github.io/FastMultiversion/maven' }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id 'cc.gahaha.fastmultiversion' version 'latest.release'
}
```

`latest.release` is always the newest [release](https://github.com/GahahaWang/FastMultiversion/releases).
Gradle looks for a new one once a day, or right away with `--refresh-dependencies`. To keep a build on one
release, write its version instead, e.g. `version '1.0.0'`.

For the dev channel, the newest pre-release (`x.y.z-dev.n`, from every change on the dev branch) or release,
whichever is newer, use this repository and version instead:

```groovy
maven { url = 'https://gahahawang.github.io/FastMultiversion/maven-dev' }
```

```groovy
id 'cc.gahaha.fastmultiversion' version 'latest.integration'
```

A release is newer than its pre-releases, so the dev channel moves to it once it is out.

Without network access, unzip the release's `fastmultiversion-<version>-maven-repository.zip` into your mod,
e.g. to `libs/fastmultiversion-repo`, and use `maven { url = file('libs/fastmultiversion-repo') }` instead.

### IntelliJ plugin

Download `fastmultiversion-intellij-plugin-<version>.zip` from the newest release, install it with
**Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart the IDE.

From then on, IntelliJ IDEA offers its updates: the plugin adds its own plugin repository. To add it by hand,
use **Settings → Plugins → ⚙ → Manage Plugin Repositories…** with
`https://gahahawang.github.io/FastMultiversion/updatePlugins.xml`.

A pre-release of the plugin (`fastmultiversion-intellij-plugin-x.y.z-dev.n.zip`, from the
[pre-releases](https://github.com/GahahaWang/FastMultiversion/releases)) adds the dev channel instead,
`https://gahahawang.github.io/FastMultiversion/updatePlugins-dev.xml`, and so gets the pre-releases too.
IntelliJ IDEA counts x.y.z-dev.n as newer than x.y.z, so after a release it stays on the pre-release of the
same code until the next pre-release.

### From source

To try changes to FastMultiversion itself, install the Gradle plugin into your local Maven repository, and add
`mavenLocal()` to the plugin repositories instead. The version is the last release's, from `gradle.properties`,
plus `-SNAPSHOT`, e.g. `0.4.0-SNAPSHOT`:

```shell
./gradlew publishToMavenLocal
```

To build the IntelliJ plugin, point `ideaPath` at your IntelliJ IDEA installation. Without one, the build
downloads IntelliJ IDEA 2026.3:

```shell
./gradlew :intellij-plugin:buildPlugin -PideaPath="C:/Program Files/JetBrains/IntelliJ IDEA 2026.3"
```

The plugin is then in `intellij-plugin/build/distributions/`.

## Setting up a mod

### With the IntelliJ IDEA wizard

With the IntelliJ plugin installed, choose **File → New → Project…**, then **FastMultiversion** under
**Generators**, and enter:

- the mod ID, the mod name and the Java package;
- the Minecraft versions: tick them in the list of every Minecraft version from 26.1 on, read from Mojang's
  version list. **Show snapshots** adds snapshots, pre-releases and release candidates;
- the loaders: Fabric, NeoForge or both. With both, the main loader: the common projects compile the shared code
  against Minecraft as its Gradle plugin sets it up (Loom, or ModDevGradle's NeoForm);
- the license, one of those the Minecraft Development plugin offers or CC BY 4.0: it goes into `fabric.mod.json`,
  `neoforge.mods.toml` and a `LICENSE` file (with the year and the authors, or the mod's name);
- under **Optional Settings**, the description, the authors (separated by commas), the website, the repository and
  the issue tracker, for `fabric.mod.json` (`description`, `authors`, `contact`) and `neoforge.mods.toml`
  (`description`, `authors`, `displayURL`, `issueTrackerURL`).

The wizard creates the project described below, with a Gradle wrapper and a small mod for each loader, and
imports it. With both loaders, shared code goes in `src/main` and each loader's in `src/<loader>`. With one loader,
the project has no loader projects (one project per version, running `gradle/version/version.gradle`), and all
code is in `src/main`, where the mod's main class is also the loader's entrypoint.

Every new project also has, in `src/main`:

- one empty file of access changes in the format of its loader, or its main loader:
  `src/main/resources/<mod id>.classtweaker` (Fabric) or `src/main/resources/META-INF/accesstransformer.cfg`
  (NeoForge). The other loader gets it converted (see
  [Access wideners, class tweakers and access transformers](#access-wideners-class-tweakers-and-access-transformers)),
  and the build scripts write down what Loom and ModDevGradle get: `accessWidenerPath = multiversion.accessWidener`,
  `accessTransformers = multiversion.accessTransformers`;
- an empty mixin package, `<package>.mixin`, and its config `<mod id>.mixins.json`, which `fabric.mod.json` and
  `neoforge.mods.toml` name. With both loaders, the common projects compile against Mixin and MixinExtras, so that
  the shared code can have mixins too.

The wizard looks up the newest releases of Fabric Loom, ModDevGradle, Fabric Loader, Mixin and MixinExtras, and
Fabric API, NeoForge and NeoForm for each Minecraft version. What it reads online (also Mojang's version list) is
kept in the IDE's system directory: offline, the wizard uses what it read last time, and only before it ever read
them the versions the plugin ships. What it cannot find at all is marked `UNKNOWN` in `gradle.properties`.

The project uses the newest Gradle plugin of the IntelliJ plugin's channel: `maven` with `latest.release`, or for a pre-release `maven-dev` with
`latest.integration`; for an IntelliJ plugin built from source, its version from `mavenLocal()`.

To set up a project by hand instead, follow the rest of this section.

### settings.gradle

```groovy
pluginManagement {
    repositories {
        maven { url = 'https://gahahawang.github.io/FastMultiversion/maven' }
        maven { url = "https://maven.fabricmc.net/" }
        maven { url = "https://maven.neoforged.net/releases" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id 'cc.gahaha.fastmultiversion' version 'latest.release'
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
| `versionScript = 'version.gradle'` | Build script of each version project, without loaders. Default: `gradle/version/version.gradle`. |

### Project layout

```text
settings.gradle
build.gradle                      loader plugins, declared with `apply false`
gradle/common/common.gradle       with several loaders: Minecraft without a loader, for the shared code
gradle/fabric/fabric.gradle       the build script of every :<version>:fabric project
gradle/neoforge/neoforge.gradle   the build script of every :<version>:neoforge project
src/main/                         code shared by all loaders
src/fabric/                       Fabric-only code
src/neoforge/                     NeoForge-only code
versions/                         one directory per version and loader, created for you
```

FastMultiversion creates a Gradle project for each version and loader, such as `:26.2:fabric` in
`versions/26.2/fabric`. Each of these projects runs `gradle/fabric/fabric.gradle`. Without loaders there is
one project per version, such as `:26.2` in `versions/26.2`, running `gradle/version/version.gradle`.

A mod for one loader needs no `loaders`: `gradle/version/version.gradle` sets up that loader, and all code is in
`src/main`:

```text
settings.gradle                   multiversion { versions ... }, without loaders
build.gradle                      the loader's plugin, declared with `apply false`
gradle/version/version.gradle     the build script of every :<version> project
src/main/                         all code and resources
versions/                         one directory per version, created for you
```

Keep each build script in its own directory under `gradle/`: there, IntelliJ IDEA knows the plugins the
script applies and completes and checks `loom { }`, `minecraft`, `multiversion` and so on. A script can also
be in the root directory, such as `fabric.gradle`, but there the IDE cannot resolve them.

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
| `multiversion.active` | Whether this is the version the IDE works with |
| `multiversion.versions` | All versions |
| `multiversion.is('[26.2,)')` | Whether this project's version is in the range |
| `multiversion.accessWidener` | The access widener made from all the access files among the project's resources, with this version's lines: Loom's `accessWidenerPath` by default (see [Access wideners, class tweakers and access transformers](#access-wideners-class-tweakers-and-access-transformers)) |
| `multiversion.accessTransformers` | The same as an access transformer: ModDevGradle's access transformers by default |
| `multiversion.versioned('src/fabric/resources/mymod.classtweaker')` | This version's copy of one access widener, class tweaker or access transformer, nothing merged or converted |

```groovy
apply plugin: 'net.fabricmc.fabric-loom'

def mc = multiversion.version

loom {
    runs {
        configureEach {
            ideConfigGenerated(true)                    // a run configuration per version...
            appendProjectPathToDisplayName = false      // ...named "Fabric Client 26.3", not "... (:26.3:fabric)"
        }
        client {
            client()
            setConfigName("Fabric Client ${mc}")
            runDir('runs/client')
        }
        server {
            server()
            setConfigName("Fabric Server ${mc}")
            runDir('runs/server')
        }
    }
}

dependencies {
    minecraft "net.minecraft:minecraft:${mc}"
    implementation "net.fabricmc:fabric-loader:${fabric_loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.property("mc.${mc}.fabric_api")}"
}
```

With ModDevGradle, name each version's runs with `ideName`:

```groovy
neoForge {
    runs {
        configureEach {
            ideName = "NeoForge ${it.name.capitalize()} ${mc}"   // "NeoForge Client 26.3"
        }
        client { client() }
        server { server() }
    }
}
```

Loom's run configurations run the game through Gradle, which builds that version with all the sources, so every
version's configuration works, not only the active one's.

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

With NeoForge as the main loader, ModDevGradle's NeoForm sets up Minecraft without a loader instead:

```groovy
apply plugin: 'net.neoforged.moddev'

neoForge {
    neoFormVersion = project.property("mc.${multiversion.version}.neoform")   // e.g. mc.26.3.neoform=26.3-1
}
```

### .gitignore

```text
versions/**/build/
versions/**/runs/
versions/**/.gradle/
```

## Writing version-specific code

Import `cc.gahaha.fastmultiversion.Mc`: `Mc.is(...)` for statements and expressions, `@Mc.Is(...)` for
declarations. Each version's build keeps only the code that runs in that version.

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

`@Mc.Is` keeps a class, method, constructor, field, enum constant or record component only in the versions in
its range:

```java
@Mc.Is("[26.1,26.2)")
static Screen screen(Minecraft mc) { return mc.screen; }

@Mc.Is("[26.2,)")
static Screen screen(Minecraft mc) { return mc.gui.screen(); }
```

`@Mc.Is` replaces `@IfMc` of FastMultiversion before 1.0: replace `@IfMc(` with `@Mc.Is(` and remove
`import cc.gahaha.fastmultiversion.IfMc;`.

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

Where a version has both classes (a library may keep the old class next to the moved one), the first of the
imports wins in that version, in the build and in the IDE:

```java
import fi.dy.masa.malilib.util.position.LayerRange;  // preferred wherever it exists
import fi.dy.masa.malilib.util.LayerRange;           // only where the other one does not
```

A name means one class in each version, so in every version that has both classes, `LayerRange` is the first
one. To use the other class in such a version, write its full name, e.g. `fi.dy.masa.malilib.util.LayerRange`.

A method that returns another class in some versions can be called once for all of them, as long as its
result is only used right away and every version's class has what is used:

```java
// getRenderLayerRange() returns a malilib util.LayerRange before 26.2 and a util.position.LayerRange since;
// both have isPositionWithinRange(BlockPos).
return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
```

Storing the result in a variable of the class, or passing it on, still needs `Mc.is`: written down, the class
is another one in each version.

### Access wideners, class tweakers and access transformers

To give an entry to some versions only, end it with a comment that holds `@Mc.Is` and a range. Entries
without one are for every version:

```text
classTweaker v1 official
accessible method net/minecraft/world/level/block/RedStoneWireBlock isDot (Lnet/minecraft/world/level/block/state/BlockState;)Z  # @Mc.Is("[26.1.2,26.3)")
accessible method net/minecraft/world/level/block/RedstoneWireBlock isDot (Lnet/minecraft/world/level/block/state/BlockState;)Z  # @Mc.Is("[26.3,)")
```

```text
public net.minecraft.world.level.block.RedStoneWireBlock isDot(Lnet/minecraft/world/level/block/state/BlockState;)Z # @Mc.Is("[26.1.2,26.3)")
public net.minecraft.world.level.block.RedstoneWireBlock isDot(Lnet/minecraft/world/level/block/state/BlockState;)Z # @Mc.Is("[26.3,)")
```

This works in `*.accesswidener`, `*.classtweaker`, `accesstransformer*.cfg` and `*_at.cfg` files among the resources:
`src/main/resources`, `src/<loader>/resources` or a version's own `versions/<version>/<loader>/src/main/resources`.

**Write the access changes once, in either format.** Each project gets all the files it sees, made into the format
its loader's Gradle plugin reads, and applies them to Minecraft with only its version's lines:

- **Loom** (Fabric, or common projects on Loom): `accessWidenerPath` is an access widener made from them all. The
  jar gets it under the name `fabric.mod.json` gives in `"accessWidener"`.
- **ModDevGradle** (NeoForge, or common projects on NeoForm): its access transformers are an access transformer
  made from them all. The jar gets it as `META-INF/accesstransformer.cfg`, which NeoForge reads by default.

The files themselves stay out of the jars. With several loaders, keep the file in `src/main/resources`: the common
projects (see [common.gradle](#commongradle)) and every loader then get it. Entries that only one loader needs can
go in `src/<loader>/resources`; they are added to that loader's.

| Access widener / class tweaker | Access transformer |
| --- | --- |
| `accessible class/method/field` | `public` |
| `extendable class` | `public-f` |
| `extendable method` | `protected-f` |
| `mutable field` | `private-f` (an access transformer only widens access, so `private` keeps it) |
| `inject-interface`, `extend-enum` | none: left out, with a warning |
| `accessible` (an access widener has no protected) | `protected` |
| none: left out, with a warning | `default`, `private` without `-f`, `+f` |

An access transformer names a field without its type, and `*` and `*()` stand for every field or method of a class;
an access widener names each member with its descriptor. FastMultiversion reads those from Minecraft's classes, as
Loom has them.

The made files are Loom's and ModDevGradle's by default. To write that down in a build script:

```groovy
loom {
    accessWidenerPath = multiversion.accessWidener
}
neoForge {
    accessTransformers = multiversion.accessTransformers
}
```

To give them one file as it is instead, use `multiversion.versioned`, whose path is relative to the root directory.
It filters only that file, for the version, and the jar still gets the made one:

```groovy
loom {
    accessWidenerPath = multiversion.versioned('src/fabric/resources/mymod.classtweaker')
}
```

Loom's `check` task (part of `build`) fails on an entry whose class or member a version lacks; ModDevGradle
checks access transformers only with `validateAccessTransformers`. With the IntelliJ plugin:

- An entry whose target is missing in some of its versions is reported as you type (for access transformers as a
  warning), with a fix that adds the range of the versions that have it. A range that is not valid, or matches no
  version, is reported on any line, as the build reads them all.
- `@Mc.Is`, its parentheses and the range get your color scheme's colors for a Java annotation, parentheses and
  string. Without the Minecraft Development plugin, the files open as text, and comments get the color of a Java
  comment.

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
Use it inside if (Mc.is("[26.1,26.2)")) or in a declaration with @Mc.Is("[26.1,26.2)").
```

Put such code inside `Mc.is` or `@Mc.Is` with the range the message gives.

## Working in IntelliJ IDEA

1. Open the root directory and let IntelliJ import the Gradle project.
2. The IDE plugin reads the API database in `build/multiversion/`. It runs `./gradlew generateApiDatabase`
   itself after each Gradle sync, and when the database is missing (e.g. after the build directory was
   deleted), and then checks code of other versions again. Without the plugin, run it yourself after you change
   the versions or dependencies; any build also updates it.

The IDE works with the **active version**, the newest unless you set it, and with all of its loaders at once:
`src/main` belongs to its common project and each `src/<loader>` to its project of that loader. To switch, set the
version in `gradle.properties` and reload the Gradle project:

```properties
multiversion.active=26.2
```

It can also be set with `-Pmultiversion.active=26.2` or in the `multiversion` block of `settings.gradle`.

With the FastMultiversion plugin installed:

- Code for other versions is checked against those versions. Errors in it name the version, e.g.
  `[26.1.2] Cannot resolve method 'setScren' in 'Minecraft'`.
- Go to Declaration, Quick Documentation and completion inside such code use that version's Minecraft.
- Minecraft API used in code that also runs in versions without it is reported as you type. Press
  **Alt+Enter** for the fixes:
  - Surround with `if (Mc.is(...))`
  - Annotate with `@Mc.Is(...)`
  - Import a class of another version
  - Import packages on demand
- Version ranges are checked as you type. Press **Ctrl+Space** inside a range to choose from the project's
  versions.
- An import of a class the active version does not have, or whose name an earlier import already takes, is
  shown as unused only if no version uses it.
- After a Gradle sync, run configurations of what the sync removed go: of modules that are gone, and Loom's of
  `runClient`, `runServer`, ... in projects that are no version projects (e.g. of a version the mod dropped).
  Loom and ModDevGradle never remove theirs.
- Access widener, class tweaker and access transformer entries are checked against the versions that get them.
  Without the Minecraft Development plugin, these files open as text.

## License

FastMultiversion is under the [Mozilla Public License 2.0](LICENSE): you may use, change and share it, and files
of it that you change and share stay under the same license, with their source. It does not reach the mods built
with it: `Mc` is only on their compile classpath, `Mc.is` is gone from their code once compiled, and `@Mc.Is` is
not kept in class files, so a mod can have any license.
