# Mahi Remote Control - Web Controller UI

Open `index.html` in a browser. No build step, no dependencies.

    index.html        markup for pairing, remote view, drawers, modal
    css/tokens.css    design tokens (colors, radii, fonts)
    css/base.css      reset, typography, focus, scrollbars
    css/components.css  buttons, cards, dock, drawer, states
    js/app.js         interactions + integration hooks

Demo: any 6 digits connect; `000000` shows the pairing error.
Preview loading/error/screen-off states in Settings > Preview screen state.
Hooks: `pair()` (signalling) and the canvas `pointerdown` handler (data channel) in js/app.js.
Shortcut: Ctrl+Shift+X opens the emergency stop confirmation.
