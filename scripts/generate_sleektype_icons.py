import os
import shutil
from PIL import Image, ImageDraw

def create_icons():
    src_path = r"C:\Users\LENOVO\.gemini\antigravity-ide\brain\48b88a69-cdf8-4730-93ab-2ca6e50462f9\.user_uploaded\media_1790434001155.jpg"
    res_dir = r"c:\Users\LENOVO\Desktop\HeliBoard-main\app\src\main\res"

    src_img = Image.open(src_path).convert("RGBA")
    src_w, src_h = src_img.size

    # Densities: (folder_suffix, legacy_size, adaptive_size)
    densities = [
        ("mdpi", 48, 108),
        ("hdpi", 72, 162),
        ("xhdpi", 96, 216),
        ("xxhdpi", 144, 324),
        ("xxxhdpi", 192, 432),
    ]

    bg_color = (16, 19, 32, 255) # #101320

    def make_rounded(img, size, radius_pct=0.18):
        scale = 4
        big_size = size * scale
        big_img = img.resize((big_size, big_size), Image.Resampling.LANCZOS)
        mask = Image.new("L", (big_size, big_size), 0)
        draw = ImageDraw.Draw(mask)
        draw.rounded_rectangle((0, 0, big_size - 1, big_size - 1), radius=int(big_size * radius_pct), fill=255)
        out = Image.new("RGBA", (big_size, big_size), (0, 0, 0, 0))
        out.paste(big_img, (0, 0), mask)
        return out.resize((size, size), Image.Resampling.LANCZOS)

    def make_circular(img, size):
        scale = 4
        big_size = size * scale
        big_img = img.resize((big_size, big_size), Image.Resampling.LANCZOS)
        mask = Image.new("L", (big_size, big_size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, big_size - 1, big_size - 1), fill=255)
        out = Image.new("RGBA", (big_size, big_size), (0, 0, 0, 0))
        out.paste(big_img, (0, 0), mask)
        return out.resize((size, size), Image.Resampling.LANCZOS)

    def make_adaptive_fg(img, size):
        # The adaptive icon canvas is 108dp. Safe zone is inner 72dp (~66.6% - 75%).
        # We scale the artwork to 82% so the keyboard and speech bubble fit inside the safe zone.
        scale = 4
        big_size = size * scale
        out = Image.new("RGBA", (big_size, big_size), bg_color)
        inner_size = int(big_size * 0.82)
        resized_inner = img.resize((inner_size, inner_size), Image.Resampling.LANCZOS)
        offset = (big_size - inner_size) // 2
        out.paste(resized_inner, (offset, offset))
        return out.resize((size, size), Image.Resampling.LANCZOS)

    for suffix, leg_size, adap_size in densities:
        mipmap_dir = os.path.join(res_dir, f"mipmap-{suffix}")
        drawable_dir = os.path.join(res_dir, f"drawable-{suffix}")
        os.makedirs(mipmap_dir, exist_ok=True)
        os.makedirs(drawable_dir, exist_ok=True)

        # 1. Legacy Square Icon
        sq_icon = make_rounded(src_img, leg_size)
        sq_path = os.path.join(mipmap_dir, "ic_launcher.png")
        sq_icon.save(sq_path, "PNG", optimize=True)
        print(f"Saved {sq_path} ({leg_size}x{leg_size})")

        # 2. Legacy Round Icon
        rd_icon = make_circular(src_img, leg_size)
        rd_path = os.path.join(mipmap_dir, "ic_launcher_round.png")
        rd_icon.save(rd_path, "PNG", optimize=True)
        print(f"Saved {rd_path} ({leg_size}x{leg_size})")

        # 3. Adaptive Foreground Icon (placed in drawable-<density>)
        fg_icon = make_adaptive_fg(src_img, adap_size)
        fg_path = os.path.join(drawable_dir, "ic_launcher_foreground.png")
        fg_icon.save(fg_path, "PNG", optimize=True)
        print(f"Saved {fg_path} ({adap_size}x{adap_size})")

    # Also remove old vector drawable/ic_launcher_foreground.xml if it exists
    old_xml = os.path.join(res_dir, "drawable", "ic_launcher_foreground.xml")
    if os.path.exists(old_xml):
        os.remove(old_xml)
        print("Removed old vector ic_launcher_foreground.xml")

    # Update fastlane store icon (512x512)
    fastlane_dir = r"c:\Users\LENOVO\Desktop\HeliBoard-main\fastlane\metadata\android\en-US\images"
    if os.path.exists(fastlane_dir):
        store_icon = make_rounded(src_img, 512, radius_pct=0.18)
        store_path = os.path.join(fastlane_dir, "icon.png")
        store_icon.save(store_path, "PNG", optimize=True)
        print(f"Saved Fastlane 512x512 icon: {store_path}")

    print("All icons generated successfully!")

if __name__ == "__main__":
    create_icons()
