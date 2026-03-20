# Beginner Guide (No Prior Experience Required)

This guide is for users who are new to data platforms.

## What is DataTurntable in simple terms?

Think of DataTurntable as a factory for data:

- **Connectors** are the doors where data enters/leaves.
- **Modules** are machines that transform data.
- **Pipelines** are conveyor belts connecting machines.
- **Alerts** tell you when something goes wrong.
- **Data Explorer** lets you inspect produced data.

## Your first day plan

### Step 1: Log in and pick the right tenant

Always check the tenant selector in the top bar. Most "missing data" issues are just wrong tenant context.

### Step 2: Open Dashboard

Look for:

- big error spikes
- pipelines stuck in failed/running state
- warnings about API connection

### Step 3: Create a safe test workflow

1. Create a test module.
2. Create a test pipeline.
3. Run it manually once.
4. Verify output in Data Explorer.

### Step 4: Read logs if something fails

In Grafana Logs, filter by service and time window of the failure.

## Common terms you will see

- **Tenant**: isolated workspace/team/environment
- **Run**: one pipeline execution
- **Latency**: how long a run/request takes
- **Throughput**: how much data is processed
- **Retry**: automatic second attempt after failure

## Safe habits for beginners

- Work in `dev` tenant first.
- Version before you change behavior.
- Save notes: what changed, why, expected impact.
- Ask for review before production changes.

## Typical mistakes and quick fixes

### "I cannot see a button someone else sees"

Your role permissions differ. Ask tenant admin to compare abilities.

### "Pipeline run failed immediately"

Usually connector credentials or module config mismatch.

### "Data is empty"

Check tenant, time range, table, and read permission.

## Help template (copy/paste)

When asking support, include:

- tenant name/id
- pipeline/module name
- exact timestamp with timezone
- screenshot of error
- what you already tried
