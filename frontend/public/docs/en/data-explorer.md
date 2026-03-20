# Data Explorer

## What It Is For

Data Explorer provides quick operational data checks without leaving the platform.

## Core Features

- table selection and filtering
- time-range and row-limit querying
- summary cards (row count, min, max, avg, latest)
- trend and tag distribution charts
- CSV/JSON export

## Practical Guide

1. Select tenant and table.
2. Set time window and row limit.
3. Query and inspect summary cards.
4. Validate suspicious windows with exports.

## Data Validation Tips

- Compare summary average with expected operational baseline.
- Use tag distribution to spot location/device skew.
- Use short windows for incident triage, wider windows for trend analysis.

## Safety

- Restrict create/delete table actions to trusted operators.
- Use production change controls for destructive actions.
