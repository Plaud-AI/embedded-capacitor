import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'ai.plaud.pwademo',
  appName: 'Plaud PWA Demo',
  // Required by Capacitor even when loading a remote URL; its contents are
  // ignored at runtime because `server.url` is set below.
  webDir: 'public',
  server: {
    // The native shell loads the live Vercel site and Capacitor injects the
    // native bridge, so the BLE plugin talks to iOS CoreBluetooth.
    url: 'https://plaud-capacitor-demo.vercel.app',
    cleartext: false,
  },
};

export default config;
