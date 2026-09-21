/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_RCM_API_BASE?: string
  readonly VITE_RCM_CENTER_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
