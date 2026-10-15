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
