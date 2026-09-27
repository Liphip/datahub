/// <reference types="vite/client" />

/**
 * Defines the interface `ImportMetaEnv`, the contract implemented by this source unit.
 */
interface ImportMetaEnv {
  readonly VITE_API_URL: string;
  readonly VITE_PUBLIC_API_URL?: string;
}

/**
 * Defines the interface `ImportMeta`, the contract implemented by this source unit.
 */
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
