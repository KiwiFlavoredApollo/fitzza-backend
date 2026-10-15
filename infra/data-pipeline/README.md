# Crawler to Jev contract

The AI data pipeline runs in this order:

```text
Crawler -> Jev color normalization -> FashionCLIP embedding -> Qdrant upsert
```

The FashionCLIP Jev implementation reads the crawler's product JSON directory directly. It uses:

- `name`
- `dataset.logical_category`
- every `color_image_mappings[].color_raw`
- all sibling colors on the same product

`scripts/validate_jev_data.py` validates the files before any paid Jev request. Color keys follow the Jev implementation's `strip().lower()` rule. It does not create an intermediate input file.

## Local usage

Use the crawler's qualified product `data/` directory, not candidate or audit output.

```powershell
python scripts/validate_jev_data.py `
  --source .data/color-qualified-dataset-500/data
```

After validation, run `fashion-CLIP/scripts/generate_color_map.py` with the same directory as `--data`. The implementation reference is the `feat/color-map-jev` branch; pin its reviewed commit before deployment because it is not merged into `main`.

## Jev API key on Windows

Do not paste the key into source files, Compose YAML, shell history, screenshots, or chat. In PowerShell, enter it as a masked value for the current terminal session:

```powershell
$secureKey = Read-Host "TYPESAFE_API_KEY" -AsSecureString
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try {
  $env:TYPESAFE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
}
```

Confirm only whether it is set; never print the value:

```powershell
if ($env:TYPESAFE_API_KEY) { "TYPESAFE_API_KEY is set" } else { "TYPESAFE_API_KEY is missing" }
```

Run Jev from the repository root in the same terminal:

```powershell
python .data/fashion-clip/scripts/generate_color_map.py `
  --data .data/color-qualified-dataset-500/data `
  --config .data/fashion-clip/config `
  --workers 4
```

Remove the key when finished or close the terminal:

```powershell
Remove-Item Env:TYPESAFE_API_KEY
```

This session-only method is recommended for local execution. Store the deployment key in AWS Secrets Manager and inject it only into the Jev batch job at runtime.

## S3 run layout

Each execution uses an immutable run ID:

```text
runs/<run-id>/crawler/data/**/*.json
runs/<run-id>/crawler/report.json
runs/<run-id>/jev/color_map.json
runs/<run-id>/jev/color_map_review.csv
runs/<run-id>/jev/_SUCCESS.json
runs/<run-id>/fashion-clip/report.json
```

FashionCLIP/Qdrant must not start until the Jev `_SUCCESS.json` exists. Jev credentials belong in AWS Secrets Manager and must never be written to S3, Kubernetes manifests, or logs.

## Local Qdrant

Qdrant runs in Docker; the crawler validation and Jev scripts run as Python batch jobs.

```powershell
docker compose -f infra/data-pipeline/docker-compose.yml up -d
docker compose -f infra/data-pipeline/docker-compose.yml ps
```

Open `http://localhost:6333/dashboard` for the Qdrant dashboard. Stop it without deleting indexed data:

```powershell
docker compose -f infra/data-pipeline/docker-compose.yml down
```

### Postman checks

- `GET http://localhost:6333/collections`: Qdrant connection and collection list
- `GET http://localhost:6333/collections/musinsa_products`: collection status after indexing
- `POST http://localhost:6333/collections/musinsa_products/points/scroll`: inspect indexed payloads

Use this JSON body for the scroll request:

```json
{
  "limit": 3,
  "with_payload": true,
  "with_vector": false
}
```

Jev and FashionCLIP are batch scripts, not HTTP APIs, so they are verified through generated files and logs rather than Postman. The current FashionCLIP script recreates `musinsa_products`; use it only against this local Qdrant instance until that behavior is made configurable.
