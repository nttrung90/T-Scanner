import os
import re
import sys
import json
import time
import shutil
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

FORMAT_PATTERN = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])")

LOCALES_CONFIG = {
    # 6 ngôn ngữ UI tự động cần dịch/đồng bộ từ values (base English)
    # (vi là ngôn ngữ gốc values-vi, en là base values)
    "es": {"folder": "values-es", "api_lang": "es"},
    "pt": {"folder": "values-pt", "api_lang": "pt"},
    "fr": {"folder": "values-fr", "api_lang": "fr"},
    "id": {"folder": "values-in", "api_lang": "id"},
    "de": {"folder": "values-de", "api_lang": "de"},
    "ja": {"folder": "values-ja", "api_lang": "ja"},
}

def get_format_signature(text):
    result = []
    implicit = 0
    for index, kind in FORMAT_PATTERN.findall(text):
        if kind in ("%", "n"):
            continue
        if not index:
            implicit += 1
            index = str(implicit)
        result.append((index, kind))
    return sorted(result)

def protect_placeholders(text):
    placeholders = []
    def repl(m):
        idx = len(placeholders)
        placeholders.append(m.group(0))
        return f"__PH{idx}__"
    protected = FORMAT_PATTERN.sub(repl, text)
    return protected, placeholders

def restore_placeholders(text, placeholders):
    restored = text
    for idx, ph in enumerate(placeholders):
        pattern = re.compile(rf"__\s*ph\s*{idx}\s*__", re.IGNORECASE)
        restored = pattern.sub(ph, restored)
    return restored

def translate_text(text, target_lang, source_lang='en'):
    if not text.strip():
        return text
    protected, placeholders = protect_placeholders(text)
    url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={source_lang}&tl={target_lang}&dt=t&q={urllib.parse.quote(protected)}"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                translated_parts = [part[0] for part in data[0] if part and part[0]]
                translated = "".join(translated_parts)
                restored = restore_placeholders(translated, placeholders)
                return restored
        except Exception as e:
            time.sleep(0.5 * (attempt + 1))
    return text

def translate_batch(texts, target_lang, source_lang='en'):
    if not texts:
        return []
    DELIM = " ||| "
    protected_items = []
    all_placeholders = []
    for t in texts:
        prot, phs = protect_placeholders(t)
        protected_items.append(prot)
        all_placeholders.append(phs)

    joined = DELIM.join(protected_items)
    url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={source_lang}&tl={target_lang}&dt=t&q={urllib.parse.quote(joined)}"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                translated = "".join([part[0] for part in data[0] if part and part[0]])
                parts = translated.split('|||')
                if len(parts) == len(texts):
                    results = []
                    for p, phs in zip(parts, all_placeholders):
                        results.append(restore_placeholders(p.strip(), phs))
                    return results
        except Exception as e:
            time.sleep(0.5 * (attempt + 1))

    # Fallback: individual translation
    print(f"    (Falling back to individual translation for {len(texts)} items...)")
    results = []
    for t in texts:
        results.append(translate_text(t, target_lang, source_lang))
    return results

def escape_xml(text):
    if text is None:
        return ""
    text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace(r"\'", "'").replace(r'\"', '"')
    text = text.replace("&", "&amp;")
    text = text.replace("<", "&lt;")
    text = text.replace(">", "&gt;")
    text = text.replace("'", r"\'")
    text = text.replace('"', r'\"')
    if text.startswith("@"):
        text = r"\@" + text[1:]
    elif text.startswith("?"):
        text = r"\?" + text[1:]
    return text

def read_base_resources():
    base_file = RES / "values/strings.xml"
    tree = ET.parse(base_file)
    root = tree.getroot()
    base_strings = []
    for s in root.findall("string"):
        name = s.get("name")
        text = "".join(s.itertext())
        base_strings.append((name, text))
    
    base_plurals = []
    for p in root.findall("plurals"):
        name = p.get("name")
        items = {}
        for item in p.findall("item"):
            items[item.get("quantity")] = "".join(item.itertext())
        base_plurals.append((name, items))
    
    return base_strings, base_plurals

