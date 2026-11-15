#!/usr/bin/env python3
"""Generates the 16x16 GUI icons (run from repo root): python3 tools/icons/make_icons.py

Each icon is a 16x16 canvas composed by stamping small character-grid sprites ('.' = transparent).
Characters map to colors via PAL below. Output is deterministic (byte-identical on every run).
"""
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                   "src", "main", "resources", "assets", "coppergolemsrevamped",
                   "textures", "item", "gui")

PAL = {
    # copper ramp
    "k": "3A1D12", "K": "5A2E1E", "d": "8C4A30", "c": "C15A36", "C": "E77C56", "h": "FBB497",
    # oxidized accent
    "g": "3B8B78", "G": "5BC1A4",
    # wood
    "u": "2E2214", "w": "4C3A22", "W": "6B5132", "v": "9C7A4A", "V": "B8945F",
    # green
    "n": "2F6B26", "N": "4C9A2A", "M": "76C043",
    # red
    "r": "8E1C1C", "e": "B52D2D", "R": "D23B3B", "Q": "F27A6E", "F": "FBE3DC",
    # wheat
    "y": "8A6B1F", "Y": "C9A43A", "Z": "E8D06A",
    # blue (zzz, lapis)
    "B": "9FC4FF", "b": "5A82D0", "L": "2F4FA8", "l": "6E96F0",
    # greys
    "1": "3E3E3E", "2": "6B6B6B", "3": "8F8F8F",
    # light greys (craft arrow)
    "4": "B4B4B4", "5": "DADADA", "6": "FFFFFF",
}


def blank():
    return [["."] * 16 for _ in range(16)]


def stamp(cv, x0, y0, sprite):
    for dy, row in enumerate(sprite):
        for dx, ch in enumerate(row):
            if ch != ".":
                x, y = x0 + dx, y0 + dy
                assert 0 <= x < 16 and 0 <= y < 16, (x, y)
                cv[y][x] = ch


def recolor(sprite, m):
    return ["".join(m.get(c, c) for c in r) for r in sprite]


def chest(w, h, o="w", hi="V", mid="v", sh="W", la="C", lb="c"):
    seam = (h - 1) // 2
    cx = w // 2
    rows = []
    for y in range(h):
        r = ""
        for x in range(w):
            if y in (0, h - 1) or x in (0, w - 1) or y == seam:
                ch = o
            elif y == 1:
                ch = hi if x < w - 2 else mid
            elif x == w - 2 or y == h - 2:
                ch = sh
            else:
                ch = mid
            if x in (cx - 1, cx) and seam <= y <= seam + 1:
                ch = la if (y == seam or x == cx - 1) else lb
            r += ch
        rows.append(r)
    return rows


PIN = [
    ".rrr.",
    "rQRRr",
    "rRFRr",
    "rReer",
    ".rer.",
    "..r..",
]

HEAD = [                                           # copper golem face, eyes closed
    ".KKKKKKK.",
    "KhCCCCCcK",
    "KCcccccdK",
    "KCKKcKKdK",
    "KCccCccdK",
    "KcccCdcdK",
    "KcccdccdK",
    ".KKKKKKK.",
]
Z_SMALL = ["BBB", "..B", ".B.", "bbb"]
Z_BIG = ["BBBBB", "..BB.", ".BB..", "bbbbb"]


def arrow(shaft):
    """Right-pointing copper arrow, 9 tall, `shaft` extra pixels of shaft."""
    rows = [
        "..K....",
        "...K...",
        "KKKCK..",
        "KhCCCK.",
        "KCCCCcK",
        "KcccdK.",
        "KKKdK..",
        "...K...",
        "..K....",
    ]
    ext = "..KCCcK.."
    return [r[0] + ext[i] * shaft + r[1:] for i, r in enumerate(rows)]


BROOM_HANDLE = [".vw."] * 6
BROOM_BAND = ["KhCK", "KcdK"]
BROOM_HEAD = [
    "yZZYYy",
    "yZZYYYYy",
    "yZYZYYYYYy",
    "yZYZYZYYYYYy",
    "yZYZYZYZYYYy",
    "yZYZYZYZYYYy",
    "yyyyyyyyyyyy",
]


def handle(cv, x0, y0, n):
    """Vanilla-style diagonal tool handle from (x0, y0) going up-right for n rows."""
    for i in range(n):
        stamp(cv, x0 + i, y0 - i, ["w" + ("v" if i % 2 == 0 else "W") + "u"])
    stamp(cv, x0, y0 + 1, ["uu"])


