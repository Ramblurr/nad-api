# Home Assistant Usage

[nad-api](../README.md) was created primarily to expose my NAD receiver to Home Assistant.

HA has two pre-existing integrations that *could* be used, but in my experience neither work wrll.

- [Telnet](https://www.home-assistant.io/integrations/telnet/) - I could not get the `value_template` / `command_state` working with my NAD. Furthermore this integration uses the deprecated telnetlib python module, so it's probably going to be removed.
- [NAD](https://www.home-assistant.io/integrations/NAD) - Does not work reliably. The entity goes offline for days at a time.

Notably I do not need a `media_player` entity for my NAD reciever, the Bluesound and Roon integrations take care of that. I need controls for power (main zone and zone 2) and volume, etc.
And I need it to work reliably!

I don't want to create or maintain a new custom integration. Rather we lean on the following builtin HA integrations:

- [Restful Switch](https://www.home-assistant.io/integrations/switch.rest/)
- [RESTful binary sensor](https://www.home-assistant.io/integrations/binary_sensor.rest)
- [RESTful sensor](https://www.home-assistant.io/integrations/sensor.rest)
- [RESTful command](https://www.home-assistant.io/integrations/rest_command)


Here is my config:

```yaml
# NAD Receiver REST Integration
# Assumes nad-api is running at http://nad-api.lan:8002
# Device name: nad-t778

# Power switches (Main and Zone2)
switch:
  - platform: rest
    name: "NAD Main Power"
    unique_id: nad_t778_main_power
    resource: http://nad-api.lan:8002/api/nad-t778/Main.Power
    body_on: '{"operator": "=", "value": "On"}'
    body_off: '{"operator": "=", "value": "Off"}'
    is_on_template: "{{ value_json.value == 'On' }}"
    headers:
      Content-Type: application/json

  - platform: rest
    name: "NAD Zone2 Power"
    unique_id: nad_t778_zone2_power
    resource: http://nad-api.lan:8002/api/nad-t778/Zone2.Power
    body_on: '{"operator": "=", "value": "On"}'
    body_off: '{"operator": "=", "value": "Off"}'
    is_on_template: "{{ value_json.value == 'On' }}"
    headers:
      Content-Type: application/json

  - platform: rest
    name: "NAD Main Mute"
    unique_id: nad_t778_main_mute
    resource: http://nad-api.lan:8002/api/nad-t778/Main.Mute
    body_on: '{"operator": "=", "value": "On"}'
    body_off: '{"operator": "=", "value": "Off"}'
    is_on_template: "{{ value_json.value == 'On' }}"
    headers:
      Content-Type: application/json

  - platform: rest
    name: "NAD Zone2 Mute"
    unique_id: nad_t778_zone2_mute
    resource: http://nad-api.lan:8002/api/nad-t778/Zone2.Mute
    body_on: '{"operator": "=", "value": "On"}'
    body_off: '{"operator": "=", "value": "Off"}'
    is_on_template: "{{ value_json.value == 'On' }}"
    headers:
      Content-Type: application/json

# Volume and Source sensors
sensor:
  - platform: rest
    name: "NAD Main Volume"
    unique_id: nad_t778_main_volume
    resource: http://nad-api.lan:8002/api/nad-t778/Main.Volume
    value_template: "{{ value_json.value }}"
    unit_of_measurement: "dB"

  - platform: rest
    name: "NAD Zone2 Volume"
    unique_id: nad_t778_zone2_volume
    resource: http://nad-api.lan:8002/api/nad-t778/Zone2.Volume
    value_template: "{{ value_json.value }}"
    unit_of_measurement: "dB"

  - platform: rest
    name: "NAD Main Source"
    unique_id: nad_t778_main_source
    resource: http://nad-api.lan:8002/api/nad-t778/Main.Source
    value_template: "{{ value_json.value }}"

  - platform: rest
    name: "NAD Zone2 Source"
    unique_id: nad_t778_zone2_source
    resource: http://nad-api.lan:8002/api/nad-t778/Zone2.Source
    value_template: "{{ value_json.value }}"

# REST commands for setting volume
rest_command:
  nad_main_volume_set:
    url: http://nad-api.lan:8002/api/nad-t778/Main.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "=", "value": "{{ volume }}"}'

  nad_main_volume_up:
    url: http://nad-api.lan:8002/api/nad-t778/Main.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "+"}'

  nad_main_volume_down:
    url: http://nad-api.lan:8002/api/nad-t778/Main.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "-"}'

  nad_zone2_volume_set:
    url: http://nad-api.lan:8002/api/nad-t778/Zone2.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "=", "value": "{{ volume }}"}'

  nad_zone2_volume_up:
    url: http://nad-api.lan:8002/api/nad-t778/Zone2.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "+"}'

  nad_zone2_volume_down:
    url: http://nad-api.lan:8002/api/nad-t778/Zone2.Volume
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "-"}'

  nad_main_source_set:
    url: http://nad-api.lan:8002/api/nad-t778/Main.Source
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "=", "value": "{{ source }}"}'

  nad_zone2_source_set:
    url: http://nad-api.lan:8002/api/nad-t778/Zone2.Source
    method: POST
    headers:
      Content-Type: application/json
    payload: '{"operator": "=", "value": "{{ source }}"}'
```

Usage examples for automations:

```yaml
# Set main volume to -40 dB
action: rest_command.nad_main_volume_set
data:
  volume: "-40"

# Increase main volume
action: rest_command.nad_main_volume_up

# Set source to index 3
action: rest_command.nad_main_source_set
data:
  source: "3"
```

