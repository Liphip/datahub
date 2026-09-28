# Example: meter readings for §14a EnWG grid calculation

§14a EnWG (BNetzA decision BK6-22-300) is used here as a **worked example**. Nothing in the
platform is specific to it: the same building blocks serve any case where readings from one system
need master data from others before they go to several consumers.

The pipelines in this directory are executed end to end by
`backend/src/test/kotlin/com/dataturntable/examples/Energy14aExampleTest.kt`, so this example is
kept in step with the product.

## The situation

| Source | Delivers | Typical input |
|---|---|---|
| LoRaWAN network server | Readings per device (`devEUI`, `time`, `energyKwh`) | MQTT or HTTP push, fire-and-forget |
| SAP IS-U | Meters (`EQUNR`, `SERNR`, manufacturer, phases), their metering location (MeLo) and market location (MaLo) | Nightly CSV export to an SFTP or FTP inbox, or by mail |
| Internal register | Location (site) and grid area per market location | CSV in a directory inbox, or pushed to the ingest API |
| Device registry | Which device reads which meter, from when | Installation app, pushed to the ingest API |

| Consumer | Needs |
|---|---|
| Metering system | Each reading with meter, MaLo and location |
| Grid calculation | Each reading with the grid area (e.g. the local substation) |

## Metadata model (template `energy`)

```text
lorawan_device --meter--> meter --metering_location--> metering_location
   (devEUI)          (meter_number, sap.equipment)         (MeLo)
                                                               |
                                                        market_location
                                                            (MaLo) --location--> location --grid_area--> grid_area
```

Identifiers are validated: a DevEUI has 16 hex digits, a MeLo matches `DE` plus 31 characters, a
MaLo has 11 digits. Every identifier, attribute and relation has a validity period. A meter
exchange is therefore a new relation from its date on: readings from before the exchange still
resolve to the old meter.

## Pipelines

1. **`sap-meters.pipeline.json`** saves meters (matched by `sap.equipment`, also known by
   `meter_number`) with their MeLo, and each MeLo with its MaLo. `AB` (valid from) comes from SAP.
2. **`internal-locations.pipeline.json`** saves each MaLo's location and each location's grid area.
3. **`lorawan-devices.pipeline.json`** links each device to its meter from `installedAt` on.
4. **`lorawan-readings.pipeline.json`** runs these steps:
   - Deduplicates readings by `devEUI` and `time`, so a gateway that sends twice is counted once.
   - Enriches each reading with the metadata valid at the reading's `time`.
   - Sends the result unchanged ("Send as is") to two REST output connectors.

   A reading from an unknown device fails its run (`onMissing: fail`). The run keeps its input and
   can be retried once the device is registered, so no reading is lost or billed without a meter.

Master data can arrive in any order: relations to objects that do not exist yet create them, and
their attributes are filled in when their own source delivers.

`${meteringConnectorId}` and `${gridConnectorId}` in the readings pipeline are the ids of the two
REST output connectors. The master-data pipelines take their input from the ingest API; attach an
inbox connector (FILE, FTP/FTPS, SFTP, IMAP) to their IMPORT node to read SAP exports directly.

## What an enriched reading looks like

```json
{
  "devEUI": "70B3D57ED0000001",
  "time": "2026-07-01T12:00:00Z",
  "energyKwh": 1301.25,
  "meter": { "_type": "meter", "_name": "1ESY1160000002", "manufacturer": "Landis+Gyr", "phases": 3,
             "ids": { "meter_number": "1ESY1160000002", "sap.equipment": "10000002" } },
  "marketLocation": { "_type": "market_location", "direction": "consumption", "ids": { "malo": "51238696781" } },
  "location": { "_type": "location", "street": "Hauptstr. 1", "postalCode": "01067", "city": "Dresden" },
  "gridArea": { "_type": "grid_area", "ids": { "grid_area_id": "ONS-Dresden-17" } }
}
```

The connectors deliver with an `Idempotency-Key` (run and node), so a receiver can discard a
repeat after a failover.

## What §14a needs beyond this example

The example covers data collection, enrichment and distribution. A §14a-compliant control process
also needs parts outside the data hub or not built yet. From BK6-22-300, Annex 1:

- Grid-state data at minute resolution from at least 15 % of the grid connections in the area.
- A control action within 5 minutes, which never reduces a controllable device below 4.2 kW.
- Documentation of every action, kept for at least two years.
- Monthly publication by the 15th.

The data hub supplies the enriched time series and the audit trail. The grid-state estimation,
the control decision and the control channel to the devices (e.g. via the smart meter gateway)
are separate systems.
