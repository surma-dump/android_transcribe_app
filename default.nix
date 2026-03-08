let
	nixpkgs = builtins.getFlake "github:nixos/nixpkgs/release-25.11";
	pkgs = import nixpkgs {};
	inherit (pkgs) mkShell buildEnv;

	shell = mkShell {
		packages = with pkgs; [
			jdk21_headless
			sdkmanager
		];
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
