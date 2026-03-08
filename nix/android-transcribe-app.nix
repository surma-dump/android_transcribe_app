{
  lib,
  stdenv,
  stdenvNoCC,
  runCommand,
  fetchurl,
  gradle,
  jdk21_headless,
  mkShell,
  sdkmanager,
  androidenv,
  unzip,
  cargoNdk,
  curl,
  naerskLib,
  rustToolchain,
}:

let
  ndkVersion = "29.0.14206865";
  projectSrc = lib.cleanSource ../.;

  androidComposition = androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "34.0.0" "35.0.0" ];
    abiVersions = [ "arm64-v8a" ];
    ndkVersions = [ ndkVersion ];
    includeNDK = true;
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
  };

  androidSdk = androidComposition.androidsdk;
  androidHome = "${androidSdk}/libexec/android-sdk";
  androidNdk = "${androidHome}/ndk/${ndkVersion}";

  onnxruntimeAar = fetchurl {
    url = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar";
    hash = "sha256-BKRhepx5fPSSJVleRbVUYIHLNMhqyBdYEUFXfTt9v+I=";
  };

  ortExtracted = stdenvNoCC.mkDerivation {
    pname = "onnxruntime-android-extracted";
    version = "1.22.0";
    src = onnxruntimeAar;
    nativeBuildInputs = [ unzip ];
    dontUnpack = true;
    installPhase = ''
      runHook preInstall
      mkdir -p "$out"
      unzip -q "$src" -d "$out"
      runHook postInstall
    '';
  };

  modelAssets =
    let
      modelBase = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main";
      fetchModel = file: hash: fetchurl {
        url = "${modelBase}/${file}?download=true";
        inherit hash;
      };

      configJson = fetchModel "config.json" "sha256-ZmkDx2uXmMrywhCv1PbNYLCKjb+YAOyNejvA0hSKxGY=";
      vocabTxt = fetchModel "vocab.txt" "sha256-1YVEZ56kvGrFY9H1Ret9R0vWz6Rn8KbiwdwcfTfjw10=";
      encoder = fetchModel "encoder-model.int8.onnx" "sha256-YTnS+n4bCGCXsnfHFJcl7bq4nMfHrmSyPHQb5AVa/wk=";
      decoder = fetchModel "decoder_joint-model.int8.onnx" "sha256-7qdIPuPRowN12u3I7YPjlgyRsJiBISeg2Z0ciXdmenA=";
      nemo = fetchModel "nemo128.onnx" "sha256-qf3hSG6/zAjzKNda1GEMZ4Nf6ljHO6V+Mgmm9s8Bnp8=";
      modelDir = "parakeet-tdt-0.6b-v3-int8";
    in
    runCommand "android-transcribe-model-assets" { } ''
      set -euo pipefail

      appDir="$out/app/${modelDir}"
      packDir="$out/model_pack/${modelDir}"

      mkdir -p "$appDir" "$packDir"
      ln -s ${configJson} "$appDir/config.json"
      ln -s ${vocabTxt} "$appDir/vocab.txt"

      ln -s ${encoder} "$packDir/encoder-model.int8.onnx"
      ln -s ${decoder} "$packDir/decoder_joint-model.int8.onnx"
      ln -s ${nemo} "$packDir/nemo128.onnx"
    '';

  rustLib = naerskLib.buildPackage {
    pname = "android-transcribe-rust-lib";
    version = "0.1.0";
    src = projectSrc;

    copyBins = false;
    copyLibs = false;
    doCheck = false;

    CARGO_BUILD_TARGET = "aarch64-linux-android";
    ORT_STRATEGY = "system";
    ORT_PREFER_DYNAMIC_LINK = "1";
    ORT_LIB_LOCATION = "${ortExtracted}/jni/arm64-v8a";
    ORT_INCLUDE_DIR = "${ortExtracted}/headers";

    cargoBuildOptions = opts:
      opts
      ++ [
        "--target"
        "aarch64-linux-android"
        "--lib"
      ];

    preBuild = ''
      hostToolchain="$(find ${androidNdk}/toolchains/llvm/prebuilt -mindepth 1 -maxdepth 1 -type d | head -n1)"

      clang="$hostToolchain/bin/aarch64-linux-android26-clang"
      clangxx="$hostToolchain/bin/aarch64-linux-android26-clang++"
      ar="$hostToolchain/bin/llvm-ar"
      ranlib="$hostToolchain/bin/llvm-ranlib"

      export PATH="$hostToolchain/bin:$PATH"
      export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$clang"
      export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$ar"

      export CC_aarch64_linux_android="$clang"
      export CXX_aarch64_linux_android="$clangxx"
      export AR_aarch64_linux_android="$ar"
      export RANLIB_aarch64_linux_android="$ranlib"
    '';

    postInstall = ''
      libDir="$out/lib/arm64-v8a"
      mkdir -p "$libDir"

      cp "target/aarch64-linux-android/release/libandroid_transcribe_app.so" "$libDir/"

      hostToolchain="$(find ${androidNdk}/toolchains/llvm/prebuilt -mindepth 1 -maxdepth 1 -type d | head -n1)"
      cp "$hostToolchain/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$libDir/"
    '';
  };

  apkAndDeps =
    rec {
        apk = stdenv.mkDerivation {
          pname = "android-transcribe-app";
          version = "0.1.0";
          src = projectSrc;

          nativeBuildInputs = [
            gradle
            jdk21_headless
          ];

          mitmCache = gradleDeps;
          __darwinAllowLocalNetworking = true;

          dontUseGradleCheck = true;
          gradleBuildTask = "assembleRelease";
          gradleUpdateTask = "assembleRelease";
          gradleFlags = [
            "-x"
            "downloadModels"
            "-x"
            "cargoNdkBuild"
            "-Dorg.gradle.java.home=${jdk21_headless}"
          ];

          preBuild = ''
            set -euo pipefail

            export HOME="$TMPDIR"
            export _JAVA_OPTIONS="-Duser.home=$TMPDIR ''${_JAVA_OPTIONS-}"
            export GRADLE_USER_HOME="$TMPDIR/gradle-home"
            export ANDROID_HOME="${androidHome}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export ANDROID_NDK_HOME="${androidNdk}"
            mkdir -p "$HOME/.android"

            cat > local.properties <<EOF
            sdk.dir=$ANDROID_HOME
            EOF

            cp ${./release.keystore} release.keystore
            export STORE_PASS=android
            export KEY_ALIAS=release
            export KEY_PASS=android

            modelDir="parakeet-tdt-0.6b-v3-int8"

            mkdir -p "app/src/main/jniLibs/arm64-v8a"
            ln -sf ${rustLib}/lib/arm64-v8a/libandroid_transcribe_app.so "app/src/main/jniLibs/arm64-v8a/libandroid_transcribe_app.so"
            ln -sf ${rustLib}/lib/arm64-v8a/libc++_shared.so "app/src/main/jniLibs/arm64-v8a/libc++_shared.so"

            mkdir -p "app/src/main/assets/$modelDir"
            ln -sf ${modelAssets}/app/$modelDir/config.json "app/src/main/assets/$modelDir/config.json"
            ln -sf ${modelAssets}/app/$modelDir/vocab.txt "app/src/main/assets/$modelDir/vocab.txt"

            mkdir -p "model_assets/src/main/assets/$modelDir"
            ln -sf ${modelAssets}/model_pack/$modelDir/encoder-model.int8.onnx "model_assets/src/main/assets/$modelDir/encoder-model.int8.onnx"
            ln -sf ${modelAssets}/model_pack/$modelDir/decoder_joint-model.int8.onnx "model_assets/src/main/assets/$modelDir/decoder_joint-model.int8.onnx"
            ln -sf ${modelAssets}/model_pack/$modelDir/nemo128.onnx "model_assets/src/main/assets/$modelDir/nemo128.onnx"
          '';

          installPhase = ''
            runHook preInstall
            mkdir -p "$out"
            cp app/build/outputs/apk/release/app-release.apk "$out/app-release.apk"

            mkdir -p "$out/nix-support"
            echo "file binary-dist $out/app-release.apk" > "$out/nix-support/hydra-build-products"
            runHook postInstall
          '';
        };

        gradleDeps = gradle.fetchDeps {
          pkg = apk;
          attrPath = null;
          data = ./gradle-deps.json;
        };
      };
in
{
  inherit (apkAndDeps)
    apk
    gradleDeps
    ;
  inherit
    rustLib
    modelAssets
    ;

  gradleDepsUpdateScript = apkAndDeps.gradleDeps.passthru.updateScript;

  shell = mkShell {
    packages = [
      rustToolchain
      cargoNdk
      jdk21_headless
      gradle
      curl
      sdkmanager
      androidSdk
    ];

    shellHook = ''
      export JAVA_HOME="${jdk21_headless}"
      export ANDROID_HOME="${androidHome}"
      export ANDROID_SDK_ROOT="$ANDROID_HOME"
      export ANDROID_NDK_HOME="${androidNdk}"
      export ANDROID_NDK="$ANDROID_NDK_HOME"
      export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME ''${GRADLE_OPTS-}"

      if [ ! -f local.properties ]; then
        cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
EOF
      fi

      mkdir -p "$HOME/.android"
    '';
  };
}
