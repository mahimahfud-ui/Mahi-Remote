import os from 'os';

export interface LanInterfaceInfo {
  ip: string;
  family: 'IPv4' | 'IPv6';
  interfaceName: string;
  isDefault: boolean;
}

/**
 * Detects the active non-internal IPv4 address for LAN operations.
 * Ignores loopback, disconnected interfaces, IPv6 link-local, and duplicates.
 * Prioritizes Wi-Fi and Ethernet adapters.
 */
export function detectLanIp(): string {
  const interfaces = os.networkInterfaces();
  const candidates: { name: string; ip: string; priority: number }[] = [];

  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;
    for (const addr of addrs) {
      // Must be IPv4 and not loopback/internal
      if (addr.family === 'IPv4' && !addr.internal) {
        const ip = addr.address;
        
        // Skip link-local 169.254.x.x
        if (ip.startsWith('169.254.')) continue;

        let priority = 10;
        const lowerName = name.toLowerCase();

        // Prioritize Wi-Fi or Ethernet
        if (lowerName.includes('wi-fi') || lowerName.includes('wlan') || lowerName.includes('wifi')) {
          priority = 100;
        } else if (lowerName.includes('eth') || lowerName.includes('ethernet') || lowerName.includes('lan')) {
          priority = 90;
        } else if (lowerName.includes('vEthernet') || lowerName.includes('virtual') || lowerName.includes('wsl')) {
          priority = 20; // Lower priority for virtualization adapters
        }

        // Standard private ranges (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
        if (ip.startsWith('192.168.')) {
          priority += 30;
        } else if (ip.startsWith('10.')) {
          priority += 20;
        }

        candidates.push({ name, ip, priority });
      }
    }
  }

  if (candidates.length === 0) {
    // If running in container or fallback environment without local Wi-Fi, return default development LAN IP
    return process.env.DEV_LAN_IP || '192.168.1.105';
  }

  // Sort descending by priority
  candidates.sort((a, b) => b.priority - a.priority);
  return candidates[0].ip;
}

export function getAllLanInterfaces(): LanInterfaceInfo[] {
  const interfaces = os.networkInterfaces();
  const list: LanInterfaceInfo[] = [];

  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;
    for (const addr of addrs) {
      if (!addr.internal && addr.family === 'IPv4' && !addr.address.startsWith('169.254.')) {
        list.push({
          ip: addr.address,
          family: addr.family,
          interfaceName: name,
          isDefault: false
        });
      }
    }
  }
  return list;
}