# Tool heads are full-canvas overlays drawn for a handle(2, 13, n) handle (the vanilla diagonal).
AXE_HEAD = [                                       # with handle(2, 13, 9)
    "................",
    "........kkk.....",
    ".......khhCk....",
    "......khCCCck...",
    ".....khCcccdwW..",
    ".....khCccdcdu..",
    "......kkkwccdck.",
    "........wWucdck.",
    ".......wvu.kkk..",
]
HOE_HEAD = [                                       # with handle(2, 13, 10)
    "................",
    ".....KKKKKKK....",
    ".....KhhCCCck...",
    ".....kCcdddcdk..",
    ".....kck..kddWu.",
    "......k...wkk...",
]
HAMMER_HEAD = [                                    # with handle(7, 9, 4)
    "..........kk....",
    ".........khCk...",
    "........khCCck..",
    ".........kCCcck.",
    "..........kccdk.",
    "...........kddk.",
    "............kk..",
]
WHEAT = [                                          # sheaf, ears up-right like vanilla wheat
    ".....ZZ.",
    "...ZZYZy",
    "..ZYZyYy",
    "..YZyYy.",
    "...yYy..",
    "..yy....",
    ".yy.....",
    "y.y.....",
]
LOG = [".wwww.", "wWVVWw", "wVvvVw", "wVvvVw", "wWVVWw", ".wwww."]

FOOT = [                                           # bare left footprint: toes, sole, heel
    "f.f.ff",
    "......",
    ".ffff.",
    "ffffff",
    "ffffff",
    ".ffff.",
    ".fff..",
    ".fff..",
    "..f...",
]
CHECK = [
    ".....nn",
    "....nMn",
    "nn.nMn.",
    "nMnMn..",
    ".nMn...",
    "..n....",
]
CROSS = [
    "R...R",
    "rR.Rr",
    ".rRr.",
    ".RrR.",
    "Rr.rR",
    "r...r",
]


PICK_BADGE = [                                     # small iron pickaxe, 8x6
    ".111111.",
    "13333321",
    "12.wv.21",
    "1..wv..1",
    "...wv...",
    "...uu...",
]
SPROUT_BADGE = [                                   # seedling, 8x6
    "nn....nn",
    "nMn..nMn",
    ".nMnnMn.",
    "..nNNn..",
    "...nN...",
    "...nN...",
]
TREE_CROWN = [                                     # 9x8 leaf crown
    "..nnnnn..",
    ".nMMMNNn.",
    "nMMNMMNNn",
    "nMNMMNMNn",
    "nNMMNNMNn",
    "nNNNNNNNn",
    ".nNNnNNn.",
    "..nnnnn..",
]
TREE_TRUNK = ["uwu", "wVw", "wVw", "wvw", "wVw", "uuu"]

QUESTION = [                                       # 5x7 "?"
    ".ZZZ.",
    "Z...Z",
    "....Z",
    "..ZZ.",
    "..Z..",
    ".....",
    "..Z..",
]

ARROW_DOWN = [                                     # 7x8 green arrow, tip down
    ".nnnnn.",
    ".nMMNn.",
    ".nMNNn.",
    "nnnMNnn",
    "nMMMNNn",
    ".nMMNn.",
    "..nMn..",
    "...n...",
]
# orange (copper) arrow pointing up: the same shape flipped, recolored
ARROW_UP = recolor(ARROW_DOWN[::-1], {"n": "k", "M": "C", "N": "c"})

