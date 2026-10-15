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
