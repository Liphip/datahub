import type { EnrichConfig, SaveMetadataMapping } from '../types';

export const DEFAULT_ENRICH: EnrichConfig = { match: { scheme: '', field: '' }, outputs: [{ as: 'metadata' }], onMissing: 'keep' };
export const DEFAULT_SAVE_METADATA: SaveMetadataMapping = { type: '', match: { scheme: '', field: '' }, attributes: '*' };

/** Problem with an enrich/saveMetadata configuration, or null when it can be saved. */
export function validateMetadataNode(enrich?: EnrichConfig, save?: SaveMetadataMapping): string | null {
  if (enrich && (!enrich.match.scheme || !enrich.match.field)) return 'Enrichment needs an identifier scheme and a record field';
  if (enrich?.outputs.some((o) => !o.as.trim())) return 'Every enrichment output needs a field name';
  if (save && (!save.type || !save.match.scheme || !save.match.field)) return 'Saving metadata needs a type, an identifier scheme and a record field';
  return null;
}

/** Comma-separated list <-> array, e.g. a relation path `market_location, location`. */
export const splitList = (text: string) => text.split(',').map((p) => p.trim()).filter(Boolean);