def outlined(sprite, fill, edge):
    """Recolor `fill` pixels that touch transparency (4-neighbour) with `edge`."""
    out = []
    for y, row in enumerate(sprite):
        r = ""
        for x, ch in enumerate(row):
            if ch == ".":
                r += ch
                continue
            e = any(not (0 <= y + dy < len(sprite) and 0 <= x + dx < len(row) and sprite[y + dy][x + dx] != ".")
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            r += edge if e else fill
        out.append(r)
    return out


def _cube_face(x, y):
    if 0 <= x <= 7 and 3 <= y <= 10:
        return "front"
    if 0 <= y <= 2 and 3 - y <= x <= 10 - y:
        return "top"
    if 8 <= x <= 10 and 10 - x <= y <= 17 - x:
        return "right"
    return None


def copper_shade(f, x, y):
    if f == "front":
        return "h" if (x == 1 or y == 4) else ("d" if (x == 6 or y == 9) else "c")
    if f == "top":
        return "h" if y == 1 else "C"
    return "d" if y > 4 else "c"


CT_FRONT = [                                       # crafting table front, 6x6 interior
    "WWWWWW",
    "V3vVWv",
    "v2VvWV",
    "V2vV3v",
    "vWWvWV",
    "VvVvWv",
]


def table_shade(f, x, y):
    if f == "front":
        return CT_FRONT[y - 4][x - 1]
    if f == "top":
        return "w" if (x + y) in (5, 8) else ("V" if y == 1 else "v")
    return "W" if (y - (10 - x)) % 3 else "w"


def oblique_cube(shade=copper_shade, o="K"):
    """Block: 8x8 front face + depth 3 -> 11x11 sprite; `shade(face, x, y)` colors the interior."""
    out = []
    for y in range(11):
        r = ""
        for x in range(11):
            f = _cube_face(x, y)
            if f is None:
                r += "."
                continue
            edge = any(_cube_face(x + dx, y + dy) is None
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge or (f == "front" and (x in (0, 7) or y in (3, 10))) or (x + y == 10 and x >= 7):
                r += o
            else:
                r += shade(f, x, y)
        out.append(r)
    return out


def dashed_rect(cv, x0, y0, x1, y1, ch):
    pts = [(x, y0) for x in range(x0, x1 + 1)] + [(x1, y) for y in range(y0 + 1, y1 + 1)] \
        + [(x, y1) for x in range(x1 - 1, x0 - 1, -1)] + [(x0, y) for y in range(y1 - 1, y0, -1)]
    for i, (x, y) in enumerate(pts):
        if i % 3 != 2:
            cv[y][x] = ch


def crafting_board():
    b = [["w"] * 10 for _ in range(10)]
    for y in range(1, 9):
        for x in range(1, 9):
            b[y][x] = "V"
    for gy in range(3):
        for gx in range(3):
            for dy in range(2):
                for dx in range(2):
                    X, Y = 1 + gx * 3 + dx, 1 + gy * 3 + dy
                    first = (dx, dy) == (0, 0)
                    if gy == 0:                      # copper ingots (top row)
                        b[Y][X] = "C" if first else "c"
                    elif gx == 1:                    # sticks (middle column)
                        b[Y][X] = "W"
                    else:
                        b[Y][X] = "V" if first else "v"
    for i in range(1, 9):
        for L in (3, 6):
            b[L][i] = "w"
            b[i][L] = "w"
    return ["".join(r) for r in b]


# ---- tab-bar icons (Back, Status, Inventory, Settings) and the crafting arrow ----
BACK = [                                           # chunky copper arrow pointing left, long shaft
    "................",
    "......K.........",
    ".....KhK........",
    "....KhCK........",
    "...KhCCKKKKKKK..",
    "..KhCCCCCCCCCcK.",
    ".KhCCCCCCCCCccK.",
    "KhCCCCCCCCCcccK.",
    ".KcccccccccdddK.",
    "..KcccddddddddK.",
    "...KcdKKKKKKKK..",
    "....KdK.........",
    ".....KdK........",
    "......K.........",
    "................",
    "................",
]
STATUS = [                                         # copper clipboard: paper, text lines, green ticks
    "................", "......kkkk......", ".....kChhCk.....", "..kkkkcKKckkkk..",
    "..kCCCCCCCCCck..", "..kCk555555kck..", "..kCk522225kck..", "..kCk555555kck..",
    "..kCk52225Nkck..", "..kCk5555N5kck..", "..kCk5N5N55kck..", "..kCk55N555kck..",
    "..kCk555555kck..", "..kdkkkkkkkkdk..", "..kdddddddddck..", "...kkkkkkkkkk...",
]
INVENTORY = [                                      # leather satchel with copper trim and buckle
    "................", "................", ".uuukkuuuukkuuu.", ".uVVCcVVVVCcVVu.",
    ".uvvCcvkkvCcvWu.", ".uvvCckhCkCcvWu.", ".uvvCckCckCcvWu.", ".uuukkkkkkkkuuu.",
    ".uvvCckddkCcvWu.", ".uvvCcvkkvCcvWu.", ".uvvCcvvvvCcvWu.", ".uvvCcvvvvCcvWu.",
    ".uWWCcWWWWCcWWu.", ".uuukkuuuukkuuu.", "................", "................",
]
CRAFT_ARROW = [                                    # vanilla-style grey arrow pointing right
    "................", "................", ".........1......", ".........21.....",
    "..1111111331....", ".16666666655311.", ".15555555544331.", ".14444444433321.",
    ".12222222222211.", "..1111111221....", ".........21.....", ".........1......",
    "................", "................", "................", "................",
]
GEAR_TOP = ["......####......", "..##..####..##..", ".####.####.####.", ".##############.",
            "..############..", "..############.."]


def gear():
    """Procedural cog: 8 teeth, a 4x4 hole, outline k, diagonal copper shading."""
    mask = GEAR_TOP + ["######....######"] * 4 + GEAR_TOP[::-1]

    def solid(x, y):
        return 0 <= x < 16 and 0 <= y < 16 and mask[y][x] == "#"

    cv = blank()
    for y in range(16):
        for x in range(16):
            if not solid(x, y):
                continue
            if any(not solid(x + a, y + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                cv[y][x] = "k"
            else:
                s = (x + y) - 15
                cv[y][x] = "h" if s < -7 else "C" if s < -2 else "c" if s < 4 else "d"
    return cv


def fromrows(rows):
    return [list(r) for r in rows]


def build():
    icons = {}

    cv = blank()                                   # 1 idle golem head + zZ
    stamp(cv, 1, 7, HEAD)
    stamp(cv, 6, 2, Z_SMALL)
    stamp(cv, 10, 1, Z_BIG)
    icons["profession_none"] = cv

    cv = blank()                                   # 2 chest -> chest
    stamp(cv, 2, 1, arrow(5))
    stamp(cv, 1, 9, chest(6, 6))
    stamp(cv, 9, 9, chest(6, 6))
    icons["profession_transporter"] = cv

    cv = blank()                                   # 3 copper chest + items to sort
    stamp(cv, 2, 7, chest(11, 8, "K", "h", "c", "d", "G", "g"))
    for x0, hi, mid, sh, edge in ((1, "Q", "R", "e", "r"), (6, "M", "N", "n", "n"),
                                  (11, "l", "b", "L", "L")):
        stamp(cv, x0, 1, [edge * 4, edge + hi + mid + edge, edge + mid + sh + edge, edge * 4])
    icons["profession_chest_sorter"] = cv

    cv = blank()                                   # 4 broom
    stamp(cv, 6, 1, BROOM_HANDLE)
    stamp(cv, 6, 7, BROOM_BAND)
    for i, row in enumerate(BROOM_HEAD):
        stamp(cv, 8 - len(row) // 2, 9 + i, [row])
    icons["profession_cleaner"] = cv

    cv = blank()                                   # 5 recipe board + hammer
    stamp(cv, 0, 5, crafting_board())
    handle(cv, 7, 9, 4)
    stamp(cv, 0, 0, HAMMER_HEAD)
    icons["profession_crafter"] = cv

    cv = blank()                                   # 6 hoe + wheat
    handle(cv, 2, 13, 10)
    stamp(cv, 0, 0, HOE_HEAD)
    stamp(cv, 7, 7, WHEAT)
    icons["profession_farmer"] = cv

    cv = blank()                                   # 7 axe + log
    handle(cv, 2, 13, 9)
    stamp(cv, 0, 0, AXE_HEAD)
    stamp(cv, 9, 9, LOG)
    icons["profession_lumber"] = cv

    for name, col, badge in (("follow_on", "d", CHECK), ("follow_off", "2", CROSS)):
        cv = blank()                               # 8/9 footprints + on/off badge
        foot, foot2 = recolor(FOOT, {"f": col}), recolor([r[::-1] for r in FOOT], {"f": col})
        if name == "follow_off":                   # dark outline: grey alone is lost on slot grey
            foot, foot2 = outlined(foot, "2", "1"), outlined(foot2, "2", "1")
        stamp(cv, 1, 7, foot)
        stamp(cv, 7, 1, foot2)
        stamp(cv, 15 - len(badge[0]), 9, badge)
        icons[name] = cv

    cv = blank()                                   # 10 chest + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 10, 0, PIN)
    icons["assign_chest"] = cv

    cv = blank()                                   # 11 crafting table + pin
    stamp(cv, 0, 4, oblique_cube(table_shade, "w"))
    stamp(cv, 10, 0, PIN)
    icons["assign_crafting_table"] = cv

    cv = blank()                                   # 12 copper block + pin
    stamp(cv, 0, 4, oblique_cube())
    stamp(cv, 10, 0, PIN)
    icons["assign_block"] = cv

    cv = blank()                                   # 13 dashed area + two pins
    dashed_rect(cv, 2, 6, 12, 14, "C")
    stamp(cv, 0, 0, PIN)
    stamp(cv, 10, 9, PIN)
    icons["assign_area"] = cv

    cv = blank()                                   # 14 chest + green arrow down into it + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 2, 0, ARROW_DOWN)
    stamp(cv, 10, 0, PIN)
    icons["assign_input"] = cv

    cv = blank()                                   # 15 chest + orange arrow up out of it + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 2, 0, ARROW_UP)
    stamp(cv, 10, 0, PIN)
    icons["assign_output"] = cv

    cv = blank()                                   # 16 chest + pickaxe badge + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 1, 0, PICK_BADGE)
    stamp(cv, 10, 0, PIN)
    icons["assign_tools"] = cv

    cv = blank()                                   # 17 chest + sprout badge + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 1, 0, SPROUT_BADGE)
    stamp(cv, 10, 0, PIN)
    icons["assign_seeds"] = cv

    cv = blank()                                   # 18 tree + pin
    stamp(cv, 1, 2, TREE_CROWN)
    stamp(cv, 4, 10, TREE_TRUNK)
    stamp(cv, 10, 0, PIN)
    icons["assign_tree"] = cv

    cv = blank()                                   # 20 copper-bound book with a "?" (Help)
    for y in range(1, 15):
        for x in range(2, 14):
            edge = y in (1, 14) or x in (2, 13)
            cv[y][x] = "k" if edge else "d" if x == 3 else "F" if x == 12 else "C" if y == 2 else "c"
    stamp(cv, 6, 4, QUESTION)
    icons["help"] = cv

    icons["icon_back"] = fromrows(BACK)               # 21..25 tab bar and crafting arrow
    icons["icon_status"] = fromrows(STATUS)
    icons["icon_inventory"] = fromrows(INVENTORY)
    icons["icon_settings"] = gear()
    icons["craft_arrow"] = fromrows(CRAFT_ARROW)

    return icons


def rgba(cv, pal):
    raw = bytearray()
    for row in cv:
        raw.append(0)
        for ch in row:
            raw += b"\0\0\0\0" if ch == "." else bytes.fromhex(pal[ch] + ("FF" if len(pal[ch]) == 6 else ""))
    return bytes(raw)


def png(raw, w=16, h=16):
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def sheet(icons, path, scale, per):
    cell, names = 8, list(icons)
    nrows = -(-len(names) // per)
    w, h = cell + per * (16 * scale + cell), cell + nrows * (16 * scale + cell)
    px = [[(139, 139, 139, 255)] * w for _ in range(h)]
    for n, name in enumerate(names):
        x0 = cell + (n % per) * (16 * scale + cell)
        y0 = cell + (n // per) * (16 * scale + cell)
        for y, row in enumerate(icons[name]):
            for x, ch in enumerate(row):
                if ch == ".":
                    continue
                c = PAL[ch]
                r, g, b = (int(c[k:k + 2], 16) for k in (0, 2, 4))
                a = int(c[6:8], 16) if len(c) == 8 else 255
                bgc = px[0][0]
                col = tuple((v * a + bv * (255 - a)) // 255 for v, bv in zip((r, g, b), bgc[:3])) + (255,)
                for dy in range(scale):
                    for dx in range(scale):
                        px[y0 + y * scale + dy][x0 + x * scale + dx] = col
    raw = bytearray()
    for row in px:
        raw.append(0)
        for c in row:
            raw += bytes(c)
    with open(path, "wb") as f:
        f.write(png(bytes(raw), w, h))


def main():
    os.makedirs(OUT, exist_ok=True)
    icons = build()
    for name, cv in icons.items():
        assert len(cv) == 16 and all(len(r) == 16 for r in cv), name
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(png(rgba(cv, PAL)))
    here = os.path.dirname(os.path.abspath(__file__))
    sheet(icons, os.path.join(here, "preview.png"), 8, 8)
    sheet(icons, os.path.join(here, "preview_1x.png"), 1, 20)
    print("wrote", len(icons), "icons to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
