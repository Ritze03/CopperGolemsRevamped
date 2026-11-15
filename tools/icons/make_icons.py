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
    "K": "5A2E1E", "d": "8C4A30", "c": "C15A36", "C": "E77C56", "h": "FBB497",
    # oxidized accent
    "g": "3B8B78", "G": "5BC1A4",
    # wood
    "w": "4C3A22", "W": "6B5132", "v": "9C7A4A", "V": "B8945F",
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

HEAD = [
    ".KKKKKKKK.",
    "KhCCCCCCcK",
    "KCccccccdK",
    "KCKKccKKdK",
    "KCccccccdK",
    "KCccCCccdK",
    "KCccCdccdK",
    "KcccccccdK",
    ".KKKKKKKK.",
]
Z_BIG = ["BBB", ".B.", "bbb"]

ARROW = [
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

HOE = (
    ["KKKKKKK", "KhCCCcK", "KCcccdK", "KcKKKvw", "KcK..vw", "KKK..vw"]
    + [".....vw"] * 8
)
STALK = [".Z.", "ZYy", "ZYy", "ZYy", ".Yy"]
AXE = (
    [".KKKKKK.", "KhCCCCcK", "KCCcccdK", "KCccccdK", ".KcccdK.", "..KKKvw."]
    + [".....vw."] * 8
)
LOG = [".wwww.", "wWVVWw", "wVvvVw", "wVvvVw", "wWVVWw", ".wwww."]
SAPLING = [".nnn.", "nMMNn", "nMNNn", ".nnn.", "..v..", "..v.."]

FOOT = [
    ".ooo.",
    "olffo",
    "offfo",
    "offso",
    "ofsso",
    ".ooo.",
    ".....",
    ".ooo.",
    "offso",
    ".ooo.",
]
FOOT_GREEN = {"o": "n", "l": "M", "f": "N", "s": "n"}
FOOT_GREY = {"o": "1", "l": "3", "f": "2", "s": "1"}

HAMMER_HEAD = ["KKKKKK", "KhCCcK", "KCccdK", "KKKKKK"]

TABLE = [
    "wwwwwwwwww",
    "wVVwVVwVVw",
    "wwwwwwwwww",
    "wvvwvvwvvw",
    "wwwwwwwwww",
    "wvvwvvwvvw",
    "wwwwwwwwww",
    ".Ww....Ww.",
    ".Ww....Ww.",
    ".Ww....Ww.",
]


def line_sprite(w, h, pts, fill, edge):
    """Thick diagonal line: 2px wide `fill`, 1px outline `edge` around it."""
    s = [["."] * w for _ in range(h)]
    for x, y in pts:
        for xx in (x, x + 1):
            s[y][xx] = fill
    for y in range(h):
        for x in range(w):
            if s[y][x] == ".":
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and s[ny][nx] == fill:
                        s[y][x] = edge
                        break
    return ["".join(r) for r in s]


def _cube_face(x, y):
    if 0 <= x <= 7 and 3 <= y <= 10:
        return "front"
    if 0 <= y <= 2 and 3 - y <= x <= 10 - y:
        return "top"
    if 8 <= x <= 10 and 10 - x <= y <= 17 - x:
        return "right"
    return None


def oblique_cube():
    """Copper block: 8x8 front face + depth 3 -> 11x11 sprite."""
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
                r += "K"
            elif f == "front":
                r += "h" if (x == 1 or y == 4) else ("d" if (x == 6 or y == 9) else "c")
            elif f == "top":
                r += "h" if y == 1 else "C"
            else:
                r += "d" if y > 4 else "c"
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


def build():
    icons = {}

    cv = blank()                                   # 1 idle golem head + zzz
    stamp(cv, 1, 6, HEAD)
    stamp(cv, 12, 0, Z_BIG)
    stamp(cv, 9, 3, Z_BIG)
    icons["profession_none"] = cv

    cv = blank()                                   # 2 chest -> arrow
    stamp(cv, 0, 4, chest(7, 8))
    stamp(cv, 8, 3, ARROW)
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
    stamp(cv, 9, 1, HAMMER_HEAD)
    stamp(cv, 11, 5, ["vw"] * 10)
    icons["profession_crafter"] = cv

    cv = blank()                                   # 6 hoe + wheat
    stamp(cv, 0, 6, STALK + [".y."] * 4)
    stamp(cv, 3, 3, STALK + [".y."] * 7)
    stamp(cv, 6, 1, HOE)
    icons["profession_farmer"] = cv

    cv = blank()                                   # 7 axe + log + sapling
    stamp(cv, 1, 1, AXE)
    stamp(cv, 9, 9, LOG)
    stamp(cv, 10, 2, SAPLING)
    icons["profession_lumber"] = cv

    cv = blank()                                   # 8 follow on
    stamp(cv, 2, 5, recolor(FOOT, FOOT_GREEN))
    stamp(cv, 9, 1, recolor(FOOT, FOOT_GREEN))
    icons["follow_on"] = cv

    cv = blank()                                   # 9 follow off
    stamp(cv, 2, 5, recolor(FOOT, FOOT_GREY))
    stamp(cv, 9, 1, recolor(FOOT, FOOT_GREY))
    stamp(cv, 0, 0, line_sprite(16, 16, [(i, 14 - i) for i in range(1, 14)], "R", "r"))
    icons["follow_off"] = cv

    cv = blank()                                   # 10 chest + pin
    stamp(cv, 0, 6, chest(11, 9))
    stamp(cv, 10, 0, PIN)
    icons["assign_chest"] = cv

    cv = blank()                                   # 11 crafting table + pin
    stamp(cv, 0, 5, TABLE)
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

    return icons


FILLER = {"a": "2E2A28", "t": "3A3532", "b": "262221", "p": "322D2A"}


def filler():                                      # 14 opaque, low-contrast tile
    g = [["a"] * 16 for _ in range(16)]
    for i in range(16):
        g[0][i] = g[i][0] = "t"
        g[15][i] = g[i][15] = "b"
    g[0][15] = g[15][0] = "a"
    for x, y in ((4, 5), (11, 3), (8, 10), (3, 12), (12, 13)):
        g[y][x] = "p"
    return g


def rgba(cv, pal):
    raw = bytearray()
    for row in cv:
        raw.append(0)
        for ch in row:
            raw += b"\0\0\0\0" if ch == "." else bytes.fromhex(pal[ch]) + b"\xff"
    return bytes(raw)


def png(raw):
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    os.makedirs(OUT, exist_ok=True)
    icons = build()
    icons["filler"] = filler()
    for name, cv in icons.items():
        assert len(cv) == 16 and all(len(r) == 16 for r in cv), name
        pal = FILLER if name == "filler" else PAL
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(png(rgba(cv, pal)))
    print("wrote", len(icons), "icons to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
