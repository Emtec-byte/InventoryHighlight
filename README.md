# RuneLite Inventory Highlighter

A lightweight RuneLite plugin that highlights specified items in your inventory with customizable colors and styles.

## Features

- Highlight specific items in your inventory with customizable colors
- Wildcard matching support (e.g., `rune*` matches all rune items)
- Multiple highlight styles (outline, fill, or both)
- Choose between item sprite or full slot highlighting
- Preset support for quick configuration changes

## Quick Start

1. Install via RuneLite Plugin Hub
2. Configure items to highlight (comma-separated)
3. Customize colors and style preferences
4. Optional: Save commonly used lists as presets

## Examples

### Basic Items
```
Coins, rune scimitar, Lobster
```

### Using Wildcards
```
rune*, dragon*, *potion*
```

## Tips

- Names are not case-sensitive
- Use commas to separate items
- Add * for wildcards (e.g., `angler*` matches all anglerfish)
- Hover-only mode reduces visual clutter
- Use presets to store frequently used configurations

## Support

Avoid turning off "Hover Only" in experimental, this feature does work but has more render calls so may cause performance issues on some machines.

For issues or suggestions, please report through the RuneLite GitHub repository.

Created by Cheese cake