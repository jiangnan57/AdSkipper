"""生成秒跳的应用图标位图（不依赖 PIL / ImageMagick，纯 zlib 写 PNG）。

几何与 res/drawable/ic_launcher_foreground.xml 保持一致，108 单位视口。
用法：python tools/gen_icon.py
产出：dist/icon/icon-512.png（圆角，透明角）、icon-1024.png、icon-512-square.png（满幅）
"""
import os
import struct
import zlib

# ---------- 几何（108 视口，与 vector XML 同一套坐标） ----------
TRI = [(29.0, 29.0), (63.0, 54.0), (29.0, 79.0)]
BAR = (67.5, 29.0, 76.5, 79.0)  # x0, y0, x1, y1
BAR_R = 2.2                     # 竖条的圆角

BG_TOP = (0x2F, 0x7D, 0xF6)
BG_BOT = (0x15, 0x52, 0xAA)
WHITE = (0xFF, 0xFF, 0xFF)

SS = 2  # 每个像素 2x2 子采样


def _in_tri(px, py, a, b, c):
    def cross(o, p, q):
        return (p[0] - o[0]) * (q[1] - o[1]) - (p[1] - o[1]) * (q[0] - o[0])
    p = (px, py)
    d1, d2, d3 = cross(a, b, p), cross(b, c, p), cross(c, a, p)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def _in_rrect(px, py, x0, y0, x1, y1, r):
    cx = min(max(px, x0 + r), x1 - r)
    cy = min(max(py, y0 + r), y1 - r)
    dx, dy = px - cx, py - cy
    if x0 + r <= px <= x1 - r and y0 <= py <= y1:
        return True
    if y0 + r <= py <= y1 - r and x0 <= px <= x1:
        return True
    return dx * dx + dy * dy <= r * r


def coverage_mask(size):
    """返回 size*size 的白色覆盖率（0..1），仅字形区域非零。"""
    k = size / 108.0
    tri = [(x * k, y * k) for x, y in TRI]
    bx0, by0, bx1, by1 = (v * k for v in BAR)
    br = BAR_R * k

    x_min = int(min(tri[0][0], bx0)) - 2
    x_max = int(max(tri[1][0], bx1)) + 2
    y_min = int(min(tri[0][1], by0)) - 2
    y_max = int(max(tri[2][1], by1)) + 2
    x_min, y_min = max(0, x_min), max(0, y_min)
    x_max, y_max = min(size, x_max), min(size, y_max)

    mask = [0.0] * (size * size)
    offs = [(i + 0.5) / SS for i in range(SS)]
    inv = 1.0 / (SS * SS)

    for y in range(y_min, y_max):
        base = y * size
        for x in range(x_min, x_max):
            hit = 0
            for oy in offs:
                py = y + oy
                for ox in offs:
                    px = x + ox
                    if _in_tri(px, py, *tri) or _in_rrect(px, py, bx0, by0, bx1, by1, br):
                        hit += 1
            if hit:
                mask[base + x] = hit * inv
    return mask


def render(size, rounded):
    """返回 RGBA 行数据（bytes 列表）。rounded=True 时四角透明。"""
    mask = coverage_mask(size)
    radius = 0.225 * size if rounded else 0.0
    rows = []
    for y in range(size):
        t = y / max(1, size - 1)
        r = round(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t)
        g = round(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t)
        b = round(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t)
        row = bytearray()
        base = y * size
        for x in range(size):
            cov = mask[base + x]
            rr = round(r + (255 - r) * cov)
            gg = round(g + (255 - g) * cov)
            bb = round(b + (255 - b) * cov)
            if rounded:
                # 圆角遮罩：到圆角矩形的有符号距离，1px 过渡带做抗锯齿
                cx = min(max(x + 0.5, radius), size - radius)
                cy = min(max(y + 0.5, radius), size - radius)
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d = (dx * dx + dy * dy) ** 0.5 - radius
                a = 0.0 if d > 0.5 else (1.0 if d < -0.5 else 0.5 - d)
                row += bytes((rr, gg, bb, round(a * 255)))
            else:
                row += bytes((rr, gg, bb, 255))
        rows.append(bytes(row))
    return rows


def box_down(rows, size, factor):
    """按整数倍做箱式降采样，等效于提高抗锯齿。"""
    out = []
    for y in range(0, size, factor):
        row = bytearray()
        for x in range(0, size, factor):
            for c in range(4):
                s = 0
                for dy in range(factor):
                    src = rows[y + dy]
                    for dx in range(factor):
                        s += src[(x + dx) * 4 + c]
                row.append(s // (factor * factor))
        out.append(bytes(row))
    return out


def write_png(path, rows, size):
    raw = b"".join(b"\x00" + r for r in rows)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def main():
    out = os.path.join("dist", "icon")
    os.makedirs(out, exist_ok=True)
    big = 1024
    for name, rounded in (("icon-1024", True), ("icon-1024-square", False)):
        rows = render(big, rounded)
        n = write_png(os.path.join(out, name + ".png"), rows, big)
        print(f"{name}.png  {big}x{big}  {n/1024:.0f} KB")
        if rounded:
            small = box_down(rows, big, 2)
            n2 = write_png(os.path.join(out, "icon-512.png"), small, big // 2)
            print(f"icon-512.png  512x512  {n2/1024:.0f} KB")


if __name__ == "__main__":
    main()
