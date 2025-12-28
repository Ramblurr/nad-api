# nad-telnet-to-rest

A REST API bridge for NAD receivers with telnet control interfaces.
Connects to your NAD receiver over telnet and exposes a simple HTTP API for controlling power, volume, source, and other settings.

Designed for integration with Home Assistant and other home automation systems.

Tested with the NAD T778.
Should work with other NAD receivers that support telnet control.


## Quickstart

1. Create a `config.edn` with your device info:

```edn
{:nad-devices [{:name "nad-t778" ;; a url friendly name
                :host "10.9.4.12"
                :port 23}]
 :http        {:port 8002}}
```

2. Run the server:

```bash
clojure -M -m ol.nad-api
```


## How It Works

1. On startup, reads device configuration from `config.edn`
2. Connects to each NAD receiver via telnet (port 23)
3. Sends the introspection command (`?`) to discover supported commands per device
4. Generates REST API routes for each device and its supported commands
5. Exposes a web server on port 8002

- `GET /api` - List all configured devices
- `GET /api/{device}` - Device info and supported commands
- `GET /api/{device}/{command}` - Query current value
- `POST /api/{device}/{command}` - Set or modify value


## API Examples

### List All Devices

```bash
curl http://localhost:8002/api
```

Response:
```json
{
  "devices": {
    "nad-t778": {
      "host": "10.9.4.12",
      "port": 23,
      "model": "T778"
    }
  }
}
```

### Device Discovery

```bash
curl http://localhost:8002/api/nad-t778
```

Response:
```json
{
  "device": {
    "name": "nad-t778",
    "host": "10.9.4.12",
    "port": 23,
    "model": "T778"
  },
  "supportedCommands": {
    "Main.Mute": {
      "operators": ["?", "=", "+", "-"]
    },
    "Main.Power": {
      "operators": ["?", "=", "+", "-"]
    },
    "Main.Source": {
      "operators": ["?", "=", "+", "-"]
    },
    "Main.Volume": {
      "operators": ["?", "=", "+", "-"]
    }
  }
}
```

### Query Power State

```bash
curl http://localhost:8002/api/nad-t778/Main.Power
```

Response:
```json
{"command": "Main.Power", "value": "On"}
```

### Turn Power On/Off

```bash
# Turn on
curl -X POST http://localhost:8002/api/nad-t778/Main.Power \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "On"}'

# Turn off
curl -X POST http://localhost:8002/api/nad-t778/Main.Power \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "Off"}'
```

Response:
```json
{"command": "Main.Power", "operator": "=", "value": "Off"}
```

### Query Volume

```bash
curl http://localhost:8002/api/nad-t778/Main.Volume
```

Response:
```json
{"command": "Main.Volume", "value": "-48"}
```

### Adjust Volume

```bash
# Set to specific dB level
curl -X POST http://localhost:8002/api/nad-t778/Main.Volume \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "-40"}'

# Increment
curl -X POST http://localhost:8002/api/nad-t778/Main.Volume \
  -H "Content-Type: application/json" \
  -d '{"operator": "+"}'

# Decrement
curl -X POST http://localhost:8002/api/nad-t778/Main.Volume \
  -H "Content-Type: application/json" \
  -d '{"operator": "-"}'
```

### Query/Set Source

```bash
# Query current source
curl http://localhost:8002/api/nad-t778/Main.Source

# Set source by index
curl -X POST http://localhost:8002/api/nad-t778/Main.Source \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "3"}'
```

### Mute Control

```bash
# Query mute state
curl http://localhost:8002/api/nad-t778/Main.Mute

# Mute
curl -X POST http://localhost:8002/api/nad-t778/Main.Mute \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "On"}'

# Unmute
curl -X POST http://localhost:8002/api/nad-t778/Main.Mute \
  -H "Content-Type: application/json" \
  -d '{"operator": "=", "value": "Off"}'
```

### Reconnect

Force a device to disconnect and reconnect its telnet session:

```bash
curl -X POST http://localhost:8002/api/nad-t778/reconnect
```

Response:
```json
{"device": "nad-t778", "status": "reconnected", "host": "10.9.4.12", "port": 23, "model": "T778"}
```


## Operators

| Operator | Purpose | Requires Value |
|----------|---------|----------------|
| `?` | Query (GET only) | No |
| `=` | Set specific value | Yes |
| `+` | Increment / Toggle on | No |
| `-` | Decrement / Toggle off | No |


## Error Responses

| HTTP Status | Error | Description |
|-------------|-------|-------------|
| 400 | missing-operator | POST body missing `operator` field |
| 400 | invalid-operator | Operator not valid for this command |
| 400 | missing-value | Operator `=` requires a `value` field |
| 404 | not-found | Command not supported by device |
| 503 | connection-error | Cannot communicate with device |
| 504 | timeout | Device did not respond |


## Configuration

Edit `config.edn` to configure your devices:

```edn
{:nad-devices [{:name "nad-t778"
                :host "10.9.4.12"
                :port 23}
               ;; Add more devices as needed
               {:name "nad-living-room"
                :host "10.9.4.13"
                :port 23}]
 :http        {:port 8002
               :ip   "0.0.0.0"}}
```

Each device gets its own API namespace at `/api/{device-name}/`.

### Device Options

| Key           | Required | Default | Description                                     |
|---------------|----------|---------|-------------------------------------------------|
| `:name`       | Yes      | -       | Device name used in API URLs (e.g., `nad-t778`) |
| `:host`       | Yes      | -       | IP address or hostname of the NAD receiver      |
| `:port`       | No       | 23      | Telnet port (default 23)                        |
| `:timeout-ms` | No       | 2000    | Connection and read timeout in milliseconds     |

### HTTP Server Options

| Key     | Default   | Description           |
|---------|-----------|-----------------------|
| `:port` | 8002      | HTTP server port      |
| `:ip`   | "0.0.0.0" | IP address to bind to |

Additional options from [http-kit's run-server](https://cljdoc.org/d/http-kit/http-kit/2.9.0-beta3/api/org.httpkit.server#run-server) are also supported.


## Building

Build a native image for fast startup:

```bash
# Using Nix
nix build .#packages.x86_64-linux.default

# Or using Babashka
bb build
```

The binary is output to `./result/bin/nad-api`.


## Running

```bash
# Run the native image
./result/bin/nad-api

# Or start from source
clj -M -m ol.nad-api

# Or from the REPL
(require '[ol.nad-api :as api])
(api/start)
```


## License: European Union Public License 1.2

Copyright © 2025 Casey Link <unnamedrambler@gmail.com>

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
