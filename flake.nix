{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    clj-nix.url = "github:jlesquembre/clj-nix";

  };
  outputs =
    {
      self,
      devenv,
      devshell,
      clj-nix,
      ...
    }:
    devenv.lib.mkFlake ./. {
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = {
        graalvm-ce = pkgs: pkgs.graalvmPackages.graalvm-ce;
        default =
          pkgs:
          clj-nix.lib.mkCljApp {
            inherit pkgs;
            modules = [
              {
                projectSrc = ./.;
                name = "io.github.ramblurr/nad-api";
                main-ns = "ol.nad-api";
                nativeImage.enable = true;
                # customJdk.enable = true;
              }
            ];
          };
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
            { package = pkgs.cfssl; }
            { package = pkgs.pebble; }
          ];

        };
    };
}
