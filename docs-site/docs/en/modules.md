# Modules

## Module types

- IMPORT
- PROCESSING
- ALERT
- EXPORT

## Authoring workflow

1. Start from latest stable version.
2. Apply one logical change.
3. Test in non-production tenant.
4. Save new version with clear tag.
5. Promote through controlled rollout.

## Quality checklist

- clear description of behavior
- predictable defaults
- bounded timeout/retry behavior
- dependency changes documented
