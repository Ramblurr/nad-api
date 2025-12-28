{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    clj-nix.url = "github:jlesquembre/clj-nix";

  };
  outputs =
    inputs@{
      self,
      devenv,
      devshell,
      clj-nix,
      ...
    }:
    devenv.lib.mkFlake ./. {
      inherit inputs;
      systems = [ "x86_64-linux" ];
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = rec {
        graalvm-ce = pkgs: pkgs.graalvmPackages.graalvm-ce;
        nad-api =
          pkgs:
          clj-nix.lib.mkCljApp {
            inherit pkgs;
            modules = [
              {
                projectSrc = ./.;
                name = "io.github.ramblurr/nad-api";
                version = "0.2";
                main-ns = "ol.nad-api";
                nativeImage.enable = true;
                # customJdk.enable = true;
              }
            ];
          };
        default = nad-api;
      };
      nixosModule = ./module.nix;
      checks = {
        nixos-module =
          pkgs:
          (import ./test/nixos-module.nix {
            inherit self pkgs;
            nixpkgs = devenv.inputs.nixpkgs;
          });
      };

      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];
          # https://numtide.github.io/devshell
          commands = [
            { package = pkgs.netcat-gnu; }
          ];

        };
    };
}
