import { describe, expect, it, vi } from 'vitest';

vi.mock('../services/api', () => ({
  default: {
    createSecret: vi.fn(async (_t: string, name: string) => ({ id: `id-${name.split('.')[1]}`, name, version: 1 })),
    rotateSecret: vi.fn(async (_t: string, id: string) => ({ id, version: 2 })),
  },
}));

import api from '../services/api';
import { hasSecret, secretName, withSecretRefs } from '../lib/secrets';

describe('secret references in forms', () => {
  it('moves credentials into secrets and keeps other fields', async () => {
    const config = await withSecretRefs('t', { url: 'https://x', authToken: 'abc' }, ['authToken'], undefined, 'Alert hook');
    expect(config).toEqual({ url: 'https://x', secretRefs: { authToken: { id: 'id-authToken', version: 1 } } });
    expect(api.createSecret).toHaveBeenCalledWith('t', expect.stringMatching(/^Alert-hook\.authToken\.[a-z0-9]+$/), 'abc');
  });

  it('keeps the stored secret when the field is left empty and rotates on a new value', async () => {
    const existing = { secretRefs: { authToken: { id: 's1', version: 1 } } };
    expect(await withSecretRefs('t', { authToken: '' }, ['authToken'], existing, 'x')).toEqual(existing);
    expect(await withSecretRefs('t', { authToken: 'new' }, ['authToken'], existing, 'x'))
      .toEqual({ secretRefs: { authToken: { id: 's1', version: 2 } } });
    expect(hasSecret(existing, 'authToken')).toBe(true);
  });

  it('builds valid secret names', () => {
    expect(secretName('9 Wärme', 'password')).toMatch(/^W-rme\.password\.[a-z0-9]+$/);
  });
});
