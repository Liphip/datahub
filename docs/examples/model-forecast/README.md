# Example: forecasting load with a model trained inside the platform

A substation operator wants the load of the next hour, for every hour of the day, without running
a separate machine-learning service. A PROCESSING module fits a model to historical readings and
saves it; the platform stores it, versions it and hands it back to later runs.

**No ML server, no model registry outside the platform.** The model is state the platform writes
and reads for a module's own use — the one documented exception to "structured data only"
(`DAT-10`, functional spec §5.6).

The module and both pipeline definitions in this directory are executed end to end by
`backend/src/test/kotlin/com/dataturntable/examples/ModelForecastExampleTest.kt`, so this example
is kept in step with the product. The test needs a real Docker daemon, because a PROCESSING module
runs in a container.

## The situation

| | |
|---|---|
| **Training** | Nightly. One record per station and day: that day's hourly readings. |
| **Inference** | Continuous. One record per new hourly reading. |
| **Result** | The load forecast for the following hour, delivered to a forecast sink. |

A real installation fits a richer model — scikit-learn or PyTorch, from the platform wheel cache
(`MOD-06`) — and the same two functions apply. This example uses ordinary least squares in plain
Python so the arithmetic is checkable by hand and needs no wheels.

## Files

| File | What it is |
|---|---|
| `load-forecast-module.py` | The PROCESSING module. Fits a line, or applies the saved line. |
| `load-training.pipeline.json` | Nightly: IMPORT → PROCESSING (fit and save) → EXPORT. |
| `load-inference.pipeline.json` | Per reading: IMPORT → PROCESSING (apply) → EXPORT. |

Both pipelines run **the same module version**. That is deliberate, and it is the one constraint
worth understanding before building your own: a model artifact is **module-scoped**, so a module can
only load a model that the same module saved. One module with two roles — decide by the shape of the
record — is how a training pipeline and an inference pipeline share a model today. See
"Known limits" below for what changes if you want two separate modules.

`${forecastModuleVersionId}`, `${trainingConnectorId}` and `${forecastConnectorId}` are placeholders;
the test substitutes real ids, and so would the pipeline editor or an API script.

## How the module tells the two roles apart

A PROCESSING module runs **once per record** and returns one result, so a training batch arrives as
a single record carrying a list of readings rather than as many records:

```json
{ "station": "DRD-17", "date": "2026-09-27",
  "readings": [ { "hour": 0, "loadKwh": 100.0 }, { "hour": 1, "loadKwh": 110.0 }, … ] }
```

Anything without a `readings` list is a single new reading and is answered with a forecast:

```json
{ "station": "DRD-17", "hour": 6, "loadKwh": 160.0 }
```

If your readings arrive one per record — from MQTT, say — aggregate them first (a `DB_LOAD` node and
SQL, or an upstream summary feed) and feed the batch. The platform has no windowing operator.

## What the module does

```python
if input_data.get("readings"):          # training batch
    model = _fit(input_data["readings"])
    save_model("load-forecast", model)
    …
else:                                  # a single reading
    model = load_model("load-forecast")
    if model is None:
        return {"modelState": "missing"}   # report, do not fail
    …
```

Two functions are injected into the module, alongside the existing `alert_fn` and `query_fn`:

- **`save_model_fn(name, model)`** pickles and base64-encodes the object. The platform stores it
  encrypted at rest in `model_artifacts`, exactly like a secret version, and **creates a new version
  rather than overwriting** — the same rule as module and pipeline versions.
- **`load_model_fn(name)`** returns the **latest** version. It is read from a snapshot taken before
  the container starts, so there is no per-record database round trip.

Returning a `dict` is what lets a module save a model: the platform reads the saved models back
after the container exits, stores them, and strips them from the result before it reaches the next
node. A module whose `process()` does not return a `dict` gets a clear warning on stderr instead of
silently losing the model.

## What a training run reports

`loadKwh = 100 + 10 × hour` fits exactly, so the reported coefficients are exact and checkable:

```json
{ "role": "train", "station": "DRD-17", "date": "2026-09-27", "observations": 6,
  "slope": 10.0, "intercept": 100.0 }
```

## What an inference run delivers

For hour 6 the forecast is `10 × 6 + 100 = 160`:

```json
{ "role": "infer", "station": "DRD-17", "hour": 6.0, "loadKwh": 160.0,
  "forecastNextHourKwh": 160.0, "modelState": "applied" }
```

The delivery carries an `Idempotency-Key` (run and node), like every other output, so a consumer can
discard a repeat after a failover.

## Retraining and hot-swap

Running the training pipeline again saves **version 2** instead of overwriting version 1. The
inference pipeline is not changed, redeployed or restarted: its next execution preloads the new
version and applies it. The example asserts this — after retraining on a steeper line
(`loadKwh = 120 + 12 × hour`) the same inference pipeline forecasts 192 for hour 6.

On one instance a save invalidates that module's cached snapshot immediately, so even the very next
execution sees it. Other instances pick a save up within
`dataturntable.execution.model-artifact-cache-seconds` (default 60 s) — the same
instant-local/eventual-remote pattern already used for ingest API keys.

## Before the first training run

The inference pipeline reports `modelState: "missing"` and still delivers, rather than failing the
run. A pipeline that starts before the first nightly run should not push its records onto the
dead-letter queue. A training run that cannot fit — one reading, or all readings at the same hour —
fails loudly, and nothing is saved or delivered.

## Known limits

- **Module-scoped.** A different module cannot load the model. Sharing across modules would mean
  scoping artifacts by tenant and name instead, which is a deliberate isolation decision, not an
  oversight (`MOD-15` says "the same module or a different one"; only the first half works today).
- **No version pinning from inside a module.** `load_model_fn(name, version=…)` raises
  `NotImplementedError` in the module, which fails the run visibly. A module process has no live
  channel back to the platform while it runs, so it can only be handed what was preloaded. Pin an
  old model from a tool that already knows the version, not from module code.
- **A run records which version it created, not which it read** (`MOD-16`, Partial). For
  reproducibility, keep the training input that produced each version.
- **No artifact browser yet.** There is no UI page to list versions; the Python API is the interface
  for now.
- **Size.** The whole artifact is preloaded into the container environment for every execution, so
  it suits model parameters, not model weights of hundreds of megabytes.
