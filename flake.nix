{
  description = "Reproducible Nix build for android_transcribe_app";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/release-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    rust-overlay.url = "github:oxalica/rust-overlay";
    naersk.url = "github:nix-community/naersk";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      rust-overlay,
      naersk,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ rust-overlay.overlays.default ];
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        rustToolchain = pkgs.rust-bin.stable.latest.default.override {
          targets = [ "aarch64-linux-android" ];
        };

        naerskLib = naersk.lib.${system}.override {
          cargo = rustToolchain;
          rustc = rustToolchain;
        };

        app = pkgs.callPackage ./nix/android-transcribe-app.nix {
          inherit naerskLib rustToolchain;
          cargoNdk = pkgs.cargo-ndk;
        };
      in
      {
        packages = {
          default = app.apk;
          apk = app.apk;
          rustLib = app.rustLib;
          modelAssets = app.modelAssets;
          gradleDeps = app.gradleDeps;
          gradleDepsUpdateScript = app.gradleDepsUpdateScript;
        };

        devShells.default = app.shell;
      }
    );
}
