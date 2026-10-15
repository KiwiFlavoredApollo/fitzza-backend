# Crawler to Jev contract

The AI data pipeline runs in this order:

```text
Crawler -> Jev color normalization -> FashionCLIP embedding -> Qdrant upsert
```

Jev receives text context rather than image bytes. The crawler output must therefore preserve:

- `product_id`
- `name`
- the depth-1 category name or `dataset.logical_category`
- every `color_image_mappings[].color_raw`
- the verified `color_image_mappings[].image_url` used for human review
- all sibling colors on the same product

`scripts/prepare_jev_input.py` validates these fields and groups every occurrence of the same raw color name into one deterministic record. It rejects invalid image URLs before any paid Jev request is made.

## Local usage

Use the crawler's qualified product `data/` directory, not candidate or audit output.

```powershell
python scripts/prepare_jev_input.py `
  --source .data/color-qualified-dataset-500/data `
  --output .data/pipeline/jev/input.json
```

The output contains the contract version, source counts, and sorted color records. Each color record contains every product context needed to build the Jev choice prompt and the image URL needed when a low-confidence result is reviewed.

## S3 run layout

Each execution uses an immutable run ID:

```text
runs/<run-id>/crawler/data/**/*.json
runs/<run-id>/crawler/report.json
runs/<run-id>/jev/input.json
runs/<run-id>/jev/color_map.json
runs/<run-id>/jev/color_map_review.csv
runs/<run-id>/jev/_SUCCESS.json
runs/<run-id>/fashion-clip/report.json
```

FashionCLIP/Qdrant must not start until the Jev `_SUCCESS.json` exists. Jev credentials belong in AWS Secrets Manager and must never be written to S3, Kubernetes manifests, or logs.
