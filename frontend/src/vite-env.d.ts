/// <reference types="vite/client" />

/**
 * Defines the interface `ImportMetaEnv`, the contract implemented by this source unit.
 */
interface ImportMetaEnv {
  readonly VITE_API_URL: string;
  // TODO: Declare every supported frontend API environment variable
  // Details: api.ts also reads VITE_PUBLIC_API_URL, but it is absent here. Add its optional type (and document its build-time behavior) so configuration changes remain discoverable and type-checked.
}

/**
 * Defines the interface `ImportMeta`, the contract implemented by this source unit.
 */
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
