let
  nixpkgs = builtins.getFlake "github:nixos/nixpkgs/release-25.11";
  rustOverlay = import (builtins.getFlake "github:oxalica/rust-overlay").outPath;

  pkgs = import nixpkgs {
    overlays = [ rustOverlay ];
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };

  rustToolchain = pkgs.rust-bin.stable.latest.default.override {
    targets = [ "aarch64-linux-android" ];
  };

  naerskLib = pkgs.callPackage (builtins.getFlake "github:nix-community/naersk").outPath {
    cargo = rustToolchain;
    rustc = rustToolchain;
  };

  app = pkgs.callPackage ./nix/android-transcribe-app.nix {
    inherit naerskLib rustToolchain;
    cargoNdk = pkgs.cargo-ndk;
  };
in
{
  inherit (app)
    apk
    rustLib
    modelAssets
    gradleDeps
    gradleDepsUpdateScript
    shell
    ;
}
