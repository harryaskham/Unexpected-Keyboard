{
  description = "Unexpected Keyboard - Android virtual keyboard fork";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        jdk = pkgs.openjdk17;
        build_tools_version = "34.0.0";

        android = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ build_tools_version ];
          platformVersions = [ "35" ];
          abiVersions = [ "armeabi-v7a" ];
        };

        ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";

        gradle = pkgs.gradle.override { java = jdk; };

        # Without this option, aapt2 fails to run with a permissions error.
        gradle_wrapped = pkgs.runCommandLocal "gradle-wrapped" {
          nativeBuildInputs = [ pkgs.makeBinaryWrapper ];
        } ''
          mkdir -p $out/bin
          ln -s ${gradle}/bin/gradle $out/bin/gradle
          wrapProgram $out/bin/gradle \
            --add-flags "-Dorg.gradle.project.android.aapt2FromMavenOverride=${ANDROID_SDK_ROOT}/build-tools/${build_tools_version}/aapt2"
        '';

      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.findutils
            pkgs.fontforge
            pkgs.python3
            jdk
            android.androidsdk
            gradle_wrapped
          ];
          JAVA_HOME = jdk.home;
          inherit ANDROID_SDK_ROOT;
        };
      }
    );
}
