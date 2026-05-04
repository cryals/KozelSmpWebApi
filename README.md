# Kozel SMP Web API

Paper/Leaf plugin by AL-S for the Kozel SMP website.

The plugin starts a small HTTP API and returns player stats as JSON.

## Endpoints

`GET /players`

Returns:

- current online player count
- max server player count
- all online players sorted by nickname
- player groups: `overworld`, `nether`, `end`, `other`

`GET /health`

Returns:

```json
{"status":"ok"}
```

## Example

```json
{
  "online": 3,
  "max": 100,
  "updatedAt": 1777900000000,
  "players": {
    "all": {
      "count": 3,
      "list": []
    },
    "overworld": {
      "count": 1,
      "list": []
    },
    "nether": {
      "count": 1,
      "list": []
    },
    "end": {
      "count": 1,
      "list": []
    },
    "other": {
      "count": 0,
      "list": []
    }
  }
}
```

Each player item contains:

```json
{
  "name": "Alex",
  "uuid": "00000000-0000-0000-0000-000000000000",
  "world": "world",
  "dimension": "overworld"
}
```

## Config

After first launch, edit:

```text
plugins/KozelSmpWebApi/config.yml
```

```yaml
host: "127.0.0.1"
port: 8087
token: ""
update-interval-ticks: 20
```

If `token` is empty, the API is public.

If `token` is set:

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:8087/players
```

or:

```bash
curl -H "X-API-Key: YOUR_TOKEN" http://127.0.0.1:8087/players
```

## Build

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The jar will be here:

```text
build/libs/KozelSmpWebApi-1.0.0.jar
```

Put the jar into the server `plugins` directory and restart the server.

## Releases

GitHub Actions builds and publishes a release:

- on every push to `main`
- every day at 09:00 Moscow time
- manually from the Actions tab

## License

Mozilla Public License Version 2.0.
