#!/usr/bin/env python3
"""
Renders a scripted terminal demo to MP4: title cards, typed commands, real command output and
captions. No screen recorder needed -- frames are drawn with Pillow and piped to ffmpeg.

    python3 render_terminal.py scenes/01-what-runs.py out/01-what-runs.mp4

A scene file defines SCENES, a list of steps:
    ("title", "Heading", "Subheading", seconds)
    ("type", "dede wknd --request-log logs/request.log")     # typed at the prompt
    ("out", ["line", "!highlighted line", "~dim line"], per_line_seconds)
    ("caption", "Lower-third text")                          # stays until the next caption
    ("pause", seconds)
    ("clear",)

Output lines are pasted from real runs (see ../record-demo.sh); "..." marks trimmed output.
Requires: Python 3 with Pillow, ffmpeg with libx264, and macOS system fonts (Menlo, SF).
"""
import runpy
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1280, 720, 30
BG = (13, 17, 23)
WINDOW = (22, 27, 34)
TITLE_BAR = (33, 38, 45)
TEXT = (201, 209, 217)
DIM = (125, 133, 144)
PROMPT = (63, 185, 80)
HIGHLIGHT = (240, 200, 80)
ACCENT = (88, 166, 255)
CAPTION_BG = (31, 111, 235)

MONO = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 18)
SANS = "/System/Library/Fonts/SFNS.ttf"
CAPTION_FONT = ImageFont.truetype(SANS, 26)
TITLE_FONT = ImageFont.truetype(SANS, 56)
SUBTITLE_FONT = ImageFont.truetype(SANS, 28)
SMALL_FONT = ImageFont.truetype(SANS, 18)

MARGIN = 36
BAR_H = 34
LINE_H = 25
TEXT_X = MARGIN + 22
TEXT_Y = MARGIN + BAR_H + 16
CAPTION_H = 76
ROWS = (H - TEXT_Y - CAPTION_H - 30) // LINE_H
CHAR_W = MONO.getlength("M")
COLS = int((W - TEXT_X - MARGIN - 16) // CHAR_W)


class Renderer:
    def __init__(self, ffmpeg, window_title):
        self.ffmpeg = ffmpeg
        self.window_title = window_title
        self.lines = []          # (style, text)
        self.caption = None

    def emit(self, image, seconds):
        frame = image.tobytes()
        for _ in range(max(1, round(seconds * FPS))):
            self.ffmpeg.stdin.write(frame)

    def terminal(self, typing=None):
        img = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([MARGIN, MARGIN, W - MARGIN, H - MARGIN - CAPTION_H + 10], 12, fill=WINDOW)
        d.rounded_rectangle([MARGIN, MARGIN, W - MARGIN, MARGIN + BAR_H], 12, fill=TITLE_BAR)
        d.rectangle([MARGIN, MARGIN + BAR_H - 12, W - MARGIN, MARGIN + BAR_H], fill=TITLE_BAR)
        for i, color in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
            x = MARGIN + 20 + i * 22
            d.ellipse([x - 6, MARGIN + BAR_H // 2 - 6, x + 6, MARGIN + BAR_H // 2 + 6], fill=color)
        d.text((W // 2, MARGIN + BAR_H // 2), self.window_title, font=SMALL_FONT, fill=DIM, anchor="mm")

        lines = list(self.lines)
        if typing is not None:
            lines.append(("cmd", typing + "█"))
        for row, (style, text) in enumerate(lines[-ROWS:]):
            y = TEXT_Y + row * LINE_H
            if style == "cmd":
                # A typed line starting with spaces continues the previous command ("\").
                d.text((TEXT_X, y), ">" if text.startswith(" ") else "$", font=MONO,
                       fill=DIM if text.startswith(" ") else PROMPT)
                d.text((TEXT_X + 2 * CHAR_W, y), clip(text, COLS - 2), font=MONO, fill=(255, 255, 255))
            else:
                color = {"hi": HIGHLIGHT, "dim": DIM, "note": ACCENT}.get(style, TEXT)
                d.text((TEXT_X, y), clip(text, COLS), font=MONO, fill=color)

        if self.caption:
            box = [MARGIN, H - MARGIN - CAPTION_H + 28, W - MARGIN, H - MARGIN + 8]
            d.rounded_rectangle(box, 10, fill=CAPTION_BG)
            d.text(((box[0] + box[2]) // 2, (box[1] + box[3]) // 2), self.caption, font=CAPTION_FONT,
                   fill=(255, 255, 255), anchor="mm")
        return img

    def title(self, heading, sub, seconds):
        img = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(img)
        d.text((W // 2, H // 2 - 40), heading, font=TITLE_FONT, fill=(255, 255, 255), anchor="mm")
        d.line([W // 2 - 80, H // 2 + 8, W // 2 + 80, H // 2 + 8], fill=ACCENT, width=4)
        for i, part in enumerate(sub.split("\n")):
            d.text((W // 2, H // 2 + 50 + i * 40), part, font=SUBTITLE_FONT, fill=TEXT, anchor="mm")
        self.emit(img, seconds)

    def run(self, steps):
        for step in steps:
            kind = step[0]
            if kind == "title":
                self.lines, self.caption = [], None
                self.title(step[1], step[2], step[3])
            elif kind == "type":
                text = step[1]
                for i in range(0, len(text) + 1, 2):   # two characters per frame: ~60 cps
                    self.emit(self.terminal(typing=text[:i]), 1 / FPS)
                self.emit(self.terminal(typing=text), 0.35)
                self.lines.append(("cmd", text))
            elif kind == "out":
                per_line = step[2] if len(step) > 2 else 0.05
                for raw in step[1]:
                    style, text = parse(raw)
                    self.lines.append((style, text))
                    self.emit(self.terminal(), per_line)
            elif kind == "caption":
                self.caption = step[1]
                self.emit(self.terminal(), 0.2)
            elif kind == "pause":
                self.emit(self.terminal(), step[1])
            elif kind == "clear":
                self.lines = []
            else:
                raise ValueError(f"unknown step {step!r}")


def parse(raw):
    if raw.startswith("!"):
        return "hi", raw[1:]
    if raw.startswith("~"):
        return "dim", raw[1:]
    if raw.startswith("#"):
        return "note", raw
    return "out", raw


def clip(text, cols):
    return text if len(text) <= cols else text[: cols - 1] + "…"


def main():
    scene_file, out = sys.argv[1], sys.argv[2]
    scene = runpy.run_path(scene_file)
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
         "-preset", "medium", "-movflags", "+faststart", out],
        stdin=subprocess.PIPE)
    Renderer(ffmpeg, scene.get("WINDOW_TITLE", "dede")).run(scene["SCENES"])
    ffmpeg.stdin.close()
    if ffmpeg.wait() != 0:
        sys.exit("ffmpeg failed")


if __name__ == "__main__":
    main()
