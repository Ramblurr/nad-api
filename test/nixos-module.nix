{
  self,
  pkgs,
  nixpkgs,
}:

let
  inherit
    (import (nixpkgs + "/nixos/lib/testing-python.nix") {
      inherit pkgs;
      system = pkgs.system;
    })
    makeTest
    ;
in
makeTest {
  name = "nad-api module test";

  nodes.machine =
    { pkgs, ... }:
    {
      imports = [ self.nixosModules.default ];

      # Test creates the user/group (module doesn't manage users)
      users.users.nad-api = {
        isSystemUser = true;
        group = "nad-api";
        description = "NAD API service user";
      };
      users.groups.nad-api = { };

      services.nad-api = {
        enable = true;
        package = self.packages.${pkgs.system}.default;
        devices = [
          {
            name = "test-nad";
            host = "127.0.0.1";
            port = 2323;
          }
        ];
        http.port = 8002;
      };

      # Mock NAD server - accepts connections, sends model, echoes responses
      systemd.services.mock-nad =
        let
          mockScript = pkgs.writeShellScript "mock-nad" ''
            # Send model on connect
            printf "\nMain.Model=T778\r"
            # Read and respond to commands
            while IFS= read -r line; do
              printf "\nMain.Power=On\r"
            done
          '';
        in
        {
          description = "Mock NAD receiver for testing";
          wantedBy = [ "multi-user.target" ];
          before = [ "nad-api.service" ];
          serviceConfig = {
            Type = "simple";
            ExecStart = "${pkgs.socat}/bin/socat TCP-LISTEN:2323,reuseaddr,fork EXEC:${mockScript}";
            Restart = "always";
          };
        };
    };

  testScript = ''
    start_all()

    # Wait for mock NAD server
    machine.wait_for_unit("mock-nad.service")
    machine.wait_for_open_port(2323)

    # Wait for nad-api service
    machine.wait_for_unit("nad-api.service")
    machine.wait_for_open_port(8002)

    # Verify config file exists
    machine.succeed("test -f /etc/nad-api/config.edn")
    machine.succeed("grep -q 'test-nad' /etc/nad-api/config.edn")

    # Test API root endpoint
    result = machine.succeed("curl -s http://localhost:8002/api")
    assert "test-nad" in result, f"Expected 'test-nad' in response, got: {result}"

    # Test device discovery endpoint
    result = machine.succeed("curl -s http://localhost:8002/api/test-nad")
    assert "T778" in result, f"Expected 'T778' model in response, got: {result}"

    print("All tests passed!")
  '';
}
