let
	nixpkgs = builtins.getFlake "github:nixos/nixpkgs/release-25.11";
	pkgs = import nixpkgs {};
	inherit (pkgs) mkShell buildEnv;

	shell = mkShell {
		packages = with pkgs; [
			jdk21_headless
			sdkmanager
		];

		shellHook = ''
			mkdir -p .adh
			export ANDROID_HOME="$PWD/.adh"
			export ANDROID_SDK_ROOT="$PWD/.adh"
			export ANDROID_NDK_HOME="${env}/libexec/android-sdk/ndk/29.0.14206865"
	  '';

	};
	
	env = buildEnv {
		name = "env";
		paths = with pkgs; [
			androidenv.androidPkgs.platform-tools
			androidenv.androidPkgs.androidsdk
			androidenv.androidPkgs.build-tools
			androidenv.androidPkgs.ndk-bundle
		];
	};
in
{ inherit env shell; }
