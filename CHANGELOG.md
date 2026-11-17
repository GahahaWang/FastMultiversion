## [1.1.0](https://github.com/GahahaWang/FastMultiversion/compare/v1.0.0...v1.1.0) (2026-11-17)

### ✨ New Features

* Accept a call whose result is used at once although its class differs between versions ([855953b](https://github.com/GahahaWang/FastMultiversion/commit/855953bf8ea7533da792b854aaafbf2a3373273f))
* Check access widener and transformer entries against their versions in the IDE ([046784a](https://github.com/GahahaWang/FastMultiversion/commit/046784a74ffa1fc3e00ee61de277a220a165a1a4))
* Choose the license of a new mod, and its optional settings ([3c4face](https://github.com/GahahaWang/FastMultiversion/commit/3c4faceb3a20bc8953485801e0e3f1d027f96fc8))
* Choose the main loader of a new mod for Fabric and NeoForge ([20f1231](https://github.com/GahahaWang/FastMultiversion/commit/20f1231515872642fe11732cd2cdb3c6a5fa2caa))
* Give access changes to some versions, written once for every loader ([6a10be4](https://github.com/GahahaWang/FastMultiversion/commit/6a10be4c2d575500be73e3eee68c5b27caa4598f))
* Lay out a new mod for one loader in src/main without loader projects ([52971cc](https://github.com/GahahaWang/FastMultiversion/commit/52971ccc3677e262306a9af477b98cd4e33ea113))
* Look up the newest Loom and ModDevGradle, and remember what was read for offline ([55f1177](https://github.com/GahahaWang/FastMultiversion/commit/55f117722ef02b0424454b532ce3928b4405283b))
* Make run configurations for every version and loader, and remove stale ones ([12e47ca](https://github.com/GahahaWang/FastMultiversion/commit/12e47ca14d35fe24d8d6abb94e6f3c2a1fe25117))
* Publish other pushes to dev as pre-releases on a dev channel ([3bb1e21](https://github.com/GahahaWang/FastMultiversion/commit/3bb1e2178d41a77237593b5ea6055e2d414ac0f1))
* Report imports that no Minecraft version uses ([fd27db5](https://github.com/GahahaWang/FastMultiversion/commit/fd27db54ac927e51fcbda467c5672f505bbdf4de))
* Start a new mod with an empty file of access changes and an empty mixin package ([a5ed0f2](https://github.com/GahahaWang/FastMultiversion/commit/a5ed0f291504b95e643a63902c1bf2a1b4b4f9e5))

### 🐛 Bug Fixes

* Color names only other versions have in active code ([6f37811](https://github.com/GahahaWang/FastMultiversion/commit/6f3781174f707dae648e31ad9b2dc5aafad05b24))
* Go to classes of later imports that another version has beside the first ([b0d8e2d](https://github.com/GahahaWang/FastMultiversion/commit/b0d8e2d542226b6218419ea2448d8c3dc426b1fc))
* Go to the declaration of names only other versions have ([410ea54](https://github.com/GahahaWang/FastMultiversion/commit/410ea549fc7992a6151ecaf507633125b5eef162))
* Leave a chain alone while the call after it is being written ([7d98dbd](https://github.com/GahahaWang/FastMultiversion/commit/7d98dbd703e5505d6f169aa1e966c4f1bb7257ff))
* Let the first import win where a version has both classes of a name ([b30b0af](https://github.com/GahahaWang/FastMultiversion/commit/b30b0af61f1986eed75c201acfd855986ece2ace))

## [1.0.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.7.1...v1.0.0) (2026-11-16)

### ⚠ BREAKING CHANGES

* Replace @IfMc with @Mc.Is

### ✨ New Features

* List the wizard under Generators and choose Minecraft versions from Mojang's list ([cf71fd3](https://github.com/GahahaWang/FastMultiversion/commit/cf71fd3de1004d2e1e6bef56fe46e6ebca26fa42))
* Replace @IfMc with @Mc.Is ([a785feb](https://github.com/GahahaWang/FastMultiversion/commit/a785feb794d2b40f9c49f3ee010a807a93787bdd))

## [0.7.1](https://github.com/GahahaWang/FastMultiversion/compare/v0.7.0...v0.7.1) (2026-11-15)

### 🐛 Bug Fixes

* Require an IDE restart to install or update the plugin ([f88a29c](https://github.com/GahahaWang/FastMultiversion/commit/f88a29c1b5697c3066b0fca6e656f0c2e2c9ebeb))

## [0.7.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.6.1...v0.7.0) (2026-11-15)

### ✨ New Features

* Update the API database after each Gradle sync ([4564fff](https://github.com/GahahaWang/FastMultiversion/commit/4564fffb973f618439d99fa16385013b55c7ad49))

## [0.6.1](https://github.com/GahahaWang/FastMultiversion/compare/v0.6.0...v0.6.1) (2026-11-14)

### 🐛 Bug Fixes

* Color names of other versions with the editor's color scheme ([4f72aea](https://github.com/GahahaWang/FastMultiversion/commit/4f72aeafe43eb5a1a0f8abb7aa9fa1f87897a009))

## [0.6.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.5.0...v0.6.0) (2026-11-14)

### ✨ New Features

* Color names in code of other versions as the IDE does ([73a2acf](https://github.com/GahahaWang/FastMultiversion/commit/73a2acf03dc552236f9a227f88fd519465620b0c))

## [0.5.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.4.0...v0.5.0) (2026-11-14)

### ✨ New Features

* Serve the Gradle plugin from a Maven repository on GitHub Pages ([7f4ab08](https://github.com/GahahaWang/FastMultiversion/commit/7f4ab080ac26d145c68065963f1d12444f09b295))

## [0.4.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.3.0...v0.4.0) (2026-11-14)

### ✨ New Features

* Let IntelliJ IDEA update the plugin from GitHub Pages ([380f88c](https://github.com/GahahaWang/FastMultiversion/commit/380f88c9f8150769ca21422384a4f2926505c943))

## [0.3.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.2.0...v0.3.0) (2026-11-14)

### ✨ New Features

* Add a New Project wizard for FastMultiversion mods ([a7c435d](https://github.com/GahahaWang/FastMultiversion/commit/a7c435defb0918b0be57d0c7a64bba176a01b484))

## [0.3.0-dev.1](https://github.com/GahahaWang/FastMultiversion/compare/v0.2.0...v0.3.0-dev.1) (2026-11-14)

### ✨ New Features

* Add a New Project wizard for FastMultiversion mods ([a7c435d](https://github.com/GahahaWang/FastMultiversion/commit/a7c435defb0918b0be57d0c7a64bba176a01b484))

## [0.2.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.1.0...v0.2.0) (2026-11-14)

### ✨ New Features

* Let the IDE resolve the shared build scripts ([40e52ea](https://github.com/GahahaWang/FastMultiversion/commit/40e52ea8afef32264143df3d7fd9992c80f9c55f))

## [0.2.0-dev.1](https://github.com/GahahaWang/FastMultiversion/compare/v0.1.0...v0.2.0-dev.1) (2026-11-14)

### ✨ New Features

* Let the IDE resolve the shared build scripts ([40e52ea](https://github.com/GahahaWang/FastMultiversion/commit/40e52ea8afef32264143df3d7fd9992c80f9c55f))

## [0.1.0](https://github.com/GahahaWang/FastMultiversion/compare/v0.0.0...v0.1.0) (2026-11-14)

### ✨ New Features

* First release of FastMultiversion ([76b4905](https://github.com/GahahaWang/FastMultiversion/commit/76b4905a09444fd0c1aa2e9e29779ff0ce0f0c8f))

## [0.1.0-dev.1](https://github.com/GahahaWang/FastMultiversion/compare/v0.0.0...v0.1.0-dev.1) (2026-11-14)

### ✨ New Features

* First release of FastMultiversion ([76b4905](https://github.com/GahahaWang/FastMultiversion/commit/76b4905a09444fd0c1aa2e9e29779ff0ce0f0c8f))
