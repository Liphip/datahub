import api from '../services/api';
import type { SecretReference } from '../types';

type Refs = Record<string, { id: string; version?: number }>;

function isRefs(value: unknown): value is Refs {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  return Object.entries(value).every(([key, raw]) => {
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key) || typeof raw !== 'object' || raw === null || Array.isArray(raw)) return false;
    const ref = raw as Record<string, unknown>;
    return typeof ref.id === 'string' && ref.id.length > 0
      && Object.keys(ref).every((name) => name === 'id' || name === 'version')
      && (ref.version === undefined || (typeof ref.version === 'number' && Number.isInteger(ref.version) && ref.version > 0));
  });
}

/**
 * Replaces the credential fields of a connector or alert-channel config by secret references:
 * the backend stores credentials only as secrets. A new value creates a secret (or a new
 * version of the field's existing secret); an empty value keeps the existing reference.
 */
export async function withSecretRefs(
  tenantId: string,
  config: Record<string, unknown>,
  sensitiveKeys: string[],
  existing: Record<string, unknown> | undefined,
  namePrefix: string,
): Promise<Record<string, unknown>> {
  const result = { ...config };
  const stored = existing?.secretRefs;
  const invalidStored = stored !== undefined && !isRefs(stored);
  if (invalidStored && !sensitiveKeys.some((key) => typeof result[key] === 'string' && result[key] !== '')) {
    throw new Error('Stored secret references are invalid. Re-enter and save the connector credentials.');
  }
  const refs: Refs = isRefs(stored) ? { ...stored } : {};
  delete result.secretRefs;
  for (const key of sensitiveKeys) {
    const value = result[key];
    delete result[key];
    if (typeof value !== 'string' || value === '') continue;
    const current = refs[key];
    const ref: SecretReference = current
      ? await api.rotateSecret(tenantId, current.id, value)
      : await api.createSecret(tenantId, secretName(namePrefix, key), value);
    refs[key] = { id: ref.id, version: ref.version };
  }
  if (Object.keys(refs).length > 0) result.secretRefs = refs;
  return result;
}

/** Secret names: letter first, then letters, digits, `_.-`; unique per tenant. */
export function secretName(prefix: string, key: string): string {
  const base = `${prefix}.${key}`.replace(/[^A-Za-z0-9_.-]/g, '-').replace(/^[^A-Za-z]+/, '');
  return `${base || 'secret'}.${Math.random().toString(36).slice(2, 8)}`.slice(0, 128);
}

/** True when the field has a stored secret (shown as "stored, leave empty to keep"). */
export function hasSecret(config: Record<string, unknown> | undefined, key: string): boolean {
  return Boolean((config?.secretRefs as Refs | undefined)?.[key]);
}
