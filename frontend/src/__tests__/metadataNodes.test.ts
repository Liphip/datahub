import { describe, expect, it } from 'vitest';
import { DEFAULT_ENRICH, DEFAULT_SAVE_METADATA, splitList, validateMetadataNode } from '../lib/metadataNodes';

describe('metadata pipeline steps', () => {
  it('rejects incomplete configurations', () => {
    expect(validateMetadataNode(DEFAULT_ENRICH)).toMatch(/scheme/);
    expect(validateMetadataNode(undefined, DEFAULT_SAVE_METADATA)).toMatch(/type/);
    expect(validateMetadataNode({ ...DEFAULT_ENRICH, match: { scheme: 'lorawan.devEUI', field: 'devEUI' }, outputs: [{ as: ' ' }] })).toMatch(/output/);
  });

  it('accepts complete configurations', () => {
    expect(validateMetadataNode({ ...DEFAULT_ENRICH, match: { scheme: 'lorawan.devEUI', field: 'devEUI' } })).toBeNull();
    expect(validateMetadataNode(undefined, { type: 'meter', match: { scheme: 'sap.equipment', field: 'EQUNR' } })).toBeNull();
    expect(validateMetadataNode()).toBeNull();
  });

  it('splits relation paths', () => {
    expect(splitList('market_location, location ,')).toEqual(['market_location', 'location']);
  });
});
