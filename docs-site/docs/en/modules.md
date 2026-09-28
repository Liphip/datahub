# Modules

## Module types

| Type | Role | Forwards data | Fires alerts |
|---|---|---|---|
| IMPORT | Parses data the platform fetched via a connector | Yes | No |
| DB_LOAD | Loads rows from a platform table | Yes | No |
| PROCESSING | Transforms, filters or enriches | Yes | No |
| MERGE | Combines several upstream branches (fan-in) | Yes | No |
| ALERT | Evaluates conditions and raises alerts (built-in rule types cover most cases without code) | No | Yes |
| EXPORT | Shapes the record the platform delivers via a connector (or use "Send as is" for no code at all) | No | No |
| DB_SAVE | Prepares rows the platform writes to a table | No | No |

## A minimal PROCESSING module

```python
class ProcessingModule:
    def process(self, data: dict, query_fn=None) -> dict | None:
        data["value_c"] = (data["value_f"] - 32) * 5 / 9   # normalise units
        return data                                         # return None to drop the record
```

More complete, realistic examples (CSV parsing, a custom absence alert, shaping data for a legacy
system) are in the developer section's functional specification, §15.9, and a full multi-pipeline
example is under *Example: §14a EnWG*.

## Authoring workflow

1. Start from the latest stable version.
2. Apply one logical change.
3. Test in a non-production tenant.
4. Save as a new version with a clear description.
5. Roll out; old versions stay available and auditable.

## Quality checklist

- Predictable behaviour on unexpected input: return `None` to drop a record, don't crash the run.
- Config values come from the constructor's `config` parameter, not hard-coded.
- Dependency changes are documented in the module's requirements.
