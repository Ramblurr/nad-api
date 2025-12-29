{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.nad-api;

  # Helper function to convert Nix expression to EDN via JSON -> jet
  nixToEdn =
    name: value:
    pkgs.runCommand "${name}.edn" { nativeBuildInputs = [ pkgs.jet ]; } ''
      echo '${builtins.toJSON value}' | jet --from json --to edn --keywordize > $out
    '';

  # Build the config structure expected by nad-api
  configData = {
    nad-devices = map (
      device:
      {
        inherit (device) name host;
        port = device.port;
      }
      // lib.optionalAttrs (device.timeout-ms != null) {
        timeout-ms = device.timeout-ms;
      }
    ) cfg.devices;

    http = {
      inherit (cfg.http) port;
      ip = cfg.http.ip;
    }
    // cfg.http.extraOptions;
  };

  configFile = nixToEdn "nad-api-config" configData;

  deviceSubmodule = lib.types.submodule {
    options = {
      name = lib.mkOption {
        type = lib.types.str;
        description = "URL-friendly name for the device (used in API paths).";
        example = "nad-t778";
      };

      host = lib.mkOption {
        type = lib.types.str;
        description = "IP address or hostname of the NAD receiver.";
        example = "10.9.4.12";
      };

      port = lib.mkOption {
        type = lib.types.port;
        default = 23;
        description = "Telnet port for the NAD receiver.";
      };

      timeout-ms = lib.mkOption {
        type = lib.types.nullOr lib.types.int;
        default = null;
        description = "Connection and read timeout in milliseconds.";
        example = 2000;
      };
    };
  };
in
{
  options.services.nad-api = {
    enable = lib.mkEnableOption "NAD receiver REST API bridge";

    package = lib.mkPackageOption pkgs "nad-api" { };

    devices = lib.mkOption {
      type = lib.types.listOf deviceSubmodule;
      default = [ ];
      description = "List of NAD receiver devices to control.";
      example = lib.literalExpression ''
        [
          {
            name = "nad-t778";
            host = "10.9.4.12";
            port = 23;
          }
          {
            name = "nad-living-room";
            host = "10.9.4.13";
          }
        ]
      '';
    };

    http = {
      port = lib.mkOption {
        type = lib.types.port;
        default = 8002;
        description = "HTTP server port.";
      };

      ip = lib.mkOption {
        type = lib.types.str;
        default = "0.0.0.0";
        description = "IP address to bind the HTTP server to.";
      };

      extraOptions = lib.mkOption {
        type = lib.types.attrsOf lib.types.unspecified;
        default = { };
        description = ''
          Extra options passed to http-kit's run-server.
          See https://cljdoc.org/d/http-kit/http-kit/2.9.0-beta3/api/org.httpkit.server#run-server
        '';
        example = lib.literalExpression ''
          {
            thread = 4;
            queue-size = 20480;
          }
        '';
      };
    };

    user = lib.mkOption {
      type = lib.types.str;
      default = "nad-api";
      description = "User account under which nad-api runs.";
    };

    group = lib.mkOption {
      type = lib.types.str;
      default = "nad-api";
      description = "Group under which nad-api runs.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open the HTTP port in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.devices != [ ];
        message = "services.nad-api.devices must contain at least one device.";
      }
    ];

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.http.port ];

    systemd.services.nad-api = {
      description = "NAD Receiver REST API Bridge";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" ];

      serviceConfig = {
        Type = "simple";
        User = cfg.user;
        Group = cfg.group;
        ExecStart = "${cfg.package}/bin/nad-api";
        ConfigurationDirectory = "nad-api";
        Restart = "always";
        RestartSec = 2;
        # Hardening
        CapabilityBoundingSet = "";
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        ProtectHome = true;
        ProtectSystem = "strict";
        ProtectControlGroups = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        MemoryDenyWriteExecute = false; # JVM needs this
      };

      serviceConfig.ExecStartPre = [
        "+${pkgs.coreutils}/bin/install -m 0600 -o ${cfg.user} -g ${cfg.group} ${configFile} /etc/nad-api/config.edn"
      ];
    };
  };
}
