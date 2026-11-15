# Icon generator
Run `python3 tools/icons/make_icons.py` from the repo root to regenerate the GUI icons (16x16 RGBA) in `src/main/resources/assets/coppergolemsrevamped/textures/item/gui/`.
Each icon is built from character-grid sprites and a color palette in the script; the PNGs may also be replaced by hand.
The tab-bar icons (`icon_back`, `icon_status`, `icon_inventory`, `icon_settings`) and `craft_arrow` were drawn for the GUI simulator and ported 1:1 (pixel-identical to the simulator PNGs); `held_empty` is gone with the held-item cell.