def translate_locale(locale_tag, config, base_strings, base_plurals):
    folder_name = config["folder"]
    api_lang = config["api_lang"]
    target_dir = RES / folder_name
    target_file = target_dir / "strings.xml"

    existing_strings = {}
    if target_file.exists():
        try:
            tree = ET.parse(target_file)
            for s in tree.getroot().findall("string"):
                existing_strings[s.get("name")] = "".join(s.itertext())
        except Exception as e:
            print(f"Error parsing existing {target_file}: {e}")

    # Keys that need update/refresh
    refresh_keys = {
        "vip_perk_5", "ocr_engine_auto_desc", "ocr_engine_tesseract_desc",
        "ocr_engine_paddle_desc", "ocr_engine_mlkit_desc", "back_to_root_folder"
    }

    prepared_items = []
    items_to_translate_idx = []
    texts_to_translate = []

    for idx, (name, base_text) in enumerate(base_strings):
        if name in existing_strings and name not in refresh_keys:
            val = existing_strings[name]
            if get_format_signature(base_text) != get_format_signature(val):
                items_to_translate_idx.append(idx)
                texts_to_translate.append(base_text)
                prepared_items.append([name, None, base_text])
            else:
                prepared_items.append([name, val, base_text])
        else:
            items_to_translate_idx.append(idx)
            texts_to_translate.append(base_text)
            prepared_items.append([name, None, base_text])

    print(f"\n[{locale_tag}] Folder {folder_name}: {len(base_strings) - len(texts_to_translate)} existing valid, {len(texts_to_translate)} to translate...")

    CHUNK_SIZE = 15
    translated_results = []
    for c in range(0, len(texts_to_translate), CHUNK_SIZE):
        chunk = texts_to_translate[c:c + CHUNK_SIZE]
        chunk_res = translate_batch(chunk, api_lang)
        translated_results.extend(chunk_res)
        print(f"  [{locale_tag}] Translated {len(translated_results)} / {len(texts_to_translate)} strings...")
        time.sleep(0.1)

    for item_idx, trans in zip(items_to_translate_idx, translated_results):
        base_text = prepared_items[item_idx][2]
        if trans is None or get_format_signature(base_text) != get_format_signature(trans):
            prepared_items[item_idx][1] = base_text
        else:
            prepared_items[item_idx][1] = trans

    # Ensure no None
    for item in prepared_items:
        if item[1] is None:
            item[1] = item[2]

    # Plurals
    ASIAN_LANGS = {"zh-Hans", "ja", "ko", "th", "vi", "id", "ms", "fil"}
    final_plurals = []
    for name, items in base_plurals:
        plural_items = {}
        for quantity, text in items.items():
            translated_pl = translate_text(text, api_lang)
            if get_format_signature(text) != get_format_signature(translated_pl):
                translated_pl = text
            plural_items[quantity] = translated_pl
        final_plurals.append((name, plural_items))

    # Build strings.xml content
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for name, val, base_text in prepared_items:
        escaped = escape_xml(val)
        # Check if formatted="false" needed
        base_sig = get_format_signature(base_text)
        if not base_sig and "%" in escaped:
            lines.append(f'    <string name="{name}" formatted="false">{escaped}</string>')
        else:
            lines.append(f'    <string name="{name}">{escaped}</string>')

    if final_plurals:
        lines.append('')
        lines.append('    <!-- Plurals Resources -->')
        for name, p_items in final_plurals:
            lines.append(f'    <plurals name="{name}">')
            quantities = ["other"] if locale_tag in ASIAN_LANGS else ["one", "other"]
            for q in quantities:
                if q in p_items:
                    escaped_item = escape_xml(p_items[q])
                    lines.append(f'        <item quantity="{q}">{escaped_item}</item>')
                elif "other" in p_items:
                    escaped_item = escape_xml(p_items["other"])
                    lines.append(f'        <item quantity="{q}">{escaped_item}</item>')
            lines.append('    </plurals>')

    lines.append('</resources>\n')
    content = "\n".join(lines)

    target_dir.mkdir(parents=True, exist_ok=True)
    with open(target_file, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"[{locale_tag}] Done! Written {len(prepared_items)} strings and {len(final_plurals)} plurals to {target_file}")

    # Synchronize alias folder if any
    alias_folder = config.get("alias_folder")
    if alias_folder:
        alias_dir = RES / alias_folder
        alias_dir.mkdir(parents=True, exist_ok=True)
        alias_file = alias_dir / "strings.xml"
        shutil.copyfile(target_file, alias_file)
        print(f"[{locale_tag}] Synchronized alias folder {alias_folder}")

if __name__ == "__main__":
    base_strings, base_plurals = read_base_resources()
    target_locales = sys.argv[1:] if len(sys.argv) > 1 else list(LOCALES_CONFIG.keys())
    print(f"Total base strings: {len(base_strings)}, plurals: {len(base_plurals)}")
    print(f"Target locales to process: {target_locales}")

    for tag in target_locales:
        if tag in LOCALES_CONFIG:
            translate_locale(tag, LOCALES_CONFIG[tag], base_strings, base_plurals)
        else:
            print(f"Unknown locale tag: {tag}")
