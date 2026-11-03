# Hub Connection Flow (Desktop Issue #40)

## Overview
The Hub connection screen allows users to discover and connect to classroom Hubs via mDNS/UDP discovery or manual IP entry. Once connected, the app monitors the connection and shows a status banner.

## Components

### ConnectionPage (`src/pages/ConnectionPage.tsx`)
The main connection screen shown when no Hub is connected.

**Features:**
- Auto-discovery of Hubs via `observeDiscovery()`
- Manual IP address entry with validation
- Connection probe to verify Hub reachability
- Auto-reconnect to last connected Hub on app start
- Real-time status banner feedback

**Props:**
- `t: Dictionary` - Translations
- `onConnected: (address: string, hubName: string) => void` - Callback when connection succeeds

**State Flow:**
1. On mount: attempts auto-reconnect to `getLastHubConnection()`
2. Observes discovery for available Hubs
3. User selects Hub or enters manual address
4. Probes Hub via `/download` endpoint (HEAD request, 5s timeout)
5. If reachable: saves connection, notifies parent via `onConnected`

### HubStatusBanner (`src/components/HubStatusBanner.tsx`)
Status banner component showing Hub connection state.

**Variants:**
- `success` (green) - Connected to Hub
- `warning` (amber) - Searching or no Hubs found
- `danger` (red) - Connection failed or error
- `info` (gray) - General information

**Props:**
- `t: Dictionary` - Translations
- `variant: BannerVariant` - Visual style
- `hubName?: string` - Hub name for connected state
- `message?: string` - Override default message

## Services

### Database (`src/services/db.ts`)
**New functions:**
- `saveHubConnection(address: string)` - Records connection in `hub_connections` table
- `getLastHubConnection()` - Retrieves most recently connected Hub
- `updateHubConnectionSeen(address: string)` - Updates `last_seen_at` timestamp

**Schema (`migrations/002_hub_connections.sql`):**
```sql
CREATE TABLE hub_connections (
    hub_address TEXT PRIMARY KEY,
    connected_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);
```

### Discovery (`src/services/discovery.ts`)
**New function:**
- `probeHub(address: string)` - Validates Hub reachability
  - GET request to `/download` endpoint (HEAD not supported by mock Hub)
  - 5-second timeout via AbortController
  - Returns `{ reachable: boolean; name?: string }`
  - Fetches Hub name from discovery if available

## App Integration (`src/App.tsx`)

**Changes:**
1. **Removed language toggle** (per English-only architectural rule)
2. **Conditional rendering:**
   - No connection: show simplified shell + `ConnectionPage`
   - Connected: show full app with `HubStatusBanner`
3. **Connection monitoring:**
   - Observes discovery to detect if connected Hub disappears
   - Updates `last_seen_at` while Hub is reachable
   - Returns to `ConnectionPage` if Hub goes offline

**State:**
```typescript
const [connectedHub, setConnectedHub] = useState<{
  address: string;
  name: string;
} | null>(null);
```

## UI States

### Not Connected
- Simplified top bar with offline pill
- ConnectionPage showing:
  - Discovery panel with auto-found Hubs
  - Manual IP entry field
  - Status banner (info/warning)

### Connected
- Full app shell with drawer navigation
- Green "Connected" pill in top bar showing Hub name
- Success banner in content area
- Classes list (from local SQLite)

### Connection States
| State | Banner Variant | Message |
|-------|---------------|---------|
| Searching | info | "Searching for classroom Hubs..." |
| Hubs Found | info | "Hubs found on your network" |
| No Hubs | warning | "No Hubs found. You can enter an address manually." |
| Connecting | info | "Connecting to Hub..." |
| Connected | success | "Connected to {Hub Name}" |
| Failed | danger | "Could not reach the Hub. Check the address and try again." |
| Error | danger | "Connection error. Please try again." |

## Acceptance Criteria (from Issue #40)

✅ **Discovered Hubs appear within 3 seconds**
- `observeDiscovery()` uses 1s polling + `hub-found` events
- 3s timeout via `setTimeout(() => setSearching(false), 3000)`

✅ **Manual IP entry with validation**
- Regex: `^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$`
- Private IP validation (10.x, 172.16-31.x, 192.168.x, 127.x)
- Port range 1-65535

✅ **Status banner shows connection state**
- 4 variants (success, warning, danger, info)
- Icon + text, accessible with `role="status"` and `aria-label`

✅ **Against mock_hub.py, Hub appears and banner shows "Connected to Local Hub"**
- `probeHub()` validates via HEAD `/download`
- Hub name fetched from discovery beacon
- Success banner shows hub name

## Testing

### Manual Test Steps
1. **Start Mock Hub:**
   ```bash
   python3 scripts/mock_hub.py
   ```

2. **Launch Desktop App:**
   ```bash
   cd desktop
   npm run tauri dev
   ```

3. **Verify Auto-Discovery:**
   - Hub should appear in list within 3 seconds
   - Shows Hub name and IP:port
   - "Connect" button enabled

4. **Test Manual Entry:**
   - Enter valid IP (e.g., `192.168.1.50:8080`)
   - Click "Connect"
   - Banner should show success

5. **Test Invalid IP:**
   - Enter invalid IP (e.g., `999.999.999.999`)
   - Should show error message

6. **Test Connection:**
   - Select discovered Hub or enter manual address
   - Banner changes to "Connecting to Hub..."
   - On success: shows "Connected to {name}"
   - Hub pill in top bar turns green

7. **Test Auto-Reconnect:**
   - Close and reopen app
   - Should automatically reconnect to last Hub

8. **Test Disconnect:**
   - Stop Mock Hub
   - App should detect loss and return to ConnectionPage

## Files Changed
- ✅ `desktop/src/pages/ConnectionPage.tsx` (created)
- ✅ `desktop/src/components/HubStatusBanner.tsx` (created)
- ✅ `desktop/src/services/discovery.ts` (extended with `probeHub`)
- ✅ `desktop/src/services/db.ts` (extended with connection tracking)
- ✅ `desktop/src/App.tsx` (conditional rendering, removed language toggle)
- ✅ `desktop/src/store/app.ts` (removed `toggleLanguage`)
- ✅ `desktop/src/i18n/en.json` (added connection strings)
- ✅ `desktop/src/index.css` (added banner and connection styles)
- ✅ `desktop/src-tauri/migrations/002_hub_connections.sql` (created)
- ✅ `desktop/docs/connection.md` (this file)

## Language Note
Per architectural decision (2026-11-03), the apps are **English-only**. Filipino strings were NOT added despite Issue #40 sub-task 5 requesting them. The language toggle was removed from the UI, though the i18n infrastructure remains for potential future use.

## Design Tokens Used
- Buttons: 40px min-height (per Google Classroom standard)
- Hub pill: 10px dot, border-radius 9999px (full round)
- Banner colors:
  - Success: `--color-lara-success-*`
  - Warning: `--color-lara-warning-*`
  - Danger: `--color-lara-danger-*`
  - Info: `--color-lara-subtle` / `--color-lara-border`
- Icons: Phosphor (`ph-broadcast`, `ph-wifi-high`, `ph-wifi-slash`, `ph-check-circle`, `ph-warning`, `ph-x-circle`, `ph-info`)
