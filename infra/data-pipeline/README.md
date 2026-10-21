# Crawler to Jev contract

Cloud deployment and execution are separate. See [DEPLOYMENT.md](DEPLOYMENT.md) for the suspended Jobs, approved bundle contract and masked SSM key registration. The examples below describe the earlier local workflow, not permission to run collection or indexing.

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
python -X utf8 .data/fashion-clip/scripts/generate_color_map.py `
  --data .data/color-qualified-dataset-500/data `
  --config .data/fashion-clip/config `
  --workers 4
```

Remove the key when finished or close the terminal:

```powershell
Remove-Item Env:TYPESAFE_API_KEY
```

This session-only method is for local execution. The cloud deployment reads its key from SSM SecureString `/fitzza/prod/data-pipeline/typesafe-api-key` only after separate execution approval. Do not create a new Secrets Manager resource for this pipeline.

## Local FashionCLIP indexing

Run the batch from the local FashionCLIP checkout after `color_map.json` is generated. UTF-8 mode prevents Windows PowerShell's legacy console encoding from failing on Korean output or emoji.

```powershell
$env:PYTHONUTF8 = "1"
Push-Location .data/fashion-clip
.\.venv\Scripts\python.exe scripts\index_to_qdrant.py
Pop-Location
```

The current script recreates the local `musinsa_products` collection. Verify completion in the dashboard or with `GET http://localhost:6333/collections/musinsa_products`; `points_count` must be greater than zero.

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

The layout above is historical. The deployed cloud contract uses `pipeline/runs/<run-id>/` and requires human color approval plus a separate matching dataset-quality approval; a Jev `_SUCCESS.json` alone is not sufficient. Jev credentials belong in SSM SecureString and must never be written to S3, Kubernetes manifests, or logs.

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
